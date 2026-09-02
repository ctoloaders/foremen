package com.foremen.service;

import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * Mixin giving a project-scoped service automatic project filtering. A concrete service
 * implements {@link ReadOnlyAdminService} (for reads) and this interface, and overrides ONLY
 * {@link #getProjectIdPath()}. All decision logic lives in the single default
 * {@link #addRequiredQuery()} (Requirements 6.1-6.10, 7.1-7.3) and is not meant to be overridden.
 *
 * @param <DaoModel> the JPA entity type the concrete service filters
 */
public interface ProjectScopedService<DaoModel> {

    /**
     * The sole per-entity override point: JPA dot-path from the query root to the project id
     * (Requirement 6.9). No default is provided so every concrete project-scoped service MUST
     * declare its path explicitly.
     */
    String getProjectIdPath();

    /**
     * Supplies the caller's allowed project ids for a resolved user id. A concrete service wires
     * this to the {@code ProjectAccessCache} (e.g. {@code projectAccessCache.get(userId)}), keeping
     * this interface free of a hard dependency on the cache bean.
     */
    Set<Long> allowedProjectIds(Long userId);

    /**
     * All shared decision logic; NOT intended to be overridden by concrete services (Requirement 6.8).
     *
     * <ul>
     *     <li>Req 6.6: no authenticated caller &rarr; match-nothing.</li>
     *     <li>Req 6.1, 6.7: exact-authority ADMIN check &rarr; {@code null} (no filter, ADMIN bypass).</li>
     *     <li>Req 6.5: non-numeric/absent principal name &rarr; match-nothing.</li>
     *     <li>Req 6.3: empty allowed set &rarr; match-nothing.</li>
     *     <li>Req 6.2, 7.1-7.3: non-empty allowed set &rarr; restrict the resolved project-id path to the set.</li>
     * </ul>
     */
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

    // --- helpers ---

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
