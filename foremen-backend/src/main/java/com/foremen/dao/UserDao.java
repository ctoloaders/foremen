package com.foremen.dao;

import com.foremen.dao.model.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDao extends AdminDao<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    /**
     * FOR-05-09 Req 13.7 — case-insensitive email lookup for the {@code Worker_Record_Flow}
     * duplicate-email guard: a supplied worker email must be unique against every existing user's
     * email under case-insensitive comparison, whatever that user's role / status / active flag.
     * Returns the first user whose email equals {@code email} ignoring case, or empty when none.
     */
    @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

    /**
     * Counts users whose role code matches the given value (exact, case-sensitive),
     * traversing the {@code role.code} association. Used by the admin bootstrap to
     * enforce the single-ADMIN invariant.
     */
    long countByRoleCode(String roleCode);

    /**
     * Returns users whose role code matches the given value (exact, case-sensitive),
     * traversing the {@code role.code} association. Used by the admin bootstrap to
     * resolve the existing ADMIN user (if any).
     */
    List<UserEntity> findByRoleCode(String roleCode);

    /**
     * FOR-05-09 Req 11 (task 10.1) — the candidate search backing
     * {@code GET /api/project-members/candidates}. Returns the page of users eligible to be added to
     * {@code projectId}, applying the Requirement 11 filters at the DB layer so the service never
     * loads non-matching rows:
     *
     * <ul>
     *   <li><b>exclude current members</b> (Req 11.1) — a {@code NOT EXISTS} over
     *       {@code project_members} drops every user who already has a membership on the project in
     *       <em>any</em> Assignment_Status;</li>
     *   <li><b>exclude Inactive_Users</b> (Req 11.1) — only {@code active = true} users, which keeps
     *       {@code INVITED}+active users and uninvited WORKER records (both {@code active = true});</li>
     *   <li><b>{@code term}</b> (Req 11.2) — when non-null, a case-insensitive substring over the
     *       user's name OR email; the caller passes the already-trimmed, lower-cased term wrapped in
     *       {@code %…%} (a {@code null} term is "not supplied");</li>
     *   <li><b>{@code roleCode}</b> (Req 11.3) — when non-null, the user's Company_Role code equals it;</li>
     *   <li><b>{@code blockRole}</b> (Req 11.11) — when non-null (a WORKERS or CLIENTS block request),
     *       the user's Company_Role code equals the single block role ({@code WORKER} / {@code CLIENT});</li>
     *   <li><b>{@code excludeWorkerClient}</b> (Req 11.11) — when {@code true} (an ADMIN_STAFF block
     *       request), the user's Company_Role code is neither {@code WORKER} nor {@code CLIENT}.</li>
     * </ul>
     *
     * <p>Results are ordered by display name ascending then by user id ascending (Req 11.7); the
     * {@link Page} carries the total number of matching candidates. Email may be {@code null} for an
     * uninvited WORKER record. The query selects only {@link UserEntity}, never a password/token.
     *
     * @param projectId           the project candidates are sought for (required)
     * @param term                lower-cased, {@code %…%}-wrapped search term, or {@code null}
     * @param roleCode            exact Company_Role code filter, or {@code null}
     * @param blockRole           {@code WORKER} / {@code CLIENT} for a single-role block, or {@code null}
     * @param excludeWorkerClient {@code true} for an ADMIN_STAFF block request, else {@code false}
     * @param pageable            page index + size (and no sort; the ORDER BY is fixed in the query)
     */
    @Query(value = "SELECT u FROM UserEntity u "
            + "WHERE u.active = true "
            + "AND NOT EXISTS (SELECT 1 FROM ProjectMemberEntity pm "
            + "                WHERE pm.projectId = :projectId AND pm.user.id = u.id) "
            + "AND (:term IS NULL OR LOWER(u.name) LIKE :term OR LOWER(u.email) LIKE :term) "
            + "AND (:roleCode IS NULL OR u.role.code = :roleCode) "
            + "AND (:blockRole IS NULL OR u.role.code = :blockRole) "
            + "AND (:excludeWorkerClient = false OR u.role.code NOT IN ('WORKER', 'CLIENT')) "
            + "ORDER BY u.name ASC, u.id ASC",
            countQuery = "SELECT COUNT(u) FROM UserEntity u "
                    + "WHERE u.active = true "
                    + "AND NOT EXISTS (SELECT 1 FROM ProjectMemberEntity pm "
                    + "                WHERE pm.projectId = :projectId AND pm.user.id = u.id) "
                    + "AND (:term IS NULL OR LOWER(u.name) LIKE :term OR LOWER(u.email) LIKE :term) "
                    + "AND (:roleCode IS NULL OR u.role.code = :roleCode) "
                    + "AND (:blockRole IS NULL OR u.role.code = :blockRole) "
                    + "AND (:excludeWorkerClient = false OR u.role.code NOT IN ('WORKER', 'CLIENT'))")
    Page<UserEntity> searchCandidates(@Param("projectId") Long projectId,
                                      @Param("term") String term,
                                      @Param("roleCode") String roleCode,
                                      @Param("blockRole") String blockRole,
                                      @Param("excludeWorkerClient") boolean excludeWorkerClient,
                                      Pageable pageable);
}
