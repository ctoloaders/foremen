package com.foremen.service;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The single CRUD contract for project-scoped services. It {@code extends AdminService}, so it
 * inherits the full CRUD surface, and layers on:
 * <ul>
 *     <li>automatic project filtering on LIST reads (the inherited {@link #addRequiredQuery()}, FOR-03-04);</li>
 *     <li>a {@code default} project-id action resolver {@link #getProjectId(Object)} derived from
 *         {@link #getProjectIdPath()} (FOR-03-04a).</li>
 * </ul>
 *
 * <p>A concrete project-scoped service declares {@code implements ProjectScopedService<...>} (never a
 * plain {@link AdminService}, never both) and supplies only the standard CRUD plumbing
 * ({@code getDao}, {@code getMapper}, {@code getEntityManager}, {@code getDaoModelClass},
 * {@code getAuditLogDao}), {@link #getProjectIdPath()} (the single mandatory per-entity override), and
 * {@link #allowedProjectIds(Long)} wired to {@code ProjectAccessCache.get(userId)}. It overrides NO
 * CRUD method, {@code addRequiredQuery()}, or {@code getProjectId()} (a service MAY override
 * {@code getProjectId} only for a bespoke resolution). This interface introduces no class inheritance
 * (only {@code default}/abstract members), so it stays lightweight and testable (Requirement 1.7).
 *
 * @param <ServiceModel>         the service (list) model
 * @param <ServiceExtendedModel> the service extended model
 * @param <DaoModel>             the DAO/entity model
 * @param <ID>                   the entity id type
 */
public interface ProjectScopedService<ServiceModel, ServiceExtendedModel, DaoModel, ID>
        extends AdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID> {

    /**
     * The sole mandatory per-entity override point: JPA dot-path from the query root to the project
     * id (Requirement 1.1). No default is provided so every concrete project-scoped service MUST
     * declare its path explicitly. It drives both the list filter and, by default, the action
     * resolver {@link #getProjectId(Object)}.
     */
    String getProjectIdPath();

    /**
     * Supplies the caller's allowed project ids for a resolved user id. A concrete service wires
     * this to the {@code ProjectAccessCache} (e.g. {@code projectAccessCache.get(userId)}), keeping
     * this interface free of a hard dependency on the cache bean.
     */
    Set<Long> allowedProjectIds(Long userId);

    /**
     * All shared list-filter decision logic; NOT intended to be overridden by concrete services.
     *
     * <ul>
     *     <li>Req 6.6: no authenticated caller &rarr; match-nothing.</li>
     *     <li>Req 6.1, 6.7: exact-authority ADMIN check &rarr; {@code null} (no filter, ADMIN bypass).</li>
     *     <li>Req 6.5: non-numeric/absent principal name &rarr; match-nothing.</li>
     *     <li>Req 6.3: empty allowed set &rarr; match-nothing.</li>
     *     <li>Req 6.2, 7.1-7.3: non-empty allowed set &rarr; restrict the resolved project-id path to the set.</li>
     * </ul>
     *
     * <p>This is an {@code @Override} of the inherited {@link ReadOnlyAdminService#addRequiredQuery()}
     * default; its read-filter behavior is unchanged from FOR-03-04 (Requirement 7.1).</p>
     */
    @Override
    default Specification<DaoModel> addRequiredQuery() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // Req 6.6: no authenticated caller -> reveal nothing.
        if (auth == null || !auth.isAuthenticated()) {
            return matchNothing();
        }

        // Req 6.1, 6.7: exact-authority ADMIN check (own check; ReadOnlyAdminService.isCallerAdmin is private).
        if (isAdmin(auth)) {
            return null; // ADMIN bypass: no filter combined into the read.
        }

        Long userId = parseUserId(auth.getName()); // Req 6.5: principal name is the numeric userId.
        if (userId == null) {
            return matchNothing();
        }

        Set<Long> allowed = allowedProjectIds(userId);
        if (allowed == null || allowed.isEmpty()) {
            return matchNothing(); // Req 6.3: empty allowed set sees no rows.
        }

        // Req 6.2 + 7.1-7.3: restrict the resolved project-id path to the allowed set.
        return (root, query, cb) -> resolvePath(root, getProjectIdPath()).in(allowed);
    }

    /**
     * Resolves the owning project id of the entity identified by {@code entityId}, or {@code null}
     * if no such entity exists. DEFAULT (Requirements 1.1-1.3): derives the value from the declared
     * {@link #getProjectIdPath()} by building a Criteria query that selects that path for the row
     * whose {@code id} equals {@code entityId}, reusing {@link #resolvePath} for join traversal and
     * the inherited {@link #getEntityManager()} / {@link #getDaoModelClass()} (available because this
     * interface extends {@link AdminService}). Returns {@code null} on no row (uses
     * {@code getResultList()}, never {@code getSingleResult()}), so a missing entity and an
     * out-of-scope entity are handled uniformly by the caller. A concrete service MAY override this
     * for a bespoke resolution (Requirement 1.4); it stays overridable and is never abstract.
     */
    default Long getProjectId(ID entityId) {
        CriteriaBuilder cb = getEntityManager().getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<DaoModel> root = cq.from(getDaoModelClass());
        // resolvePath handles "projectId" (single) and "project.id" / "room.project.id" (joins).
        cq.select(resolvePath(root, getProjectIdPath()).as(Long.class));
        cq.where(cb.equal(root.get("id"), entityId));
        List<Long> rows = getEntityManager().createQuery(cq).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * All action-decision logic for a single-entity {@code Project_Scoped_Action} (Requirement 2);
     * NOT intended to be overridden by concrete services. Returns normally when the action is allowed,
     * or throws a {@link ForemenApiException} with HTTP status 404 and message code
     * {@code error.entity.not.found} when denied — deliberately indistinguishable from a missing
     * entity so out-of-scope records are not revealed (Requirement 2.7, 2.8, 6.1). It reuses the exact
     * caller-resolution ({@link #parseUserId}), ADMIN-detection ({@link #isAdmin}), and allowed-set
     * ({@link #allowedProjectIds(Long)}) logic the read filter uses, so both decisions read from the
     * same source and honor the same invalidation (Requirement 2.9, 2.10).
     *
     * <ul>
     *     <li>Req 2.3: no authenticated caller &rarr; deny.</li>
     *     <li>Req 2.2, 2.9: ADMIN authority &rarr; allow (bypass; {@code getProjectId} is NOT called).</li>
     *     <li>Req 2.4: blank/non-numeric principal name &rarr; deny.</li>
     *     <li>Req 2.5: non-ADMIN with an empty/{@code null} allowed set &rarr; deny.</li>
     *     <li>Req 2.6: non-ADMIN, owning id resolved and IN the allowed set &rarr; allow.</li>
     *     <li>Req 2.7, 2.8: owning id not in the set, or {@code null} &rarr; deny.</li>
     * </ul>
     */
    default void assertProjectAccess(ID entityId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // Req 2.3: no authenticated caller -> deny.
        if (auth == null || !auth.isAuthenticated()) {
            throw denied(entityId);
        }

        // Req 2.2, 2.9: ADMIN bypass -> allow WITHOUT resolving the owning project id.
        if (isAdmin(auth)) {
            return;
        }

        // Req 2.4: principal name is the numeric userId; blank/non-numeric -> deny.
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            throw denied(entityId);
        }

        // Req 2.5, 2.10: empty/null allowed set (from the same cache the read filter uses) -> deny.
        Set<Long> allowed = allowedProjectIds(userId);
        if (allowed == null || allowed.isEmpty()) {
            throw denied(entityId);
        }

        // Req 2.6, 2.7, 2.8: allow iff the owning project id is resolved and in the allowed set.
        Long owningProjectId = getProjectId(entityId);
        if (owningProjectId == null || !allowed.contains(owningProjectId)) {
            throw denied(entityId);
        }
    }

    // --- by-id CRUD overrides (FOR-03-04a) ---
    // Every by-id read/mutation inherited from AdminService/ReadOnlyAdminService is overridden here
    // ONCE: it runs assertProjectAccess (per targeted id for the batch variants, aborting on the
    // first deny) and then delegates to the inherited body via AdminService.super.<method>(...).
    // Signatures mirror the inherited declarations 1:1 so each is a genuine override, not an overload.
    // `create` is deliberately NOT overridden (it resolves no existing id); the guard test asserts
    // this override set stays complete as AdminService evolves (Requirement 7.5, 8.5).

    /** Read-by-id: check ownership, then delegate (Requirement 3.1-3.3). */
    @Override
    default ServiceExtendedModel findById(ID id) {
        assertProjectAccess(id);
        return AdminService.super.findById(id);
    }

    /** Update-by-id: check ownership before any change is persisted (Requirement 4.1-4.3). */
    @Override
    default ServiceExtendedModel update(ID id, ServiceExtendedModel model) {
        assertProjectAccess(id);
        return AdminService.super.update(id, model);
    }

    /** Multi-id update: validate every targeted id, aborting on the first deny (Requirement 4.5). */
    @Override
    default List<ServiceExtendedModel> updateAll(List<ID> ids, ServiceExtendedModel model) {
        ids.forEach(this::assertProjectAccess);
        return AdminService.super.updateAll(ids, model);
    }

    /** Single-field update-by-id: check ownership before the write (Requirement 4.5). */
    @Override
    default <V> void updateSingleField(ID id, V value, BiConsumer<DaoModel, V> setter) {
        assertProjectAccess(id);
        AdminService.super.updateSingleField(id, value, setter);
    }

    /** Delete-by-id: check ownership before the row is removed (Requirement 5.1-5.3). */
    @Override
    default void deleteById(ID id) {
        assertProjectAccess(id);
        AdminService.super.deleteById(id);
    }

    /** Multi-id delete: validate every targeted id, aborting on the first deny (Requirement 5.5). */
    @Override
    default void deleteAll(List<ID> ids) {
        ids.forEach(this::assertProjectAccess);
        AdminService.super.deleteAll(ids);
    }

    /** Soft-delete: validate every targeted id, aborting on the first deny (Requirement 5.5). */
    @Override
    default void softDelete(String fieldName, Set<ID> ids) {
        ids.forEach(this::assertProjectAccess);
        AdminService.super.softDelete(fieldName, ids);
    }

    /** Set-properties-to-null-by-id: check ownership before the write (Requirement 5.5). */
    @Override
    default void setPropertiesToNull(ID id, Set<String> propertyNames) {
        assertProjectAccess(id);
        AdminService.super.setPropertiesToNull(id, propertyNames);
    }

    // --- helpers ---

    /**
     * The {@code Access_Denied_Outcome}: a 404 {@code error.entity.not.found} carrying the entity id,
     * so an out-of-scope entity is indistinguishable from a missing one and no new message code is
     * introduced (Requirement 2.7, 2.8, 6.1).
     */
    private static ForemenApiException denied(Object entityId) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", entityId);
    }

    /** Req 6.7: an authority is ADMIN when it exactly equals {@code ROLE_ADMIN} or the bare {@code ADMIN}. */
    private static boolean isAdmin(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (("ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE).equals(a)
                    || ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** Req 6.5: parse the principal name as a numeric userId; null on blank/non-numeric. */
    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Req 7.1/7.2: single segment restricts a root property; dotted segments traverse joins. */
    private static <T> Path<Object> resolvePath(Root<T> root, String dotPath) {
        String[] segments = dotPath.split("\\.");
        if (segments.length == 1) {
            return root.get(segments[0]);
        }
        From<?, ?> from = root;
        for (int i = 0; i < segments.length - 1; i++) {
            from = from.join(segments[i]);
        }
        return from.get(segments[segments.length - 1]);
    }

    /** A Specification that is always false: an OR of zero predicates (Req 6.3, 6.6). */
    private static <T> Specification<T> matchNothing() {
        return (root, query, cb) -> cb.disjunction();
    }
}
