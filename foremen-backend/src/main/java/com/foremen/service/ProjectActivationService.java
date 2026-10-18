package com.foremen.service;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.ProjectNotReadyException;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.readiness.AreaContribution;
import com.foremen.service.readiness.ProjectReadinessResult;
import com.foremen.service.readiness.ProjectReadinessService;
import com.foremen.service.readiness.ReadinessState;

/**
 * FOR-05-12 (Requirements 1, 2, 3; design §Backend design → New components, §Error handling) — the
 * single server-side gate behind the explicit, readiness-gated project activation
 * ({@code pre-activation → ACTIVE}). Both activation entry points (the dedicated
 * {@code POST /api/projects/{id}/activate} endpoint, task 2.2, and the generic {@code PUT} when it
 * sets {@code status = ACTIVE} from a pre-activation status, task 2.3) converge on
 * {@link #activate(Long, boolean)} so the rule lives in exactly one place.
 *
 * <p>The gate runs, in order (design Decision D1/D2/D5):
 * <ol>
 *   <li><b>Access gate (404)</b> — resolve the project with the ADMIN-bypass Accessible_Project rule,
 *       byte-identical to {@code ProjectReadinessService}/{@code ProjectScheduleService} (missing and
 *       out-of-scope are indistinguishable, {@code 404 error.entity.not.found}); Requirement 1.4.</li>
 *   <li><b>Permission gate (403)</b> — the caller must hold {@code PROJECTS} UPDATE (ADMIN bypassed by
 *       {@link ForemenPermissionEvaluator}), mirroring the {@code PermissionInterceptor} gate the
 *       project update path already applies; otherwise {@code 403 error.access.denied}
 *       (Requirement 1.3, 3.3).</li>
 *   <li><b>Pre-activation check</b> — an already-{@code ACTIVE} project is an idempotent success (the
 *       unchanged project is returned, no readiness computed, Requirement 1.2); a terminal/later
 *       status (e.g. {@code COMPLETED}/{@code CANCELLED}/{@code ON_HOLD}) is rejected with
 *       {@code 409 error.project.status.illegalTransition} (Requirement 1.2).</li>
 *   <li><b>Readiness gate</b> — compute the project-wide readiness over <b>all</b> areas
 *       ({@link ProjectReadinessService#getReadinessAllAreas(Long)}, Decision D2); fully ready iff
 *       {@code overallPercentage == 100} (Requirement 2.4).</li>
 *   <li>if not fully ready and <b>not</b> ({@code force} &amp;&amp; the caller is ADMIN/MANAGER) →
 *       throw {@link ProjectNotReadyException} carrying the not-ready areas (state != {@code DONE}),
 *       leaving the status unchanged (Requirements 2.1, 3.1, 3.2).</li>
 *   <li>otherwise set {@code status = ACTIVE}, save, and return the mapped model (Requirements 1.1,
 *       2.2, 3.4).</li>
 * </ol>
 *
 * <p>Force is role-gated and readiness-only (Decision D5): it is honored solely for ADMIN/MANAGER and
 * bypasses solely the readiness gate — the access (404) and UPDATE (403) gates always apply, and a
 * non-ADMIN/MANAGER {@code force} is ignored (treated as a normal gated activation, Requirement 3.2).
 */
@Service
@Transactional
public class ProjectActivationService {

    /** 404 for a missing <em>or</em> a non-accessible project — byte-identical to the readiness/schedule gate. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 403 when the caller lacks {@code PROJECTS} UPDATE (or the role is unresolved). */
    static final String ACCESS_DENIED_MESSAGE = "error.access.denied";

    /** 409 when activation is requested from a non-pre-activation, non-ACTIVE (terminal/later) status. */
    static final String ILLEGAL_TRANSITION_MESSAGE = "error.project.status.illegalTransition";

    /** The ABAC resource/operation the activation permission gate requires (mirrors the project update gate). */
    static final String PROJECTS_RESOURCE = "PROJECTS";
    static final String UPDATE_OPERATION = "UPDATE";

    /** The role code (besides ADMIN) allowed to force a not-fully-ready activation (Requirement 3.1). */
    static final String MANAGER_ROLE_CODE = "MANAGER";

    /** The {@code ROLE_<code>} authority prefix used to read the caller's role code. */
    private static final String ROLE_PREFIX = "ROLE_";

    private final ProjectDao projectDao;
    private final ProjectServiceMapper projectServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final ForemenPermissionEvaluator permissionEvaluator;
    private final ProjectReadinessService projectReadinessService;

    public ProjectActivationService(ProjectDao projectDao,
                                    ProjectServiceMapper projectServiceMapper,
                                    ProjectAccessCache projectAccessCache,
                                    ForemenPermissionEvaluator permissionEvaluator,
                                    ProjectReadinessService projectReadinessService) {
        this.projectDao = projectDao;
        this.projectServiceMapper = projectServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.permissionEvaluator = permissionEvaluator;
        this.projectReadinessService = projectReadinessService;
    }

    /**
     * Activates a project ({@code pre-activation → ACTIVE}) through the single readiness gate; see the
     * class javadoc for the ordered rule. Returns the mapped project model (updated for a fresh
     * activation, unchanged for an idempotent already-{@code ACTIVE} call).
     *
     * @param projectId the project to activate
     * @param force     when {@code true} <em>and</em> the caller is ADMIN/MANAGER, bypass the
     *                  readiness gate (and only the readiness gate); ignored for any other caller
     * @return the mapped {@link ProjectServiceExtendedModel} of the (now or already) {@code ACTIVE} project
     * @throws ForemenApiException      404 {@code error.entity.not.found} (missing/out-of-scope),
     *                                  403 {@code error.access.denied} (no {@code PROJECTS} UPDATE), or
     *                                  409 {@code error.project.status.illegalTransition} (terminal status)
     * @throws ProjectNotReadyException 409 when readiness &lt; 100% and no valid force (carries the
     *                                  not-ready areas)
     */
    public ProjectServiceExtendedModel activate(Long projectId, boolean force) {
        // 1 — Access gate (404): resolve with the ADMIN-bypass Accessible_Project rule.
        ProjectEntity project = loadAccessibleProject(projectId);

        // 2 — Permission gate (403): the caller must hold PROJECTS UPDATE (ADMIN bypassed by the evaluator).
        assertUpdatePermission();

        // 3 — Pre-activation check: already ACTIVE is idempotent; a terminal/later status is rejected.
        ProjectStatus status = project.getStatus();
        if (status == ProjectStatus.ACTIVE) {
            return projectServiceMapper.toServiceExtendedModel(project); // idempotent success, no readiness computed
        }
        if (!status.isPreActivation()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ILLEGAL_TRANSITION_MESSAGE);
        }

        // 4 + 5 — Readiness gate: compute project-wide readiness over ALL areas (Decision D2) and
        // block when not fully ready unless a valid (ADMIN/MANAGER) force overrides the readiness gate.
        if (!(force && callerIsAdminOrManager())) {
            assertProjectReady(projectId);
        }

        // 6 — Activate: set ACTIVE, persist, and return the mapped model.
        project.setStatus(ProjectStatus.ACTIVE);
        ProjectEntity saved = projectDao.save(project);
        return projectServiceMapper.toServiceExtendedModel(saved);
    }

    /**
     * The shared, non-force readiness gate (steps 4–5 of {@link #activate(Long, boolean)}): computes
     * the project-wide readiness over <b>all</b> areas ({@link ProjectReadinessService#getReadinessAllAreas(Long)},
     * Decision D2) and, when the project is not fully ready ({@code overallPercentage != 100}, Requirement
     * 2.4), throws {@link ProjectNotReadyException} carrying the not-ready areas (state != {@code DONE}),
     * leaving the status unchanged (Requirement 2.1). A fully-ready project is a silent no-op.
     *
     * <p>Extracted so both activation paths share one implementation of the readiness check (FOR-05-12
     * Requirement 7.2): the dedicated endpoint's {@link #activate(Long, boolean)} guards it behind the
     * ADMIN/MANAGER force bypass, and the generic {@code PUT /api/projects/{id}} path
     * ({@code ProjectService.validateUpdate}, task 2.3) calls it directly — the generic PUT carries no
     * force, so it is always the gated (non-force) outcome and yields the same 409 not-ready body. This
     * method performs <b>only</b> the readiness check and throw; it does not re-run the access/UPDATE
     * gate (the generic update path applies its own) and does not touch the status (the generic update
     * applies the status write itself).
     *
     * @param projectId the project whose project-wide readiness is asserted
     * @throws ProjectNotReadyException 409 when readiness &lt; 100% (carries the not-ready areas)
     */
    void assertProjectReady(Long projectId) {
        ProjectReadinessResult readiness = projectReadinessService.getReadinessAllAreas(projectId);
        if (readiness.overallPercentage() != 100) {
            throw new ProjectNotReadyException(notReadyAreas(readiness));
        }
    }

    /**
     * The not-ready breakdown: the areas whose state is not {@code DONE} (Requirement 4.2/4.3). Fully
     * ready ({@code DONE}) areas are excluded so the 409 body lists only outstanding areas.
     */
    private static List<AreaContribution> notReadyAreas(ProjectReadinessResult readiness) {
        return readiness.areas().stream()
                .filter(area -> area.state() != ReadinessState.DONE)
                .toList();
    }

    /**
     * Loads the project for {@code projectId}, asserting the caller may access it, byte-identically to
     * {@code ProjectReadinessService}/{@code ProjectScheduleService}: an ADMIN authority bypasses the
     * Accessible_Project gate; a non-ADMIN caller must have {@code projectId} in its allowed project
     * ids. Both a non-existent and an out-of-scope project are rejected with {@code 404
     * error.entity.not.found} carrying an identical body (Requirement 1.4), with the accessibility
     * gate running before the existence lookup for non-ADMIN callers so a non-member cannot probe
     * project existence.
     *
     * @param projectId the project being activated
     * @return the managed project entity (never {@code null})
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    private ProjectEntity loadAccessibleProject(Long projectId) {
        if (!isCallerAdmin()) {
            Long userId = callerUserId();
            Set<Long> allowed = userId == null ? null : projectAccessCache.get(userId);
            if (projectId == null || allowed == null || !allowed.contains(projectId)) {
                throw notFound(projectId);
            }
        }
        return projectDao.findById(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    /**
     * Asserts the authenticated caller holds {@code PROJECTS} UPDATE (ADMIN bypassed by the
     * evaluator), throwing {@code 403 error.access.denied} otherwise. This mirrors the gate the
     * {@code PermissionInterceptor} applies to the project update path, re-applied here because the
     * dedicated endpoint and the generic-update delegation both funnel through this service (the
     * controller's own {@code @RequiresPermission(PROJECTS, UPDATE)} is the first line; this is the
     * authoritative check for every path, mirroring the {@code WorkerInvitationService} /
     * {@code WorkerRecordService} programmatic pattern). An unresolved role is treated as a denial.
     *
     * @throws ForemenApiException 403 {@code error.access.denied} when the caller lacks the grant
     */
    private void assertUpdatePermission() {
        String roleCode = callerRoleCode();
        if (roleCode == null
                || !permissionEvaluator.isAllowed(roleCode, PROJECTS_RESOURCE, UPDATE_OPERATION)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED_MESSAGE);
        }
    }

    /**
     * Whether the caller may force a not-fully-ready activation (Requirement 3.1): ADMIN (via the
     * {@link #isCallerAdmin()} bypass) or an explicit {@code MANAGER} role code, reading the role from
     * the {@code ROLE_<code>} authority like {@code ProjectReadinessService}.
     */
    private boolean callerIsAdminOrManager() {
        return isCallerAdmin() || MANAGER_ROLE_CODE.equals(callerRoleCode());
    }

    /**
     * The {@code Access_Denied_Outcome}: a 404 {@code error.entity.not.found} carrying the project id,
     * byte-identical for a missing and a non-accessible project (Requirement 1.4).
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
