package com.foremen.service.readiness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * The thin aggregator behind the {@code Readiness_API} (FOR-05-11 Requirements 3, 4, 6, 7;
 * design §ProjectReadinessService, Decision D1). It discovers every
 * {@link ProjectReadinessContributor} bean, calls each one the caller is permitted to READ, orders
 * the results by {@link ProjectReadinessContributor#order()}, and composes them — via the pure
 * {@link ReadinessMath} — into a single {@link ProjectReadinessResult}.
 *
 * <p>The aggregator never re-derives an area's completion from raw entities; each contributor owns
 * its own completion logic and percentage formula (Decision D1). The service only resolves access,
 * scopes by per-area READ, orders, and averages.
 *
 * <p><b>Access gate.</b> {@link #getReadiness(Long)} resolves the project with the same ADMIN-bypass
 * project-access rule the two existing readiness services use, throwing a byte-identical
 * {@code 404 error.entity.not.found} (carrying the project id) for a missing <em>or</em>
 * inaccessible project so the two outcomes are indistinguishable (Requirements 3.2, 3.3), exactly
 * mirroring {@code ProjectScheduleService.loadAccessibleProject}.
 *
 * <p><b>Permission scoping.</b> A contributor is included iff the caller holds READ on its
 * {@link ProjectReadinessContributor#areaResource()} (ADMIN bypass), evaluated through
 * {@link ForemenPermissionEvaluator#isAllowed(String, String, String)}; a non-readable area is
 * omitted from both the response and the average, and nothing about it is leaked (Requirement 6).
 *
 * <p><b>Robustness.</b> A single contributor that throws is contained: the service logs and omits
 * that area's contribution rather than failing the whole read (design §Error handling), treating the
 * omission like a non-readable area for composition.
 *
 * <p>{@code @Transactional(readOnly = true)} — the read performs no write and no audit
 * (Requirement 11.2, Decision D9).
 */
@Service
public class ProjectReadinessService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectReadinessService.class);

    /**
     * 404 for a missing <em>or</em> a non-accessible project. The same message code and body are
     * used for both outcomes so an out-of-scope project is indistinguishable from a non-existent one
     * (Requirements 3.2, 3.3), byte-identical to {@code ProjectScheduleService}.
     */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** The READ operation checked per area through the permission evaluator (Requirement 6.1). */
    static final String READ_OPERATION = "READ";

    /** The {@code ROLE_<code>} authority prefix used to read the caller's role code. */
    private static final String ROLE_PREFIX = "ROLE_";

    private final List<ProjectReadinessContributor> contributors;
    private final ForemenPermissionEvaluator permissionEvaluator;
    private final ProjectAccessCache projectAccessCache;
    private final ProjectDao projectDao;

    public ProjectReadinessService(List<ProjectReadinessContributor> contributors,
                                   ForemenPermissionEvaluator permissionEvaluator,
                                   ProjectAccessCache projectAccessCache,
                                   ProjectDao projectDao) {
        this.contributors = contributors;
        this.permissionEvaluator = permissionEvaluator;
        this.projectAccessCache = projectAccessCache;
        this.projectDao = projectDao;
    }

    /**
     * Computes the aggregated readiness of a project for the authenticated caller (Requirement 4).
     *
     * <p>Algorithm (design §ProjectReadinessService):
     * <ol>
     *   <li><b>Access gate</b> — resolve the project with the ADMIN-bypass rule; a missing or
     *       inaccessible project throws {@code 404 error.entity.not.found} (Requirements 3.2, 3.3).</li>
     *   <li><b>Readable areas</b> — for each contributor, include it iff the caller holds
     *       {@link ProjectReadinessContributor#areaResource()} READ (Requirement 6.1).</li>
     *   <li><b>Contribute</b> — call {@link ProjectReadinessContributor#contribute(Long)} on each
     *       included contributor; a contributor that throws is logged and omitted (design §Error
     *       handling).</li>
     *   <li><b>Order</b> — sort the contributions by {@link ProjectReadinessContributor#order()}
     *       (Requirement 7.1, 7.4), independent of Spring discovery order.</li>
     *   <li><b>Compose</b> — overall percentage/state via {@link ReadinessMath}
     *       (Requirements 4.3, 4.4).</li>
     * </ol>
     *
     * @param projectId the project whose readiness is computed
     * @return the aggregated readiness result (never {@code null})
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    @Transactional(readOnly = true)
    public ProjectReadinessResult getReadiness(Long projectId) {
        loadAccessibleProject(projectId);                             // 1 — 404

        String roleCode = callerRoleCode();

        // 2 + 3 + 4 — include each Readable_Area, contribute (contain a thrower), order by order().
        List<ProjectReadinessContributor> included = contributors.stream()
                .filter(contributor -> isReadable(roleCode, contributor))
                .sorted(Comparator.comparingInt(ProjectReadinessContributor::order))
                .toList();

        List<AreaContribution> areas = new ArrayList<>(included.size());
        for (ProjectReadinessContributor contributor : included) {
            try {
                areas.add(contributor.contribute(projectId));
            } catch (RuntimeException e) {
                // A single contributor's glitch never blocks the headline: log and omit the area,
                // treated like a non-readable area for composition (design §Error handling).
                LOG.warn("Readiness contributor '{}' failed for project {}; omitting its area",
                        contributor.areaKey(), projectId, e);
            }
        }

        // 5 — compose the equal-weight overall percentage and the state roll-up.
        int overallPercentage = ReadinessMath.overallPercentage(areas);
        ReadinessState overallState = ReadinessMath.overallState(areas);
        return new ProjectReadinessResult(projectId, overallPercentage, overallState, List.copyOf(areas));
    }

    /**
     * Computes the <b>project-wide</b> readiness of a project over <b>every</b>
     * {@link ProjectReadinessContributor}, independent of the caller's per-area READ permissions
     * (FOR-05-12 Requirement 2.3, Decision D2). This is the authoritative activation gate's view of
     * "100% ready": unlike {@link #getReadiness(Long)}, which scopes areas to the caller's READ
     * permission and therefore yields a per-caller percentage, this variant includes all areas so
     * the percentage is a project fact.
     *
     * <p>Everything else is identical to {@link #getReadiness(Long)}: the same ADMIN-bypass access
     * gate (missing or inaccessible project → {@code 404 error.entity.not.found}, Requirements 3.2,
     * 3.3), the same per-contributor containment (a thrower is logged and omitted), the same
     * {@link ProjectReadinessContributor#order()} ordering, and the same {@link ReadinessMath}
     * composition. Only the per-area READ filter is skipped.
     *
     * <p>Intended for internal use by the activation gate only; it is not exposed through the
     * caller-scoped {@code Readiness_API}.
     *
     * @param projectId the project whose project-wide readiness is computed
     * @return the aggregated readiness result over all areas (never {@code null})
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    @Transactional(readOnly = true)
    public ProjectReadinessResult getReadinessAllAreas(Long projectId) {
        loadAccessibleProject(projectId);                             // 1 — 404 (unchanged access gate)

        // 2 + 3 + 4 — include EVERY contributor (no per-area READ filter), contribute (contain a
        // thrower), order by order(). This is the only difference from getReadiness (Decision D2).
        List<ProjectReadinessContributor> included = contributors.stream()
                .sorted(Comparator.comparingInt(ProjectReadinessContributor::order))
                .toList();

        List<AreaContribution> areas = new ArrayList<>(included.size());
        for (ProjectReadinessContributor contributor : included) {
            try {
                areas.add(contributor.contribute(projectId));
            } catch (RuntimeException e) {
                LOG.warn("Readiness contributor '{}' failed for project {}; omitting its area",
                        contributor.areaKey(), projectId, e);
            }
        }

        // 5 — compose the equal-weight overall percentage and the state roll-up.
        int overallPercentage = ReadinessMath.overallPercentage(areas);
        ReadinessState overallState = ReadinessMath.overallState(areas);
        return new ProjectReadinessResult(projectId, overallPercentage, overallState, List.copyOf(areas));
    }

    /**
     * Whether the caller may READ the contributor's area resource (Requirement 6.1). ADMIN is
     * bypassed by {@link ForemenPermissionEvaluator}; an unresolved role is treated as not readable
     * (the area is omitted).
     */
    private boolean isReadable(String roleCode, ProjectReadinessContributor contributor) {
        return roleCode != null
                && permissionEvaluator.isAllowed(roleCode, contributor.areaResource(), READ_OPERATION);
    }

    /**
     * Loads the project for {@code projectId}, asserting the caller may access it, mirroring
     * {@code ProjectScheduleService.loadAccessibleProject} byte-identically: an ADMIN authority
     * bypasses the Accessible_Project gate; a non-ADMIN caller must have {@code projectId} in its
     * allowed project ids. Both a non-existent and an out-of-scope project are rejected with
     * {@code 404 error.entity.not.found} carrying an identical body (Requirements 3.2, 3.3), with the
     * accessibility gate running before the existence lookup for non-ADMIN callers so a non-member
     * cannot probe project existence.
     *
     * @param projectId the project being read
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    private void loadAccessibleProject(Long projectId) {
        if (!isCallerAdmin()) {
            Long userId = callerUserId();
            Set<Long> allowed = userId == null ? null : projectAccessCache.get(userId);
            if (projectId == null || allowed == null || !allowed.contains(projectId)) {
                throw notFound(projectId);
            }
        }
        projectDao.findById(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    /**
     * The {@code Access_Denied_Outcome}: a 404 {@code error.entity.not.found} carrying the project
     * id, byte-identical for a missing and a non-accessible project (Requirements 3.2, 3.3).
     */
    private static ForemenApiException notFound(Long projectId) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, projectId);
    }

    /** The caller's role code from the {@code ROLE_<code>} authority, or {@code null} if unresolved. */
    private static String callerRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }

    /** True iff the authenticated caller carries the exact ADMIN authority ({@code ROLE_ADMIN}/{@code ADMIN}). */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** The authenticated caller's numeric user id, or {@code null} when it cannot be resolved. */
    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
