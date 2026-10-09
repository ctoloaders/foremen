package com.foremen.service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerKind;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.controller.model.Candidate;
import com.foremen.controller.model.TeamMemberView;
import com.foremen.controller.model.TeamReadiness;
import com.foremen.controller.model.WorkerTypeAssignment;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.model.ProjectMemberServiceExtendedModel;
import com.foremen.service.model.ProjectMemberServiceModel;
import com.foremen.service.model.mapper.ProjectMemberServiceMapper;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.team.TeamBlock;
import com.foremen.service.team.ReadinessState;
import com.foremen.service.team.TeamComposition;
import com.foremen.service.team.TeamMemberOrdering;
import com.foremen.service.team.TeamRejectionChecklist;
import com.foremen.service.team.TeamRejectionChecklist.TeamRejectionContext;
import com.foremen.util.TagNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages project memberships: assign, remove, and list operations over
 * {@link ProjectMemberEntity} rows. Every mutation invalidates the affected user's
 * {@link ProjectAccessCache} entry so revoked or granted project access is honored promptly
 * (Requirements 3.1-3.6, 8.1, 8.2).
 *
 * <p><b>FOR-05-09 (task 5.1) — project-scoped CRUD.</b> Following the FOR-03-04a single-contract
 * shape, this service implements exactly one CRUD contract — {@link ProjectScopedService} — and
 * never a plain {@link AdminService} and never both. Because {@link ProjectScopedService} extends
 * {@link AdminService}, it supplies the standard CRUD plumbing ({@link #getDao()},
 * {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override {@link #getProjectIdPath()},
 * and wires {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>Project boundary.</b> {@code project_members.project_id} is a direct {@code @Column} on
 * {@link ProjectMemberEntity} (the {@code projects} table arrives in a later spec), so
 * {@link #getProjectIdPath()} returns the single segment {@code "projectId"} (entity-creation-rules
 * step 4). The inherited list filter therefore restricts non-ADMIN LIST reads to memberships of
 * projects the caller belongs to, and every by-id/by-pair CRUD operation asserts that membership;
 * ADMIN bypasses both, and a non-accessible project is reported with 404
 * {@code error.entity.not.found} byte-identical to a non-existent one (Requirements 3.1, 3.2, 3.3,
 * 3.5, 3.8). This service overrides no CRUD method, {@code addRequiredQuery()}, or
 * {@code getProjectId()}.
 *
 * <p><b>Internal-attribute masking (D12, task 7.6).</b> The Team_Member_View Internal_Attributes
 * ({@code workerTypeId}/{@code workerTypeCode}/{@code workerTypeName}/{@code workerTypeActive},
 * {@code nip}, {@code workerTypeMissing}, {@code tags}) are visible only to an admin-staff reader
 * (ADMIN + MANAGER/FOREMAN/ESTIMATOR/FINANCIER) and omitted for WORKER/CLIENT. {@link #getAdminOnlyFields()}
 * declares the full set so the inherited reflection masking nulls whichever exist on the flat read
 * model, and the inherited gate is widened from ADMIN-only to {@code isCallerAdminStaff()}; the
 * enriched immutable {@link TeamMemberView} is masked directly in {@link #listMemberViews} /
 * {@link #toView} by the same admin-staff predicate. {@code assignmentStatus} is never masked.
 */
@Service
@Transactional
@Slf4j
public class ProjectMemberService
        implements ProjectScopedService<ProjectMemberServiceModel, ProjectMemberServiceExtendedModel, ProjectMemberEntity, Long> {

    private final ProjectMemberDao projectMemberDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final ProjectDao projectDao;
    private final WorkerTypeDao workerTypeDao;
    private final ProjectAccessCache projectAccessCache;
    private final ProjectMemberServiceMapper projectMemberServiceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final NotificationService notificationService;

    public ProjectMemberService(ProjectMemberDao projectMemberDao,
                                UserDao userDao,
                                RoleDao roleDao,
                                ProjectDao projectDao,
                                WorkerTypeDao workerTypeDao,
                                ProjectAccessCache projectAccessCache,
                                ProjectMemberServiceMapper projectMemberServiceMapper,
                                AuditLogDao auditLogDao,
                                EntityManager entityManager,
                                NotificationService notificationService) {
        this.projectMemberDao = projectMemberDao;
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.projectDao = projectDao;
        this.workerTypeDao = workerTypeDao;
        this.projectAccessCache = projectAccessCache;
        this.projectMemberServiceMapper = projectMemberServiceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.notificationService = notificationService;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<ProjectMemberEntity, Long> getDao() {
        return projectMemberDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ProjectMemberEntity, ProjectMemberServiceModel, ProjectMemberServiceExtendedModel> getMapper() {
        return projectMemberServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ProjectMemberEntity> getDaoModelClass() {
        return ProjectMemberEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. A membership stores its project boundary in the
     * direct {@code project_members.project_id} column, so the project-id path is the single
     * segment {@code "projectId"} (entity-creation-rules step 4; Requirements 3.1-3.3).
     */
    @Override
    public String getProjectIdPath() {
        return "projectId";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    /**
     * The Team_Member_View Internal_Attributes (D12, Requirement 4.10/4.11): worker type
     * ({@code workerTypeId}/{@code workerTypeCode}/{@code workerTypeName}/{@code workerTypeActive}),
     * {@code nip}, {@code workerTypeMissing}, and {@code tags}. The inherited masking mechanism
     * (gated by {@code isCallerAdminStaff()} — ADMIN + MANAGER/FOREMAN/ESTIMATOR/FINANCIER, false for
     * WORKER/CLIENT) nulls whichever of these fields exist on the masked model so {@code @JsonInclude
     * (NON_NULL)} omits them for a non-admin-staff reader; {@code assignmentStatus} is intentionally
     * not internal and is never masked.
     *
     * <p>This set drives the generic reflection masking of the flat read-path
     * {@link ProjectMemberServiceModel} (which carries only {@code workerTypeId} and {@code tags};
     * the other names are skipped gracefully as absent fields). The enriched {@link TeamMemberView},
     * an immutable record, is masked directly in {@link #toView} via {@link #isCallerAdminStaff()}
     * rather than by reflection.
     */
    @Override
    public Set<String> getAdminOnlyFields() {
        return Set.of(
                "workerTypeId", "workerTypeCode", "workerTypeName", "workerTypeActive",
                "nip", "workerTypeMissing", "tags");
    }

    // --- Canonical rejection order (FOR-05-09 task 6.1, Requirements 3.7, 10.4) ---
    //
    // FOR-05-09 (task 6.1) — the single ordered checklist every mutating Team_API flow funnels
    // through (TeamRejectionChecklist) so the FIRST failing check in the canonical order determines
    // the response and nothing later runs (design §"Canonical rejection order"; Requirement 3
    // criterion 7, Requirement 10 criterion 4, Property 11). The checklist leaves every
    // project_members row unchanged on any failure, because each flow mutates only after
    // TeamRejectionChecklist.run(...) returns normally.
    //
    // Steps 1–2 (401 token, 403 PROJECT_MEMBERS permission) are enforced upstream by the security
    // filter and PermissionInterceptor before the controller body, so this service owns steps 3–9.
    // The builders below wire the steps whose checks already have helpers in this task:
    //   • mandatory fields (role.mismatch + tag.invalid — see mandatoryFieldsCheck; task 7.1 fills
    //     the full semantics);
    //   • project existence / access (the ProjectScopedService contract — see projectAccessCheck);
    //   • member existence / duplicate (assertNotDuplicate / assertMemberExists);
    //   • user existence (resolveUser).
    // The LIFECYCLE_LOCK, TEAM_COMPOSITION, and LAST_ACTIVE_GUARD steps are left as clearly-marked,
    // named slots (lifecycleLockCheck, teamCompositionCheck, lastActiveGuardCheck) that task 6.3
    // fills with the composition checks (role assignability, inactive user, lifecycle lock,
    // last-ACTIVE guards). An unfilled slot is a no-op, so the ordering is correct and compilable now
    // and each check slots in at its named position later without reordering the rest.

    /**
     * Step 5 — lifecycle lock (FOR-05-09 task 6.3, Requirement 10 criteria 1–5). Returns the check
     * that rejects a <em>mutating</em> op on a Locked_Status project with 409
     * {@code error.project.team.locked}; it leaves every {@code project_members} row unchanged on
     * rejection, because the check throws before any mutation (Requirement 10 criterion 2).
     *
     * <p><b>Applies to ADMIN too.</b> Unlike the step-4 {@link #projectAccessCheck} (which has an
     * ADMIN bypass of the Accessible_Project gate), the lifecycle lock is enforced for every caller,
     * ADMIN included (Requirement 10 criterion 2) — so this check reads the project status directly
     * and never consults {@code isCallerAdmin()}.
     *
     * <p><b>Locked_Status source.</b> The project status comes from the live {@code projects} table
     * via {@link ProjectDao}: a project in {@link ProjectStatus#COMPLETED} or
     * {@link ProjectStatus#CANCELLED} is Locked_Status (Glossary); every other status is an
     * Editable_Status in which the mutation is allowed (Requirement 10 criterion 1). The project's
     * existence / accessibility is the earlier step-4 concern — a {@code projectId} that resolves to
     * no project row here is treated as not locked, so step 4 (or a later step) owns that outcome and
     * this step never masks a 404 with a lock error (canonical order, Requirement 3 criterion 7).
     *
     * <p>Slots into {@link TeamRejectionChecklist.Step#LIFECYCLE_LOCK}.
     */
    private TeamRejectionChecklist.TeamCheck lifecycleLockCheck(Long projectId) {
        return () -> {
            if (projectId == null) {
                return; // step 4 owns the missing/invalid project id; nothing to lock-check here.
            }
            ProjectEntity project = projectDao.findById(projectId).orElse(null);
            if (project != null && isLockedStatus(project.getStatus())) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.project.team.locked", projectId);
            }
        };
    }

    /** A Locked_Status is {@code COMPLETED} or {@code CANCELLED} (Glossary); every other status is editable. */
    private static boolean isLockedStatus(ProjectStatus status) {
        return status == ProjectStatus.COMPLETED || status == ProjectStatus.CANCELLED;
    }

    /**
     * TASK 7.1 — step 3, mandatory fields. Returns the check that rejects a missing / invalid
     * mandatory field with 400, in two parts and in this order:
     *
     * <ol>
     *   <li><b>{@code error.project.member.tag.invalid}</b> — the submitted tag list violates
     *       {@link TagNormalizer#normalize(List)} (Requirement 15 criterion 3). This is a pure
     *       field-shape check that needs no lookup, so it runs first and the normalized result is
     *       stashed in {@code tagHolder} for the persist path (no re-normalization).</li>
     *   <li><b>{@code error.project.member.role.mismatch}</b> — a <em>non-null</em>
     *       {@code projectRoleId} that resolves to a role code different from the assigned user's
     *       current Company_Role code (D2, Requirement 5 criterion 11, Property 2). A null
     *       {@code projectRoleId} is always allowed (the role is derived server-side regardless). The
     *       comparison needs the user's Company_Role, so the user is resolved here (into
     *       {@code userHolder}) and reused by the later user-existence (step 7) and team-composition
     *       (step 8) checks; a non-existent user is <em>not</em> reported here (step 7 owns the 404)
     *       so a mismatching role never masks a missing user.</li>
     * </ol>
     *
     * <p>Slots into {@link TeamRejectionChecklist.Step#MANDATORY_FIELDS}; the canonical order places
     * it before project access (step 4), lifecycle lock (step 5), and the duplicate check (step 6),
     * so a mismatching supplied role is reported before a would-be duplicate (design §"Canonical
     * rejection order", Property 11).
     */
    private TeamRejectionChecklist.TeamCheck mandatoryFieldsCheck(Long userId,
                                                                  Long projectRoleId,
                                                                  List<String> submittedTags,
                                                                  UserHolder userHolder,
                                                                  TagHolder tagHolder) {
        return () -> {
            // 3a — tag shape: normalize (null = no tags) and reject an invalid list with 400
            //      error.project.member.tag.invalid, before any lookup (Req 15.2, 15.3).
            tagHolder.tags = TagNormalizer.normalize(submittedTags);

            // 3b — role.mismatch: only when a projectRoleId is supplied (D2, Req 5.11). A null
            //      projectRoleId means "derive the role from the Company_Role", so nothing to check.
            if (projectRoleId == null) {
                return;
            }
            resolveUserInto(userId, userHolder);
            if (userHolder.user == null) {
                return; // step 7 owns the 404 for a non-existent user; do not mask it here.
            }
            RoleEntity companyRole = userHolder.user.getRole();
            String companyRoleCode = companyRole == null ? null : companyRole.getCode();
            RoleEntity submittedRole = roleDao.findById(projectRoleId).orElse(null);
            String submittedCode = submittedRole == null ? null : submittedRole.getCode();
            if (!equalRoleCode(companyRoleCode, submittedCode)) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.role.mismatch", projectRoleId);
            }
        };
    }

    /** True iff the two role codes are the same non-null value (a null on either side is a mismatch). */
    private static boolean equalRoleCode(String a, String b) {
        return a != null && a.equals(b);
    }

    /**
     * TASK 6.3 SLOT — step 8, team-composition sub-order. Returns the check that enforces, in order,
     * {@code role.not.allowed.at.creation} (creation only) &rarr; {@code role.not.assignable} &rarr;
     * {@code user.inactive} &rarr; {@code worker.type.not.allowed} &rarr; {@code worker.type.invalid}
     * (Requirement 6 criteria 1/3, Requirement 14), applying to ADMIN and non-ADMIN alike. The user
     * is resolved by the step-7 user-existence check, so the slot reads it from {@code userHolder}
     * at run time (never at build time, when it is still {@code null}). Slots into
     * {@link TeamRejectionChecklist.Step#TEAM_COMPOSITION}.
     *
     * <p><b>Scope.</b> This fills the composition criteria whose decision is pure and
     * self-contained: {@code role.not.assignable} (Requirement 6 criterion 1) then {@code user.inactive}
     * (Requirement 6 criterion 3), with the lowest-numbered criterion winning when both hold
     * (Requirement 6 criterion 6, encoded by
     * {@link TeamComposition#firstTeamCompositionViolation(String, boolean)}). Task 7.1 appends the
     * assign-side worker-type sub-steps <em>after</em> criteria 1 and 3 without reordering them:
     * {@code worker.type.not.allowed} (a worker type supplied for a non-WORKER Company_Role) then
     * {@code worker.type.invalid} (a worker type that is missing or inactive), matching the canonical
     * step-8 sub-order (Requirement 14 criteria 2–5). The resolved Active_Worker_Type is stashed in
     * {@code workerTypeHolder} so the persist path reuses it without a second lookup.
     *
     * <p><b>Resolved role.</b> The checked role is the user's Company_Role ({@code user.getRole()},
     * D2), <em>not</em> the supplied {@code projectRoleId}: Requirement 6 criterion 1 rejects a user
     * whose <em>global</em> role cannot be a project role (e.g. an ADMIN user). A supplied
     * {@code projectRoleId} that disagrees with the Company_Role is a separate {@code role.mismatch}
     * concern handled at the mandatory-fields step, so {@code projectRoleId} is not read here.
     *
     * <p><b>Applies to ADMIN too.</b> These checks run for every caller, ADMIN included — the ABAC
     * bypass does not skip them (Requirement 6 criterion 4). The checklist runs this step for ADMIN
     * callers as well, so no caller-role gate is applied inside the check.
     */
    private TeamRejectionChecklist.TeamCheck teamCompositionCheck(UserHolder userHolder, Long workerTypeId) {
        return () -> {
            UserEntity user = userHolder.user; // resolved by the step-7 user-existence check
            if (user == null) {
                return; // user existence (step 7) owns a missing user; nothing to compose here.
            }
            RoleEntity companyRole = user.getRole();
            String roleCode = companyRole == null ? null : companyRole.getCode();

            TeamComposition.Violation violation =
                    TeamComposition.firstTeamCompositionViolation(roleCode, user.isActive());
            switch (violation) {
                case ROLE_NOT_ASSIGNABLE -> throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.role.not.assignable", roleCode);
                case USER_INACTIVE -> throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.user.inactive", user.getId());
                case NONE -> { /* criteria 1 and 3 pass; the worker-type sub-steps follow. */ }
            }

            // Worker-type sub-steps (Req 14.2–14.5, Property 3). A null workerTypeId is always
            // allowed (an Uncategorized_Worker, or a non-WORKER member with no worker type).
            if (workerTypeId == null) {
                return;
            }
            if (!isWorkerRole(companyRole)) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.worker.type.not.allowed", roleCode);
            }
            userHolder.workerType = resolveActiveWorkerType(workerTypeId); // 400 worker.type.invalid when missing/inactive
        };
    }

    /**
     * TASK 6.3 SLOT — step 9, last-ACTIVE MANAGER / CLIENT guard. Returns the check that rejects a
     * remove or deactivate dropping the last ACTIVE MANAGER (or CLIENT) to zero with 409
     * {@code error.project.member.last.manager} / {@code .last.client}, counting only ACTIVE members
     * (Requirement 9). The target row is resolved by the step-6 member-existence check, so the slot
     * reads it lazily from {@code holder.member} at run time (never at build time, when it is still
     * {@code null}). Task 6.1 leaves it a no-op; task 6.3 (and task 8.1) fill it so it slots into
     * {@link TeamRejectionChecklist.Step#LAST_ACTIVE_GUARD}.
     */
    private TeamRejectionChecklist.TeamCheck lastActiveGuardCheck(MemberHolder holder) {
        return () -> assertNotLastActiveManagerOrClient(holder.member);
    }

    /** The two Project_Role codes guarded by the last-ACTIVE invariant (Requirement 9 criteria 1–2). */
    private static final String MANAGER_ROLE_CODE = "MANAGER";
    private static final String CLIENT_ROLE_CODE = "CLIENT";

    // --- In-app notification type keys (FOR-05-09 task 12.2, Requirement 18, design §Notifications) ---
    //
    // The four Team_Notification_Types this spec emits (Requirement 18 criteria 1, 2, 3, 10,
    // Requirement 18 criterion 9). Each is a pure Notification_Type i18n key, resolved to a localized
    // title on the frontend (R12.2 of FOR-05-07) exactly like the FOR-05-07/08 emitters
    // (OfferNotificationEmitter / DocumentNotificationEmitter), so no backend `messages` entry is
    // required for them. The naming follows the `notification.<domain>.<event>` convention of those
    // emitters.

    /** {@code Notification_Type} i18n key: a member was assigned to a project (to the member, R18.1). */
    static final String NOTIFICATION_MEMBER_ASSIGNED = "notification.team.memberAssigned";

    /** {@code Notification_Type} i18n key: a member's Assignment_Status changed (to the member, R18.2). */
    static final String NOTIFICATION_STATUS_CHANGED = "notification.team.statusChanged";

    /** {@code Notification_Type} i18n key: a WORKER member's Worker_Type changed (to the worker, R18.10). */
    static final String NOTIFICATION_WORKER_TYPE_CHANGED = "notification.team.workerTypeChanged";

    /** {@code Notification_Type} i18n key: a member was removed from a project (to the member, R18.3). */
    static final String NOTIFICATION_MEMBER_REMOVED = "notification.team.memberRemoved";

    /**
     * TASK 8.1 — the reusable last-ACTIVE-MANAGER / last-ACTIVE-CLIENT guard (FOR-05-09 Requirement
     * 9). Rejects a remove or deactivate of {@code target} that would drop the project's count of
     * ACTIVE Project_Members of the target's Project_Role (MANAGER or CLIENT) to zero, with 409
     * {@code error.project.member.last.manager} / {@code error.project.member.last.client}; otherwise
     * returns normally and the caller proceeds to delete/deactivate the row.
     *
     * <p>This method is the single implementation shared by {@link #remove} (via
     * {@link #lastActiveGuardCheck}, step 9 of the canonical checklist) and the deactivate flow
     * (task 8.2 calls it directly before flipping ACTIVE → INACTIVE). It is intentionally agnostic to
     * whether the op is a remove or a deactivate: both subtract one ACTIVE member of the role, so the
     * same "would this leave zero ACTIVE of that role?" question governs both (Requirement 9 criteria
     * 1, 2, 5; Requirement 27 criteria 2, 7).
     *
     * <p><b>Only MANAGER and CLIENT are guarded.</b> A target whose Project_Role code is neither
     * {@code MANAGER} nor {@code CLIENT} is never blocked here (Requirement 9 criteria 1–2); the
     * method returns immediately for every other role (FOREMAN, ESTIMATOR, FINANCIER, WORKER, …).
     *
     * <p><b>Only ACTIVE targets subtract an ACTIVE member.</b> If {@code target} is already
     * {@code INACTIVE}, removing or deactivating it does not change the ACTIVE count of its role, so
     * the guard does not apply (Requirement 8 criterion 4, Requirement 27 criterion 2) and the method
     * returns.
     *
     * <p><b>ADMIN and non-ADMIN alike (Requirement 9 criterion 3).</b> The guard reads the project's
     * rows directly and never consults {@code isCallerAdmin()}, so an ADMIN caller is subject to it
     * exactly like any other caller — there is no bypass.
     *
     * <p><b>Counting + concurrency (Requirement 9 criteria 3, 8).</b> The ACTIVE count comes from a
     * COUNT query over the project's {@code project_members} rows filtered by the role code and
     * {@link AssignmentStatus#ACTIVE}, so it counts only ACTIVE members (including those whose user is
     * an Inactive_User or has status {@code INVITED}) and excludes INACTIVE members. Because the COUNT
     * runs inside the mutating transaction, two concurrent removes/deactivates of the last two ACTIVE
     * members of a role serialize on the row/transaction such that at least one ACTIVE member of the
     * role always survives (the loser gets the 409). A rejection throws before any delete/update, so
     * no {@code project_members} row, Audit_Log row, or notification is written for the rejected op
     * (Requirement 9 criteria 1, 2).
     *
     * @param target the resolved Project_Member about to be removed or deactivated (never {@code null}
     *               when called — the member-existence step resolves it first)
     * @throws ForemenApiException 409 {@code error.project.member.last.manager} /
     *                             {@code error.project.member.last.client} when the op would drop the
     *                             last ACTIVE member of that role to zero
     */
    void assertNotLastActiveManagerOrClient(ProjectMemberEntity target) {
        if (target == null) {
            return; // the member-existence step (6) owns a missing row; nothing to guard here.
        }
        // Removing/deactivating an already-INACTIVE member changes no ACTIVE count (Req 8.4 / 27.2).
        if (target.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
            return;
        }
        RoleEntity role = target.getProjectRole();
        String roleCode = role == null ? null : role.getCode();
        if (roleCode == null) {
            return;
        }
        String messageCode;
        if (MANAGER_ROLE_CODE.equals(roleCode)) {
            messageCode = "error.project.member.last.manager";
        } else if (CLIENT_ROLE_CODE.equals(roleCode)) {
            messageCode = "error.project.member.last.client";
        } else {
            return; // only MANAGER / CLIENT are guarded (Req 9.1–9.2).
        }
        long activeOfRole = projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                target.getProjectId(), roleCode, AssignmentStatus.ACTIVE);
        // The target is itself ACTIVE and counted above, so a count of exactly one means it is the
        // only ACTIVE member of the role and the op would drop the role to zero ACTIVE members.
        if (activeOfRole <= 1) {
            throw new ForemenApiException(HttpStatus.CONFLICT, messageCode, target.getProjectId());
        }
    }

    /**
     * Step 4 — project existence / access. For a by-pair operation the FOR-03-04 project-scope gate
     * is run against the project id directly: an ADMIN caller bypasses, and a non-accessible or
     * non-existent project is reported with 404 {@code error.entity.not.found}, byte-identical in
     * both cases (Requirements 3.1-3.3, 3.8). Returns a no-op for an ADMIN caller.
     */
    private TeamRejectionChecklist.TeamCheck projectAccessCheck(Long projectId) {
        return () -> {
            if (isCallerAdmin()) {
                return; // ADMIN bypass (Req 2.2/2.9).
            }
            Set<Long> accessible = callerAllowedProjectIds();
            if (projectId == null || !accessible.contains(projectId)) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", projectId);
            }
        };
    }

    /**
     * FOR-05-09 (task 14.2, Requirement 13 criteria 8, 9, 11) — runs canonical steps 4 and 5 for a
     * flow that must validate the project <em>before</em> it creates its subject (the
     * {@code Worker_Record_Flow} creates the WORKER user only after the project checks pass, so that
     * a non-accessible / locked project is reported before a would-be duplicate email, matching the
     * record-flow order of Requirement 13 criterion 11):
     *
     * <ol>
     *   <li><b>Step 4 — project existence / access.</b> The {@link ProjectScopedService} gate (ADMIN
     *       bypass; a non-accessible or non-existent project reported with 404
     *       {@code error.entity.not.found}, byte-identical in both cases, Requirement 13 criterion 9).</li>
     *   <li><b>Step 5 — lifecycle lock.</b> A Locked_Status project is rejected with 409
     *       {@code error.project.team.locked}, enforced for every caller including ADMIN
     *       (Requirement 13 criterion 8, Requirement 10).</li>
     * </ol>
     *
     * The two checks share the exact {@link #projectAccessCheck} / {@link #lifecycleLockCheck}
     * helpers the assign / remove / Attribute_Update flows use, so the {@code Worker_Record_Flow}
     * returns byte-identical responses for these outcomes. The later {@link #assign} call re-runs
     * them harmlessly (both are idempotent reads) and then owns the step-8 worker-type validation.
     *
     * @param projectId the target project id
     * @throws ForemenApiException 404 {@code error.entity.not.found} (non-existent / out-of-scope);
     *         409 {@code error.project.team.locked} (Locked_Status)
     */
    public void assertProjectAccessibleAndEditable(Long projectId) {
        TeamRejectionChecklist.run(TeamRejectionContext.builder()
                .projectAccess(projectAccessCheck(projectId))   // step 4
                .lifecycleLock(lifecycleLockCheck(projectId))   // step 5
                .build());
    }

    // --- Bespoke membership operations (FOR-03-04/05, FOR-04-13 consumers) ---

    /**
     * Assigns {@code userId} to {@code projectId} with no worker type and no tags. The three-arg
     * shape preserves the existing callers (ClientRegistrationService, ProjectService, the
     * controller's assign) — see {@link #assign(Long, Long, Long, Long, List)} for the full
     * semantics. The supplied {@code projectRoleId} is validated for role.mismatch (D2) but is
     * <em>not</em> the persisted role: the persisted Project_Role is always the user's current
     * Company_Role (Requirement 5 criteria 1, 10).
     */
    public ProjectMemberEntity assign(Long userId, Long projectId, Long projectRoleId) {
        return assign(userId, projectId, projectRoleId, null, null);
    }

    /**
     * Persists exactly one membership for the {@code (userId, projectId)} pair, funnelling every
     * validation through the canonical rejection checklist (task 6.1 / 7.1) so the <b>first tripped
     * check wins</b> and no row is written on any failure (Requirement 3 criterion 7, Requirement 10
     * criterion 4). On success the membership is saved and the user's project-access cache entry
     * invalidated.
     *
     * <p><b>Role source (D2, Requirement 5 criteria 1, 10, Property 1).</b> The persisted
     * Project_Role is the assigned user's <em>current</em> Company_Role ({@code user.getRole()}),
     * <strong>not</strong> the supplied {@code projectRoleId}. A caller may omit {@code projectRoleId}
     * entirely; if one <em>is</em> supplied and resolves to a code different from the user's
     * Company_Role, the assign is rejected at the mandatory-fields step with 400
     * {@code error.project.member.role.mismatch} (Requirement 5 criterion 11, Property 2) and no row
     * is persisted. The Company_Role must itself be an Assignable_Project_Role (team-composition step
     * 8), so a null/ADMIN/custom Company_Role is rejected with {@code role.not.assignable}.
     *
     * <p><b>Attributes (Requirement 5 criteria 2, 3).</b> The new member is created with
     * Assignment_Status {@code ACTIVE}; the submitted {@code workerTypeId} is attached only when the
     * resolved role is {@code WORKER} (upholding the Worker_Type-present ⇒ role=WORKER invariant,
     * Property 3); the submitted {@code tags} are stored in their {@link TagNormalizer} normal form.
     *
     * <p><b>Member count + isolation (Requirement 5 criteria 4, 7, 8).</b> A successful assign adds
     * exactly one row and leaves every existing member unchanged; there is no upper limit on the
     * number of CLIENT (or any) members (multi-client, parent Property 17).
     *
     * <p><b>Duplicate + concurrency (Requirement 5 criteria 5, 9).</b> A duplicate pair in <em>any</em>
     * Assignment_Status is rejected with 409 {@code error.project.member.duplicate} at the
     * member-existence step. Under a race the application-level check may pass for two concurrent
     * callers; the database {@code uk_project_members_user_project} unique constraint then admits
     * exactly one row, and the loser's {@link DataIntegrityViolationException} is translated to the
     * same 409 {@code error.project.member.duplicate} so exactly one row survives and the other call
     * gets a deterministic 409 (never a 5xx).
     *
     * @param userId        the user to attach (its Company_Role becomes the Project_Role)
     * @param projectId     the project to attach to
     * @param projectRoleId optional supplied role; validated for mismatch, never persisted (D2)
     * @param workerTypeId  optional worker type, applied only to a WORKER member (null = none)
     * @param submittedTags optional raw tag list (null = no tags), stored normalized
     * @throws ForemenApiException the first tripped canonical check (see {@link TeamRejectionChecklist}):
     *         400 {@code error.project.member.tag.invalid} / {@code error.project.member.role.mismatch};
     *         404 {@code error.entity.not.found} (project out of scope / missing user);
     *         409 {@code error.project.team.locked}; 409 {@code error.project.member.duplicate};
     *         400 {@code error.project.member.role.not.assignable} / {@code error.project.member.user.inactive};
     *         400 {@code error.project.member.worker.type.not.allowed} / {@code error.project.member.worker.type.invalid}.
     */
    public ProjectMemberEntity assign(Long userId,
                                      Long projectId,
                                      Long projectRoleId,
                                      Long workerTypeId,
                                      List<String> submittedTags) {
        UserHolder userHolder = new UserHolder();
        TagHolder tagHolder = new TagHolder();

        TeamRejectionContext checklist = TeamRejectionContext.builder()
                // Step 3 — mandatory fields: tag.invalid (shape) then role.mismatch (D2, Req 5.11).
                .mandatoryFields(mandatoryFieldsCheck(userId, projectRoleId, submittedTags, userHolder, tagHolder))
                // Step 4 — project existence / access (ADMIN bypass; 404 indistinguishable).
                .projectAccess(projectAccessCheck(projectId))
                // Step 5 — lifecycle lock -> 409 error.project.team.locked.
                .lifecycleLock(lifecycleLockCheck(projectId))
                // Step 6 — duplicate pair (any status) -> 409 (Req 5.5).
                .memberExistence(() -> assertNotDuplicate(userId, projectId))
                // Step 7 — user existence -> 404 error.entity.not.found (Req 5.6).
                .userExistence(() -> resolveUser(userId, userHolder))
                // Step 8 — team-composition sub-order (role.not.assignable / user.inactive / worker.type.*).
                .teamComposition(teamCompositionCheck(userHolder, workerTypeId))
                // Step 9 — last-ACTIVE guard does not apply to assign (it only adds a member).
                .build();

        TeamRejectionChecklist.run(checklist);

        UserEntity user = userHolder.user;                               // resolved by step 7
        RoleEntity companyRole = user.getRole();                         // D2: persist the Company_Role, not projectRoleId

        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(companyRole);                              // Req 5.1, 5.10
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);             // Req 5.2
        member.setTags(tagHolder.tags != null ? tagHolder.tags : new ArrayList<>()); // Req 5.3 (normalized)
        if (userHolder.workerType != null) {                             // Req 5.3, Property 3 (WORKER only, validated at step 8)
            member.setWorkerType(userHolder.workerType);
        }

        ProjectMemberEntity saved = persistUnique(member, userId, projectId); // Req 5.1, 5.9

        // TASK 12.1 — exactly one CREATE Audit_Log row in THIS transaction (Req 17.1). The Member_
        // Snapshot of the new member is the after-snapshot; there is no before-state (CREATE). An
        // audit-write failure here rolls back the whole assign because the method is @Transactional
        // (Req 17.6).
        writeAudit(saved, "CREATE", null, memberSnapshot(saved));

        projectAccessCache.invalidate(userId);                           // Req 8.1

        // TASK 12.2 — one in-app notification to the assigned member (R18.1), best effort, after the
        // row + audit have been written. Suppressed for a self-assignment (R18.4) and swallowed on a
        // NotificationService failure (R18.7). The body carries the project id + assigned Project_Role
        // code (the role name is resolved on the frontend, R18.9); no tags / no rate / cost (R18.12).
        emitTeamNotification(userId, NOTIFICATION_MEMBER_ASSIGNED,
                notificationBody(projectId, "role=" + (companyRole == null ? null : companyRole.getCode())),
                workspaceDeepLink(projectId));
        return saved;
    }

    /** True iff the role's code is {@code WORKER} (case-insensitive), the only role carrying a Worker_Type. */
    private static boolean isWorkerRole(RoleEntity role) {
        return role != null && role.getCode() != null && "WORKER".equalsIgnoreCase(role.getCode().trim());
    }

    /**
     * FOR-05-09 (task 15.1, Requirement 26) — the creation-restricted assign used by the FOR-04-13
     * Project_Creation_Orchestrator for each {@code members[]} entry. It behaves like
     * {@link #assign(Long, Long, Long, Long, List)} but adds the two creation-only restrictions of
     * Requirement 26 to the team-composition step, in the per-entry sub-order of Requirement 26
     * criterion 5, and never persists a Worker_Type (workers are added only from the Team tab, D13):
     *
     * <ol>
     *   <li><b>{@code error.project.member.role.not.assignable}</b> — the user's Company_Role is
     *       {@code ADMIN} or a non-system role (Requirement 6 criterion 1, Requirement 26 criterion
     *       10). Ranked first in the step, so an ADMIN / non-system user is reported as not-assignable
     *       before the Worker_Role check.</li>
     *   <li><b>{@code error.project.member.role.not.allowed.at.creation}</b> — the user's Company_Role
     *       is the Worker_Role (Requirement 26 criterion 2). Checked <em>after</em> role-assignability
     *       and <em>before</em> the inactive-user check, matching the Requirement 26 criterion 5
     *       sub-order (Requirement 6 criterion 1 → Requirement 26 criterion 2 → Requirement 6
     *       criterion 3 → Requirement 26 criterion 3). Applies regardless of the entry's
     *       {@code workerTypeId}.</li>
     *   <li><b>{@code error.project.member.user.inactive}</b> — the assigned user is an Inactive_User
     *       (Requirement 6 criterion 3).</li>
     *   <li><b>{@code error.project.member.worker.type.not.allowed}</b> — the entry carries a non-null
     *       {@code workerTypeId}, whatever the Project_Role and whether or not it matches a worker type
     *       (Requirement 26 criterion 3); the worker type is <em>not</em> looked up.</li>
     * </ol>
     *
     * <p>Every other check (step 3 mandatory fields incl. {@code role.mismatch} for a supplied
     * {@code projectRoleId} ≠ Company_Role; step 4 project access; step 5 lifecycle lock; step 6
     * duplicate pair → 409 {@code error.project.member.duplicate}; step 7 user existence) is the exact
     * {@link #assign} checklist, so the per-entry order of Requirement 26 criterion 5 — field
     * validation (incl. {@code role.mismatch}) → duplicate → user existence → the step-8 sub-order —
     * is honored, and a CLIENT entry for a CLIENT user is accepted (Requirement 26 criteria 1, 4).
     * On any failure no row is persisted and the whole creation is rolled back by the orchestrator's
     * {@code @Transactional} boundary (Requirement 26 criteria 2, 5; Requirement 6 criterion 5).
     *
     * <p>A supplied {@code workerTypeId} is only ever used to produce the {@code worker.type.not.allowed}
     * rejection; a surviving (null-workerTypeId) entry is assigned with no Worker_Type (Requirement 26
     * criterion 1).
     *
     * @param userId        the user to assign (its Company_Role becomes the Project_Role)
     * @param projectId     the project being created
     * @param projectRoleId the supplied role id; validated for {@code role.mismatch}, never persisted
     * @param workerTypeId  the entry's {@code workerTypeId}; a non-null value is rejected (Req 26.3)
     * @return the persisted membership
     * @throws ForemenApiException the first tripped check in the canonical / Requirement 26 criterion
     *         5 order
     */
    public ProjectMemberEntity assignAtCreation(Long userId,
                                                Long projectId,
                                                Long projectRoleId,
                                                Long workerTypeId) {
        UserHolder userHolder = new UserHolder();
        TagHolder tagHolder = new TagHolder();

        TeamRejectionContext checklist = TeamRejectionContext.builder()
                // Step 3 — mandatory fields: role.mismatch for a supplied projectRoleId != Company_Role
                // (no tags at creation).
                .mandatoryFields(mandatoryFieldsCheck(userId, projectRoleId, null, userHolder, tagHolder))
                // Step 4 — project existence / access (ADMIN bypass; 404 indistinguishable).
                .projectAccess(projectAccessCheck(projectId))
                // Step 5 — lifecycle lock -> 409 error.project.team.locked.
                .lifecycleLock(lifecycleLockCheck(projectId))
                // Step 6 — duplicate pair (any status) -> 409 error.project.member.duplicate (Req 26.9).
                .memberExistence(() -> assertNotDuplicate(userId, projectId))
                // Step 7 — user existence -> 404 error.entity.not.found.
                .userExistence(() -> resolveUser(userId, userHolder))
                // Step 8 — creation team-composition sub-order (Req 26.5).
                .teamComposition(creationTeamCompositionCheck(userHolder, workerTypeId))
                .build();

        TeamRejectionChecklist.run(checklist);

        UserEntity user = userHolder.user;               // resolved by step 7
        RoleEntity companyRole = user.getRole();         // D2: persist the Company_Role

        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(companyRole);
        member.setAssignmentStatus(AssignmentStatus.ACTIVE); // Req 26.1
        member.setTags(new ArrayList<>());                   // no tags at creation
        // No Worker_Type is ever persisted at creation (Req 26.1); a non-null workerTypeId was already
        // rejected by the team-composition step above.

        ProjectMemberEntity saved = persistUnique(member, userId, projectId);

        writeAudit(saved, "CREATE", null, memberSnapshot(saved)); // one CREATE audit row (Req 17.1)
        projectAccessCache.invalidate(userId);

        emitTeamNotification(userId, NOTIFICATION_MEMBER_ASSIGNED,
                notificationBody(projectId, "role=" + (companyRole == null ? null : companyRole.getCode())),
                workspaceDeepLink(projectId));
        return saved;
    }

    /**
     * The step-8 team-composition check for {@link #assignAtCreation}: the assign-side composition
     * sub-order of {@link #teamCompositionCheck} re-expressed with the two creation-only restrictions
     * of Requirement 26 slotted into the Requirement 26 criterion 5 order
     * ({@code role.not.assignable} → {@code role.not.allowed.at.creation} → {@code user.inactive} →
     * {@code worker.type.not.allowed}). The user is resolved by the step-7 user-existence check, so
     * the slot reads it from {@code userHolder} at run time. Applies to ADMIN and non-ADMIN alike.
     */
    private TeamRejectionChecklist.TeamCheck creationTeamCompositionCheck(UserHolder userHolder, Long workerTypeId) {
        return () -> {
            UserEntity user = userHolder.user; // resolved by the step-7 user-existence check
            if (user == null) {
                return; // user existence (step 7) owns a missing user.
            }
            RoleEntity companyRole = user.getRole();
            String roleCode = companyRole == null ? null : companyRole.getCode();

            // Req 6 crit 1 — the Company_Role must be an Assignable_Project_Role (rejects ADMIN /
            // non-system). Ranked first (Req 26.5 / Req 26.10).
            if (!TeamComposition.isAssignableProjectRole(roleCode)) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.role.not.assignable", roleCode);
            }

            // Req 26 crit 2 — a Worker_Role entry is not allowed at creation (regardless of the
            // user's active flag and whether the entry carries a workerTypeId). Ranked after
            // role.not.assignable and before user.inactive (Req 26.5).
            if (WORKER_ROLE_CODE.equalsIgnoreCase(roleCode == null ? null : roleCode.trim())) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.role.not.allowed.at.creation", roleCode);
            }

            // Req 6 crit 3 — the assigned user must be active.
            if (!user.isActive()) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.user.inactive", user.getId());
            }

            // Req 26 crit 3 — any non-null workerTypeId is rejected at creation, without a worker-type
            // lookup. Ranked last in the sub-order.
            if (workerTypeId != null) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.worker.type.not.allowed", roleCode);
            }
        };
    }

    /**
     * Resolves an Active_Worker_Type by id for a WORKER assign, rejecting a missing or inactive type
     * with 400 {@code error.project.member.worker.type.invalid} (Requirement 14, D10). On assign a
     * worker type is optional, so a null id never reaches here (the caller guards it).
     */
    private WorkerTypeEntity resolveActiveWorkerType(Long workerTypeId) {
        WorkerTypeEntity type = workerTypeDao.findById(workerTypeId).orElse(null);
        if (type == null || !type.isActive()) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.worker.type.invalid", workerTypeId);
        }
        return type;
    }

    /**
     * Persists {@code member}, translating a {@code uk_project_members_user_project} unique-constraint
     * violation from a concurrent assign into the same 409 {@code error.project.member.duplicate} the
     * application-level step-6 check raises, so a race yields exactly one row and the loser a
     * deterministic 409 (Requirement 5 criterion 9) rather than a 500.
     */
    private ProjectMemberEntity persistUnique(ProjectMemberEntity member, Long userId, Long projectId) {
        try {
            return projectMemberDao.save(member);
        } catch (DataIntegrityViolationException e) {
            throw new ForemenApiException(
                    HttpStatus.CONFLICT, "error.project.member.duplicate", userId, projectId);
        }
    }

    /**
     * Removes the membership of {@code userId} on {@code projectId}, funnelling the validation through
     * the canonical rejection checklist (task 6.1): project access (step 4), lifecycle lock (step 5,
     * task 6.3 slot), member existence (step 6), and the last-ACTIVE guard (step 9, task 6.3 / 8.1
     * slot). On success the row is hard-deleted and the user's project-access cache entry invalidated.
     *
     * @throws ForemenApiException the first tripped check: 404 {@code error.entity.not.found}
     *         (project out of scope); 404 {@code error.project.member.not.found} (no such pair).
     */
    public void remove(Long userId, Long projectId) {
        MemberHolder holder = new MemberHolder();

        TeamRejectionContext checklist = TeamRejectionContext.builder()
                // Step 4 — project existence / access.
                .projectAccess(projectAccessCheck(projectId))
                // Step 5 — lifecycle lock (task 6.3 slot).
                .lifecycleLock(lifecycleLockCheck(projectId))
                // Step 6 — member existence -> 404 (Req 3.4 / 8.x).
                .memberExistence(() -> assertMemberExists(userId, projectId, holder))
                // Step 9 — last-ACTIVE MANAGER / CLIENT guard (task 6.3 / 8.1 slot; reads holder lazily).
                .lastActiveGuard(lastActiveGuardCheck(holder))
                .build();

        TeamRejectionChecklist.run(checklist);

        ProjectMemberEntity target = holder.member;
        // TASK 12.1 — capture the Member_Snapshot the member held IMMEDIATELY BEFORE removal (Req
        // 17.3), including its id, before the delete makes it unavailable.
        String beforeSnapshot = memberSnapshot(target);
        Long membershipId = target.getId();
        // TASK 12.2 — capture the removed Project_Role code before the delete, for the notification
        // body (R18.3); the row is unavailable after the delete.
        RoleEntity removedRole = target.getProjectRole();
        String removedRoleCode = removedRole == null ? null : removedRole.getCode();

        projectMemberDao.delete(target);

        // Exactly one DELETE Audit_Log row in THIS transaction (Req 17.3). The before-snapshot is the
        // state before removal; there is no after-state (DELETE). An audit-write failure rolls the
        // whole remove back (Req 17.6).
        writeAuditForMembership(membershipId, "DELETE", beforeSnapshot, null);

        projectAccessCache.invalidate(userId);                           // Req 8.2

        // TASK 12.2 — one in-app notification to the removed member (R18.3), best effort, after the
        // row + audit. The removed user keeps the notification in their own list after losing access.
        // It carries the project id + removed Project_Role code and has NO deep-link (R18.3).
        // Suppressed for a self-removal (R18.4) and swallowed on a NotificationService failure (R18.7).
        emitTeamNotification(userId, NOTIFICATION_MEMBER_REMOVED,
                notificationBody(projectId, "role=" + removedRoleCode),
                null);
    }

    // --- Attribute_Update: the single PATCH entry point (FOR-05-09 Requirement 2, design §"Attribute_Update dispatch") ---

    /**
     * TASK 8.2 — the Attribute_Update dispatcher behind the single {@code PATCH /api/project-members}
     * handler (FOR-05-09 Requirement 2 criterion 1, design §"Attribute_Update dispatch"). The PATCH
     * body carries at most one of the three mutually-exclusive attribute groups; this method
     * dispatches on which one is present and returns the resulting {@link TeamMemberView}:
     *
     * <ul>
     *   <li>{@code assignmentStatus} present &rarr; deactivate / reactivate
     *       ({@link #changeAssignmentStatus}, Requirement 27 — owned by this task);</li>
     *   <li>{@code workerTypeId} present (and no {@code assignmentStatus}) &rarr; worker-type
     *       set / replace (Requirement 14 — <em>added by task 9.1</em>);</li>
     *   <li>{@code tags} present (and neither of the above) &rarr; tag replace (Requirement 15 —
     *       {@link #changeTags}).</li>
     * </ul>
     *
     * <p><b>Dispatch order.</b> The dispatch order follows the design: {@code assignmentStatus} wins,
     * then {@code workerTypeId}, then the tag branch. A tag change requires an explicit list, so a
     * PATCH that carries no {@code assignmentStatus} and no {@code workerTypeId} always dispatches to
     * {@link #changeTags} — a {@code null} tag list there is rejected as an invalid tag change
     * (Requirement 15 criterion 3), never silently ignored. A request that carries
     * {@code assignmentStatus} and other fields is treated as a status change (status wins), matching
     * the "exactly one attribute group per call" contract.
     *
     * @param userId           the target member user's id
     * @param projectId        the target project id (from the body, never the path)
     * @param assignmentStatus the raw target status string ({@code "ACTIVE"} / {@code "INACTIVE"}) for
     *                         a status change, or {@code null} when the call is not a status change
     * @param workerTypeId     the target worker type for a worker-type change (task 9.1)
     * @param tags             the replacement tag list for a tag change (task 9.5)
     * @return the {@link TeamMemberView} of the member after the update (or the current view for a
     *         no-op), masked for the caller
     */
    public TeamMemberView updateAttributes(Long userId,
                                           Long projectId,
                                           String assignmentStatus,
                                           Long workerTypeId,
                                           List<String> tags) {
        // Dispatch order (design §"Attribute_Update dispatch"): status first (Requirement 27).
        if (assignmentStatus != null) {
            return changeAssignmentStatus(userId, projectId, assignmentStatus);
        }
        // Task 9.1 — workerTypeId present (and no assignmentStatus) -> worker-type set/replace
        // (Requirement 14, design §"Attribute_Update dispatch").
        if (workerTypeId != null) {
            return changeWorkerType(userId, projectId, workerTypeId);
        }
        // Task 9.5 — tags present (and neither assignmentStatus nor workerTypeId) -> tag replace
        // (Requirement 15, design §"Attribute_Update dispatch"). A tag change REQUIRES an explicit
        // list: a null list is itself invalid (Requirement 15 criterion 3), because clearing tags is
        // done with an explicit empty list — so {@code tags == null} is routed here (not treated as
        // "no attribute group") and rejected at the mandatory-fields step with 400
        // error.project.member.tag.invalid. The last branch therefore always dispatches to the tag
        // change for a PATCH that carries no status and no worker type.
        return changeTags(userId, projectId, tags);
    }

    /**
     * TASK 9.5 — replace the whole {@code Assignment_Tags} list of a Project_Member via the PATCH
     * Attribute_Update (FOR-05-09 Requirement 15 criteria 2, 5, 6, 7, 11). Like
     * {@link #changeAssignmentStatus} / {@link #changeWorkerType}, the whole validation funnels
     * through the canonical {@link TeamRejectionChecklist} so the first tripped check wins and no row
     * changes on any failure (Requirement 3 criterion 7):
     *
     * <ol>
     *   <li><b>Step 3 — mandatory fields.</b> The submitted list is normalized via
     *       {@link TagNormalizer#normalizeRequired(List)}: a {@code null} list, or any list that
     *       violates Requirement 15 criterion 3 (a null element, a tag of 0 / &gt;50 code points
     *       after trim, a control character, or &gt;10 tags after normalization), is rejected with
     *       400 {@code error.project.member.tag.invalid} before any persistence (Requirement 15
     *       criterion 3, 11). Clearing tags requires an explicit <em>empty</em> list, which
     *       normalizes to an empty list and is accepted. The normalized result is stashed for the
     *       mutation.</li>
     *   <li><b>Step 4 — project existence / access.</b> The {@link ProjectScopedService} gate (ADMIN
     *       bypass; a non-accessible project reported as 404 {@code error.entity.not.found}
     *       byte-identical to a non-existent one).</li>
     *   <li><b>Step 5 — lifecycle lock.</b> A Locked_Status project is rejected with 409
     *       {@code error.project.team.locked} and the tags are left unchanged (Requirement 15
     *       criterion 5, Requirement 10).</li>
     *   <li><b>Step 6 — member existence.</b> A missing {@code (userId, projectId)} pair is rejected
     *       with 404 {@code error.project.member.not.found} (Requirement 15 criterion 5).</li>
     * </ol>
     *
     * <p><b>Replace, not merge (Requirement 15 criterion 5).</b> On a real change the member's whole
     * tag list is replaced with the normalized submitted list: a tag present before but absent from
     * the submitted list is removed, and an explicit empty list clears all tags. Only the tag list is
     * replaced; the membership id, user, project, Project_Role, Worker_Type, and Assignment_Status
     * are untouched, and every other member of the project is left unchanged.
     *
     * <p><b>Idempotent no-op (Requirement 15 criterion 6).</b> When the normalized submitted list
     * equals the member's current stored list — same number of tags and identical strings in the same
     * positions under <em>case-sensitive</em> comparison — the service changes no row, writes no
     * audit, emits no notification, and returns 200 with the current {@link TeamMemberView}. A change
     * that only reorders tags or only changes the letter case of a tag is <em>not</em> the same list
     * and is stored (and later audited) as a real change (Requirement 15 criterion 6, Requirement 17
     * criterion 8). The no-op screen runs after steps 1–6, so a locked project / missing pair is still
     * reported first in canonical order. The last-ACTIVE guard (step 9) never applies to a tag change.
     *
     * <p><b>Access cache (unlike assign/remove/status).</b> A tag change does <em>not</em> change the
     * user's project access (the membership and its Assignment_Status are unchanged), so the
     * {@link ProjectAccessCache} entry is <em>not</em> invalidated (design: cache is invalidated only
     * for assign / remove / deactivate / reactivate, Requirement 16 criteria 1, 5). Audit and
     * notification writes are tasks 12.x; a tag change emits no notification (Requirement 18).
     *
     * @throws ForemenApiException the first tripped canonical check: 400
     *         {@code error.project.member.tag.invalid} (null / invalid list); 404
     *         {@code error.entity.not.found} (project out of scope); 409
     *         {@code error.project.team.locked}; 404 {@code error.project.member.not.found}.
     */
    private TeamMemberView changeTags(Long userId, Long projectId, List<String> submittedTags) {
        MemberHolder holder = new MemberHolder();
        TagHolder tagHolder = new TagHolder();

        TeamRejectionContext checklist = TeamRejectionContext.builder()
                // Step 3 — mandatory field: a tag change REQUIRES an explicit list, so a null list is
                // itself invalid (Req 15.3); the normalized result is stashed for the persist path.
                .mandatoryFields(() -> tagHolder.tags = TagNormalizer.normalizeRequired(submittedTags))
                // Step 4 — project existence / access (ADMIN bypass; 404 indistinguishable).
                .projectAccess(projectAccessCheck(projectId))
                // Step 5 — lifecycle lock -> 409 error.project.team.locked (Req 15.5, Req 10).
                .lifecycleLock(lifecycleLockCheck(projectId))
                // Step 6 — member existence -> 404 error.project.member.not.found (Req 15.5).
                .memberExistence(() -> assertMemberExists(userId, projectId, holder))
                .build();

        TeamRejectionChecklist.run(checklist);

        ProjectMemberEntity member = holder.member;   // resolved by step 6
        List<String> newTags = tagHolder.tags;        // normalized by step 3 (never null)

        // Idempotent no-op (Req 15.6): the normalized list equals the stored list in the SAME
        // case-sensitive positions -> no change, no audit, no notification, 200 with the current
        // view. A reorder or a case change is a different list and falls through to a real replace
        // (Req 15.6, Req 17.8).
        List<String> current = member.getTags() == null ? List.of() : member.getTags();
        if (sameTagsCaseSensitive(current, newTags)) {
            return toView(member);
        }

        // TASK 12.1 — capture the before Member_Snapshot while the tags are still the old list, so
        // only the Assignment_Tags differ between the before and after snapshots (Req 17.8).
        String beforeSnapshot = memberSnapshot(member);

        // Real change (Req 15.5): replace the WHOLE tag list, keeping id/user/project/role/
        // workerType/status unchanged.
        member.setTags(new ArrayList<>(newTags));
        ProjectMemberEntity saved = projectMemberDao.save(member);

        // Exactly one UPDATE Audit_Log row in THIS transaction (Req 17.2/17.8). Written only on this
        // real-change branch — the idempotent no-op above returns before reaching here, so a no-op
        // writes no row (Req 17.4). An audit-write failure rolls the whole tag change back (Req 17.6).
        writeAudit(saved, "UPDATE", beforeSnapshot, memberSnapshot(saved));

        // A tag change does NOT change project access, so (unlike assign/remove/status) the
        // ProjectAccessCache is NOT invalidated here (Req 16.5).
        return toView(saved);
    }

    /**
     * True iff the two tag lists are the same under Requirement 15 criterion 6: the same number of
     * tags and identical strings in the same positions under a <em>case-sensitive</em> comparison.
     * A reorder or a letter-case change therefore counts as different (Requirement 17 criterion 8).
     */
    private static boolean sameTagsCaseSensitive(List<String> a, List<String> b) {
        return a.equals(b);
    }

    /**
     * TASK 8.2 — deactivate (ACTIVE &rarr; INACTIVE) or reactivate (INACTIVE &rarr; ACTIVE) a member
     * via the PATCH Attribute_Update (FOR-05-09 Requirement 27). The whole validation funnels through
     * the canonical {@link TeamRejectionChecklist} so the first tripped check wins and no row changes
     * on any failure (Requirement 3 criterion 7):
     *
     * <ol>
     *   <li><b>Step 3 — mandatory fields.</b> The target status is parsed from the raw string; a
     *       missing/blank value or anything other than {@code ACTIVE}/{@code INACTIVE} (case-sensitive
     *       enum names) is rejected with 400
     *       {@code error.project.member.assignment.status.invalid} before any persistence
     *       (Requirement 27 criterion 5). The parsed value is stashed for the mutation.</li>
     *   <li><b>Step 4 — project existence / access.</b> The {@link ProjectScopedService} gate (ADMIN
     *       bypass; a non-accessible project reported as 404 {@code error.entity.not.found}
     *       byte-identical to a non-existent one, Requirement 27 criterion 8).</li>
     *   <li><b>Step 5 — lifecycle lock.</b> A Locked_Status project is rejected with 409
     *       {@code error.project.team.locked} (Requirement 27 criterion 9).</li>
     *   <li><b>Step 6 — member existence.</b> A missing {@code (userId, projectId)} pair is rejected
     *       with 404 {@code error.project.member.not.found} (Requirement 27 criterion 4).</li>
     *   <li><b>Step 9 — last-ACTIVE guard.</b> Only for a <em>deactivate</em> (target {@code INACTIVE});
     *       {@link #assertNotLastActiveManagerOrClient} rejects dropping the last ACTIVE MANAGER /
     *       CLIENT to zero with 409 (Requirement 27 criterion 7). A reactivate (target {@code ACTIVE})
     *       only adds an ACTIVE member and is never guarded (Requirement 27 criterion 3), so the guard
     *       is wired only when the target is {@code INACTIVE}.</li>
     * </ol>
     *
     * <p><b>Idempotent no-op (Requirement 27 criterion 6).</b> When the member already has the
     * requested status, the service changes no row, writes no audit, emits no notification, and
     * returns 200 with the current {@link TeamMemberView}. The no-op is detected <em>after</em> the
     * checklist (so a locked project or missing pair is still reported first per the canonical order)
     * and <em>before</em> the last-ACTIVE guard is even wired, so re-deactivating an already-INACTIVE
     * last MANAGER is a clean no-op rather than a spurious 409.
     *
     * <p><b>Immutability of everything else (Requirement 27 criteria 2, 3).</b> Only
     * {@code assignmentStatus} is flipped; the membership id, user, project, Project_Role,
     * Worker_Type, and Assignment_Tags are untouched, and every other member of the project is left
     * unchanged. The row is soft-updated (never deleted), so the membership and its history remain
     * visible in the list (Requirement 27 criterion 8).
     *
     * <p><b>Access cache (mirrors assign/remove).</b> A real status change flips the user's project
     * access — deactivation removes it, reactivation restores it — so the user's
     * {@link ProjectAccessCache} entry is invalidated after a real change (never on a no-op or a
     * rejection). Audit and notification writes are tasks 12.x; this task only guarantees the no-op
     * path performs no state change.
     *
     * @throws ForemenApiException the first tripped canonical check: 400
     *         {@code error.project.member.assignment.status.invalid}; 404 {@code error.entity.not.found};
     *         409 {@code error.project.team.locked}; 404 {@code error.project.member.not.found};
     *         409 {@code error.project.member.last.manager} / {@code error.project.member.last.client}.
     */
    private TeamMemberView changeAssignmentStatus(Long userId, Long projectId, String rawStatus) {
        MemberHolder holder = new MemberHolder();
        StatusHolder statusHolder = new StatusHolder();

        // The deactivate-only last-ACTIVE guard: wired only when the parsed target is INACTIVE, read
        // lazily so the parse (step 3) and member resolution (step 6) run first in canonical order. A
        // reactivate (target ACTIVE) never trips it (Req 27.3); an idempotent no-op is screened out
        // before the guard runs (so re-deactivating a last MANAGER no-ops, not 409 — Req 27.6).
        TeamRejectionChecklist.TeamCheck lastActiveGuard = () -> {
            ProjectMemberEntity member = holder.member;
            if (member == null || statusHolder.target == null) {
                return;
            }
            boolean deactivating = statusHolder.target == AssignmentStatus.INACTIVE;
            boolean noOp = member.getAssignmentStatus() == statusHolder.target;
            if (deactivating && !noOp) {
                assertNotLastActiveManagerOrClient(member); // Req 27.7 (only a real deactivate)
            }
        };

        TeamRejectionContext checklist = TeamRejectionContext.builder()
                // Step 3 — mandatory field: parse + validate the target status (Req 27.5).
                .mandatoryFields(() -> statusHolder.target = parseAssignmentStatus(rawStatus))
                // Step 4 — project existence / access (ADMIN bypass; 404 indistinguishable, Req 27.8).
                .projectAccess(projectAccessCheck(projectId))
                // Step 5 — lifecycle lock -> 409 error.project.team.locked (Req 27.9).
                .lifecycleLock(lifecycleLockCheck(projectId))
                // Step 6 — member existence -> 404 error.project.member.not.found (Req 27.4).
                .memberExistence(() -> assertMemberExists(userId, projectId, holder))
                // Step 9 — last-ACTIVE guard (deactivate only, Req 27.7).
                .lastActiveGuard(lastActiveGuard)
                .build();

        TeamRejectionChecklist.run(checklist);

        ProjectMemberEntity member = holder.member;        // resolved by step 6
        AssignmentStatus target = statusHolder.target;     // parsed by step 3

        // Idempotent no-op (Req 27.6): already at the requested status -> no change, no audit, no
        // notification, 200 with the current view. (The last-ACTIVE guard above already treated this
        // as a no-op, so no spurious 409.)
        if (member.getAssignmentStatus() == target) {
            return toView(member);
        }

        // TASK 12.1 — capture the before Member_Snapshot while the status is still the old value, so
        // only the Assignment_Status differs between the before and after snapshots (Req 17.2).
        String beforeSnapshot = memberSnapshot(member);

        member.setAssignmentStatus(target);                // flip ACTIVE<->INACTIVE; nothing else (Req 27.2/27.3)
        ProjectMemberEntity saved = projectMemberDao.save(member);

        // Exactly one UPDATE Audit_Log row in THIS transaction (Req 17.2). Written only on this
        // real-change branch — the idempotent no-op above returns before reaching here, so a no-op
        // writes no row (Req 17.4). An audit-write failure rolls the whole status change back (Req 17.6).
        writeAudit(saved, "UPDATE", beforeSnapshot, memberSnapshot(saved));

        // A real status change flips the user's project access; mirror assign/remove and invalidate
        // the cache (deactivate removes access, reactivate restores it). TASK 12.2 — Req 16.1
        // invalidates after commit for deactivate/reactivate.
        projectAccessCache.invalidate(userId);

        // TASK 12.2 — one in-app notification to the affected member on a REAL status change
        // (deactivate / reactivate, R18.2), best effort, after the row + audit + cache invalidation.
        // The idempotent no-op above returned before here, so a no-op emits nothing (R18.8). The body
        // carries the project id + new Assignment_Status (name resolved on the frontend, R18.9).
        // Suppressed for a self-operation (R18.4) and swallowed on a NotificationService failure (R18.7).
        emitTeamNotification(userId, NOTIFICATION_STATUS_CHANGED,
                notificationBody(projectId, "assignmentStatus=" + target.name()),
                workspaceDeepLink(projectId));
        return toView(saved);
    }

    /**
     * TASK 9.1 — set or replace the Worker_Type of a WORKER member via the PATCH Attribute_Update
     * (FOR-05-09 Requirement 14). Like {@link #changeAssignmentStatus}, the whole validation funnels
     * through the canonical {@link TeamRejectionChecklist} so the first tripped check wins and no row
     * changes on any failure (Requirement 3 criterion 7):
     *
     * <ol>
     *   <li><b>Step 3 — mandatory fields.</b> A missing / null {@code workerTypeId} is rejected with
     *       400 {@code error.project.member.worker.type.invalid} before any persistence (Requirement
     *       14 criterion 8). The dispatcher only routes here when {@code workerTypeId != null}, and a
     *       non-integer / fractional / out-of-range value is rejected at JSON binding (the field is a
     *       {@link Long}), so this guard is defensive — it still fires if a null somehow reaches
     *       here.</li>
     *   <li><b>Step 4 — project existence / access.</b> The {@link ProjectScopedService} gate (ADMIN
     *       bypass; a non-accessible project reported as 404 {@code error.entity.not.found}
     *       byte-identical to a non-existent one).</li>
     *   <li><b>Step 5 — lifecycle lock.</b> A Locked_Status project is rejected with 409
     *       {@code error.project.team.locked} (Requirement 10).</li>
     *   <li><b>Step 6 — member existence.</b> A missing {@code (userId, projectId)} pair is rejected
     *       with 404 {@code error.project.member.not.found} (Requirement 14 criterion 6).</li>
     *   <li><b>Step 8 — worker-type rules.</b> A non-WORKER target is rejected with 400
     *       {@code error.project.member.worker.type.not.allowed} (Requirement 14 criterion 4,
     *       Property 3), then — unless the request is the idempotent same-type no-op below — a
     *       nonexistent or inactive (non-current) type is rejected with 400
     *       {@code error.project.member.worker.type.invalid} (Requirement 14 criterion 3). The
     *       {@code not.allowed} rule ranks before {@code invalid} (Requirement 14 criterion 9).</li>
     * </ol>
     *
     * <p><b>Idempotent no-op (Requirement 14 criterion 7).</b> When the target WORKER member already
     * has the requested Worker_Type, the service changes no row, writes no audit, emits no
     * notification, and returns 200 with the current {@link TeamMemberView}. This holds <em>even when
     * that Worker_Type is now inactive</em> ({@code active = false}): the same-type check is made
     * <em>before</em> the step-8 {@code invalid} rejection, so re-submitting the current (possibly
     * deactivated) type never fails on the inactive check (criterion 3 exempts the current type).
     * The no-op screen runs after steps 1–6 (so a locked project / missing pair is still reported
     * first in canonical order) and after the {@code not.allowed} check (a non-WORKER member can hold
     * no Worker_Type, so a same-type request on one is impossible). The last-ACTIVE guard (step 9)
     * never applies to a worker-type change — it neither removes nor deactivates a member.
     *
     * <p><b>Immutability of everything else (Requirement 14 criterion 5).</b> On a real change only
     * {@code workerType} is replaced; the membership id, user, project, Project_Role,
     * Assignment_Status, and Assignment_Tags are untouched, and every other member of the project is
     * left unchanged. The Worker_Type-present ⇒ role=WORKER invariant is upheld because the
     * {@code not.allowed} check guarantees a WORKER target before any attachment (Property 3).
     *
     * <p><b>Access cache (unlike assign/remove/status).</b> A worker-type change does <em>not</em>
     * change the user's project access (the membership and its Assignment_Status are unchanged), so
     * the {@link ProjectAccessCache} entry is <em>not</em> invalidated for a worker-type change
     * (design: cache is invalidated only for assign / remove / deactivate / reactivate, Requirement
     * 16 criteria 1, 5). Audit and notification writes are tasks 12.x.
     *
     * @throws ForemenApiException the first tripped canonical check: 400
     *         {@code error.project.member.worker.type.invalid} (missing/null id); 404
     *         {@code error.entity.not.found} (project out of scope); 409
     *         {@code error.project.team.locked}; 404 {@code error.project.member.not.found}; 400
     *         {@code error.project.member.worker.type.not.allowed} (non-WORKER target) /
     *         {@code error.project.member.worker.type.invalid} (nonexistent / inactive non-current type).
     */
    private TeamMemberView changeWorkerType(Long userId, Long projectId, Long workerTypeId) {
        MemberHolder holder = new MemberHolder();
        WorkerTypeHolder typeHolder = new WorkerTypeHolder();

        // Step 8 — worker-type rules, read lazily so the member (step 6) is resolved first. In order
        // (Req 14.9): not.allowed (non-WORKER target, Req 14.4) -> idempotent same-type exemption
        // (Req 14.7) -> invalid (nonexistent / inactive non-current type, Req 14.3). The resolved
        // Active_Worker_Type is stashed so the persist path reuses it without a second lookup.
        TeamRejectionChecklist.TeamCheck workerTypeCheck = () -> {
            ProjectMemberEntity member = holder.member;
            if (member == null) {
                return; // step 6 owns a missing pair; nothing to validate here.
            }
            if (!isWorkerRole(member.getProjectRole())) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.worker.type.not.allowed",
                        member.getProjectRole() == null ? null : member.getProjectRole().getCode());
            }
            // Idempotent same-type (Req 14.7): if the member already has this exact Worker_Type, it
            // is a no-op even when that type is now inactive. Flagged here so the mutation path below
            // skips both the invalid check and any state change (criterion 3 exempts the current type).
            WorkerTypeEntity current = member.getWorkerType();
            if (current != null && workerTypeId.equals(current.getId())) {
                typeHolder.sameType = true;
                return;
            }
            // A different (or first) type must be an Active_Worker_Type (Req 14.3): 400
            // error.project.member.worker.type.invalid when missing or inactive.
            typeHolder.workerType = resolveActiveWorkerType(workerTypeId);
        };

        TeamRejectionContext checklist = TeamRejectionContext.builder()
                // Step 3 — mandatory field: a null workerTypeId -> 400 worker.type.invalid (Req 14.8).
                // Defensive: the dispatcher only routes here when workerTypeId != null.
                .mandatoryFields(() -> {
                    if (workerTypeId == null) {
                        throw new ForemenApiException(
                                HttpStatus.BAD_REQUEST, "error.project.member.worker.type.invalid", (Object) null);
                    }
                })
                // Step 4 — project existence / access (ADMIN bypass; 404 indistinguishable).
                .projectAccess(projectAccessCheck(projectId))
                // Step 5 — lifecycle lock -> 409 error.project.team.locked (Req 10).
                .lifecycleLock(lifecycleLockCheck(projectId))
                // Step 6 — member existence -> 404 error.project.member.not.found (Req 14.6).
                .memberExistence(() -> assertMemberExists(userId, projectId, holder))
                // Step 8 — worker-type rules (not.allowed -> invalid, same-type exemption, Req 14.3-14.9).
                .teamComposition(workerTypeCheck)
                .build();

        TeamRejectionChecklist.run(checklist);

        ProjectMemberEntity member = holder.member; // resolved by step 6

        // Idempotent no-op (Req 14.7): already the requested type -> no change, no audit, no
        // notification, 200 with the current view (even if that type is now inactive).
        if (typeHolder.sameType) {
            return toView(member);
        }

        // TASK 12.1 — capture the before Member_Snapshot while the Worker_Type is still the old one,
        // so only the Worker_Type code differs between the before and after snapshots. A first
        // assignment to an Uncategorized_Worker has an empty Worker_Type code in the before snapshot
        // (Req 17.8).
        String beforeSnapshot = memberSnapshot(member);

        // Real change (Req 14.5): replace only the Worker_Type, keeping id/user/project/role/status/
        // tags unchanged (Property 3 upheld — the not.allowed check guaranteed a WORKER target).
        member.setWorkerType(typeHolder.workerType);
        ProjectMemberEntity saved = projectMemberDao.save(member);

        // Exactly one UPDATE Audit_Log row in THIS transaction (Req 17.2/17.8). Written only on this
        // real-change branch — the idempotent same-type no-op above returns before reaching here, so
        // a no-op writes no row (Req 17.4). An audit-write failure rolls the whole worker-type change
        // back (Req 17.6).
        writeAudit(saved, "UPDATE", beforeSnapshot, memberSnapshot(saved));

        // A worker-type change does NOT change project access, so (unlike assign/remove/status) the
        // ProjectAccessCache is NOT invalidated here (Req 16.5).

        // TASK 12.2 — one in-app notification to the affected WORKER only on a REAL worker-type change
        // (R18.10), best effort, after the row + audit. The idempotent same-type no-op above returned
        // before here, so a no-op emits nothing (R18.8). The body carries the project id + the new
        // Worker_Type code (its name resolved on the frontend, R18.9) and NO tier percentage, rate, or
        // cost, and no tags (R18.10, R18.12). Suppressed for a self-change (R18.4) and swallowed on a
        // NotificationService failure (R18.7).
        emitTeamNotification(userId, NOTIFICATION_WORKER_TYPE_CHANGED,
                notificationBody(projectId, "workerType="
                        + (typeHolder.workerType == null ? null : typeHolder.workerType.getCode())),
                workspaceDeepLink(projectId));
        return toView(saved);
    }

    /**
     * Parses the raw PATCH {@code assignmentStatus} into an {@link AssignmentStatus}, rejecting a
     * {@code null}/blank value or any token other than the enum names {@code ACTIVE}/{@code INACTIVE}
     * with 400 {@code error.project.member.assignment.status.invalid} (Requirement 27 criterion 5,
     * canonical step 3). The comparison is on the exact enum name (case-sensitive) to match the
     * stored representation.
     */
    private static AssignmentStatus parseAssignmentStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.assignment.status.invalid", rawStatus);
        }
        try {
            return AssignmentStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.assignment.status.invalid", rawStatus);
        }
    }

    /**
     * Maps a single membership row to a {@link TeamMemberView} for an Attribute_Update response,
     * applying the same request-locale and admin-staff masking as the list read ({@link #toView}
     * three-arg) so a WORKER/CLIENT caller never sees the Internal_Attributes while every reader sees
     * the Assignment_Status (D12, Requirement 4.10/4.11).
     */
    private TeamMemberView toView(ProjectMemberEntity member) {
        return toView(member, isRussianLocale(), isCallerAdminStaff());
    }

    // --- Audit snapshot (FOR-05-09 task 12.1, Requirement 17, design §"Audit snapshot") ---

    /**
     * The shared, minimal Jackson mapper used to serialize the {@link #memberSnapshot} payload. It
     * writes only the explicit {@code Member_Snapshot} fields this class builds — never a reflective
     * serialization of the whole entity graph — so a password, token, rate, tariff, Worker_Type tier
     * percentage, or cost can never leak into a team-change Audit_Log snapshot (Requirement 17
     * criterion 7).
     */
    private static final ObjectMapper AUDIT_SNAPSHOT_MAPPER = new ObjectMapper();

    /**
     * TASK 12.1 — writes exactly one team-change {@link AuditLogEntity} through the injected
     * {@link AuditLogDao}, in the caller's (mutating) transaction so an audit-write failure rolls the
     * whole operation back (Requirement 17 criterion 6). The row carries:
     *
     * <ul>
     *   <li>{@code entityClass} = {@code "ProjectMemberEntity"} (the audited subject type);</li>
     *   <li>{@code entityId} = the membership id (Requirement 17 criteria 1–3, "membership id as
     *       subject");</li>
     *   <li>{@code operation} = {@code CREATE} (assign) / {@code UPDATE} (worker-type / tag / status
     *       change) / {@code DELETE} (remove);</li>
     *   <li>{@code performedBy} = the id of the authenticated user who performed the operation,
     *       including an ADMIN caller (Requirement 17 criteria 1–3), resolved from the security
     *       principal exactly as the generic audit does;</li>
     *   <li>{@code performedAt} = the time of the change;</li>
     *   <li>{@code snapshotBefore} / {@code snapshotAfter} = the {@code Member_Snapshot}(s): a CREATE
     *       carries only the after-snapshot, a DELETE only the before-snapshot, and an UPDATE both,
     *       so a reader can see exactly what changed (Requirement 17 criteria 1, 2, 3, 8).</li>
     * </ul>
     *
     * <p>This is deliberately a bespoke write (not the generic {@link AdminService#saveAudit}): the
     * assign / remove / Attribute_Update flows mutate {@code project_members} rows directly (never
     * through the generic CRUD {@code create}/{@code update}/{@code delete}), so the generic path
     * never audits them and this method must — there is no double-write.
     */
    private void writeAudit(ProjectMemberEntity member, String operation, String before, String after) {
        writeAuditForMembership(member == null ? null : member.getId(), operation, before, after);
    }

    /** The id-based variant used by {@link #remove}, where the row is already deleted when audited. */
    private void writeAuditForMembership(Long membershipId, String operation, String before, String after) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setEntityClass(ProjectMemberEntity.class.getSimpleName());
        auditLog.setEntityId(membershipId);
        auditLog.setOperation(operation);
        auditLog.setPerformedBy(performingUser());
        auditLog.setPerformedAt(LocalDateTime.now());
        auditLog.setSnapshotBefore(before);
        auditLog.setSnapshotAfter(after);
        auditLogDao.save(auditLog);
    }

    /**
     * Builds the {@code Member_Snapshot} JSON for a membership (Requirement 17 criteria 1–3, 7). The
     * snapshot contains EXACTLY these fields and no others, so no sensitive value can ever be
     * serialized into a team-change audit row:
     *
     * <ul>
     *   <li>{@code userId} — the member user's id;</li>
     *   <li>{@code projectId} — the project id;</li>
     *   <li>{@code roleCode} — the Project_Role code (= the immutable Company_Role code, D2);</li>
     *   <li>{@code workerTypeCode} — the Worker_Type code, or the empty string when the member has no
     *       Worker_Type (an Uncategorized_Worker / a non-WORKER member);</li>
     *   <li>{@code assignmentStatus} — {@code ACTIVE} / {@code INACTIVE};</li>
     *   <li>{@code tags} — the ordered Assignment_Tags.</li>
     * </ul>
     *
     * It carries NO password, token, worker rate, tariff, Worker_Type tier percentage, or cost
     * (Requirement 17 criterion 7) — none of those are even read here.
     */
    private static String memberSnapshot(ProjectMemberEntity member) {
        if (member == null) {
            return null;
        }
        ObjectNode node = AUDIT_SNAPSHOT_MAPPER.createObjectNode();

        UserEntity user = member.getUser();
        if (user == null || user.getId() == null) {
            node.putNull("userId");
        } else {
            node.put("userId", user.getId());
        }

        if (member.getProjectId() == null) {
            node.putNull("projectId");
        } else {
            node.put("projectId", member.getProjectId());
        }

        RoleEntity role = member.getProjectRole();
        String roleCode = role == null ? null : role.getCode();
        node.put("roleCode", roleCode);

        // Worker_Type code, or the empty string when none (Req 17.1 "empty when none").
        WorkerTypeEntity type = member.getWorkerType();
        String workerTypeCode = type == null || type.getCode() == null ? "" : type.getCode();
        node.put("workerTypeCode", workerTypeCode);

        AssignmentStatus status = member.getAssignmentStatus();
        String statusName = status == null ? null : status.name();
        node.put("assignmentStatus", statusName);

        ArrayNode tags = node.putArray("tags");
        if (member.getTags() != null) {
            for (String tag : member.getTags()) {
                tags.add(tag);
            }
        }

        try {
            return AUDIT_SNAPSHOT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // Defensive: never crash the operation on a serialization fault — mirror the generic
            // audit's fallback shape rather than propagating.
            return "{\"error\":\"serialization_failed\",\"class\":\"ProjectMemberEntity\"}";
        }
    }

    /**
     * The id of the authenticated user who performed the operation, as the audit {@code performedBy}
     * (Requirement 17 criteria 1–3, "the id of the performing user", including ADMIN callers). The
     * security principal name is the numeric user id, so it is returned verbatim; a missing /
     * unauthenticated principal falls back to {@code "SYSTEM"}, matching the generic audit convention.
     */
    private static String performingUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "SYSTEM";
    }

    // --- In-app notifications (FOR-05-09 task 12.2, Requirement 18, design §Notifications) ---

    /**
     * TASK 12.2 — emits the single Team_Notification for a membership change to the <b>affected</b>
     * member, best effort (Requirement 18). The four callers wire the right Notification_Type:
     * assign → {@link #NOTIFICATION_MEMBER_ASSIGNED} (R18.1); a real status change (deactivate /
     * reactivate) → {@link #NOTIFICATION_STATUS_CHANGED} (R18.2); a real Worker_Type change →
     * {@link #NOTIFICATION_WORKER_TYPE_CHANGED} to the worker only (R18.10); remove →
     * {@link #NOTIFICATION_MEMBER_REMOVED} with <b>no</b> deep-link (R18.3). No caller is wired for a
     * tag change (R18.11), an idempotent no-op (R18.8), or a rejected / rolled-back op (R18.6) —
     * those paths return before reaching an emit call.
     *
     * <p><b>Only the affected member, never a self-operation (R18.4, R18.5).</b> The notification is
     * created only for the affected member and never for any other member of the project. When the
     * performing (acting) user is the affected member — a self-assignment / self-deactivation /
     * self-reactivation / self-worker-type-change / self-removal — no notification is created. The
     * acting caller is resolved from the security principal (the numeric user id); an unresolved /
     * SYSTEM caller is treated as "not the member" so the notification is still emitted (a server-side
     * flow such as the Project_Creation_Orchestrator notifies the assigned user, R18.1).
     *
     * <p><b>After commit, best-effort (R18.5, R18.7).</b> Every mutating caller invokes this at the
     * very end of its {@code @Transactional} method, after the row mutation and the audit write, so
     * the notification is created only for a change that will commit (a prior rejection throws before
     * reaching here, and the audit write — which would roll the change back on failure — has already
     * succeeded). Mirroring the ProjectMemberService audit pattern, this runs inside the mutating
     * transaction rather than via an {@code AFTER_COMMIT} emitter; {@link NotificationService#create}
     * uses {@code REQUIRES_NEW}, so the notification row commits independently and a
     * {@link NotificationService} failure is caught, logged, and swallowed here — the committed
     * membership change is kept and the normal success response is still returned (R18.7).
     *
     * @param recipientUserId the affected member's user id (the sole recipient, R18.5)
     * @param type            the Notification_Type i18n key for the change
     * @param body            the message body carrying the project/role/status/worker-type identity
     * @param deepLink        the Workspace_Shell deep-link, or {@code null} for a remove (R18.3)
     */
    private void emitTeamNotification(Long recipientUserId, String type, String body, String deepLink) {
        if (recipientUserId == null) {
            return;
        }
        if (isSelfOperation(recipientUserId)) {
            return; // R18.4: no notification for a self-operation.
        }
        try {
            notificationService.create(recipientUserId, type, body, deepLink);
        } catch (Exception e) {
            // R18.7: a NotificationService failure is log-only — never propagate, never roll back the
            // committed membership change, and still return the normal success response.
            log.error("Failed to emit team notification (type {}) to user {}", type, recipientUserId, e);
        }
    }

    /**
     * True iff the authenticated acting caller is the affected member (Requirement 18 criterion 4).
     * The acting caller's numeric user id comes from the security principal name; a blank / non-numeric
     * / unauthenticated principal (e.g. a SYSTEM flow) is treated as <em>not</em> the member so the
     * notification is still emitted to the affected user (R18.1 server-side flows).
     */
    private static boolean isSelfOperation(Long affectedUserId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Long actingUserId = parseUserId(auth.getName());
        return actingUserId != null && actingUserId.equals(affectedUserId);
    }

    /**
     * The Workspace_Shell deep-link for a project ({@code /projects/{projectId}}, R18.1/18.2/18.10):
     * the project-workspace route the assigned / updated member is pointed to. Returns {@code null}
     * when the project id is unknown (a non-interactive notification, consistent with
     * {@link NotificationService#create}'s nullable deep-link). A remove notification passes
     * {@code null} directly (R18.3) and never calls this.
     */
    private static String workspaceDeepLink(Long projectId) {
        return projectId == null ? null : "/projects/" + projectId;
    }

    /**
     * Builds the identity body for a team notification (Requirement 18 criteria 1, 2, 3, 10, 12). The
     * localized Notification_Type title and the Project_Role / Assignment_Status / Worker_Type names
     * are resolved from i18n keys on the frontend (R18.9); this body carries the stable identifiers
     * (project id and the relevant code) so the message names the concrete subject. It carries
     * <b>no</b> Assignment_Tag and no Worker_Type other than the one of criterion 10 (R18.12), and no
     * tier percentage, rate, or cost (R18.10) — none of those are read here.
     */
    private static String notificationBody(Long projectId, String detail) {
        List<String> parts = new ArrayList<>(2);
        if (projectId != null) {
            parts.add("projectId=" + projectId);
        }
        if (detail != null && !detail.isBlank()) {
            parts.add(detail);
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    // --- Individual canonical checks wired by task 6.1 ---

    /** Step 6 (assign) — reject a duplicate pair in any Assignment_Status with 409 (Req 5.5). */
    private void assertNotDuplicate(Long userId, Long projectId) {
        if (projectMemberDao.existsByUserIdAndProjectId(userId, projectId)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.project.member.duplicate", userId, projectId);
        }
    }

    /**
     * Lazily resolves the user into {@code userHolder} <em>without</em> throwing when it is missing.
     * Used by the step-3 mandatory-fields check (role.mismatch), which must not report a non-existent
     * user — step 7 ({@link #resolveUser}) owns the 404 so a mismatching role never masks a missing
     * user. Idempotent: a user already resolved into the holder is reused, not re-fetched.
     */
    private void resolveUserInto(Long userId, UserHolder userHolder) {
        if (userHolder.user == null) {
            userHolder.user = userDao.findById(userId).orElse(null);
        }
    }

    /** Step 7 (assign) — resolve the user or reject with 404 {@code error.entity.not.found} (Req 5.6). */
    private void resolveUser(Long userId, UserHolder userHolder) {
        resolveUserInto(userId, userHolder);
        if (userHolder.user == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", userId);
        }
    }

    /** Step 6 (remove/update) — resolve the member or reject with 404 {@code error.project.member.not.found}. */
    private void assertMemberExists(Long userId, Long projectId, MemberHolder holder) {
        holder.member = projectMemberDao.findByUserIdAndProjectId(userId, projectId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.project.member.not.found", userId, projectId));
    }

    /**
     * Mutable holder so the user-existence step can hand the resolved user to later steps, and so the
     * team-composition step can hand the resolved Active_Worker_Type to the persist path without a
     * second lookup (both resolved at most once per assign).
     */
    private static final class UserHolder {
        private UserEntity user;
        private WorkerTypeEntity workerType;
    }

    /** Mutable one-slot holder so the member-existence step can hand the row to the last-ACTIVE guard. */
    private static final class MemberHolder {
        private ProjectMemberEntity member;
    }

    /**
     * Mutable one-slot holder so the step-3 mandatory-fields check (which parses the raw PATCH
     * status) can hand the validated {@link AssignmentStatus} to the later last-ACTIVE guard and the
     * mutation path of {@link #changeAssignmentStatus} (parsed at most once per call).
     */
    private static final class StatusHolder {
        private AssignmentStatus target;
    }

    /**
     * Mutable one-slot holder so the step-3 mandatory-fields check can hand the normalized tag list
     * (computed once in {@link TagNormalizer#normalize(List)}) to the persist path without
     * re-normalizing it.
     */
    private static final class TagHolder {
        private List<String> tags;
    }

    /**
     * Mutable holder for the worker-type change (task 9.1): the step-8 check hands the resolved
     * Active_Worker_Type (for a real change) to the persist path, or flags the request as the
     * idempotent same-type no-op (Requirement 14 criterion 7) so the mutation path makes no change.
     * Exactly one of the two is set when the checklist passes.
     */
    private static final class WorkerTypeHolder {
        private WorkerTypeEntity workerType;
        private boolean sameType;
    }

    /** Returns every membership row whose {@code project_id} equals {@code projectId} (Req 3.5). */
    @Transactional(readOnly = true)
    public List<ProjectMemberEntity> listMembers(Long projectId) {
        return projectMemberDao.findByProjectId(projectId);
    }

    /**
     * TASK 9.3 — the downstream worker-type read (FOR-05-09 Requirement 14 criterion 13). Returns one
     * {@link WorkerTypeAssignment} per <b>WORKER</b> Project_Member of {@code projectId}, carrying the
     * member's user id, membership id, Assignment_Status, and current Worker_Type id and code, as a
     * read-only input for downstream consumers (FOR-05-06 margin computation and FOR-10 / FOR-11
     * payroll / real-cost computation) that may flag an Uncategorized_Worker as not-ready.
     *
     * <p><b>Only WORKER members.</b> Only memberships whose derived {@link TeamBlock} is
     * {@link TeamBlock#WORKERS} are returned (the Worker_Type is a WORKERS-only attribute); every
     * admin-staff / CLIENT member is excluded (Requirement 14 criterion 13). Both ACTIVE and INACTIVE
     * WORKER members are returned, each carrying its own Assignment_Status so a downstream consumer
     * can decide how to treat a deactivated member.
     *
     * <p><b>Inactive Worker_Types are included (Requirement 14 criterion 10).</b> A member whose
     * Worker_Type has since been deactivated still reports that type's id and code — the type is kept
     * on the member, so the read reflects the reference regardless of the type's {@code active} flag.
     *
     * <p><b>Uncategorized_Worker (Requirement 14 criteria 13, 16).</b> A WORKER member with no
     * Worker_Type is reported with an explicit {@code null} {@code workerTypeId} and
     * {@code workerTypeCode}, so a downstream consumer can detect it.
     *
     * <p><b>Read-only (Requirement 14 criterion 13).</b> This method issues a single {@code SELECT}
     * via {@link ProjectMemberDao#findByProjectId(Long)} inside a {@code readOnly} transaction and
     * mutates no {@code project_members} row: it neither saves nor deletes, and it is <em>not</em>
     * subject to the Team_Member_View admin-staff masking — it is an internal cross-spec contract,
     * never serialized to a WORKER / CLIENT reader, so the Worker_Type reference is always present.
     * It computes no pay, rate, or cost (out of scope).
     *
     * <p>The result order mirrors the DAO's row order; downstream consumers key by {@code userId} /
     * {@code membershipId} rather than relying on order, so no explicit ordering is imposed here.
     *
     * @param projectId the project whose WORKER worker-type assignments are read
     * @return one {@link WorkerTypeAssignment} per WORKER member of the project (empty when the
     *         project has no WORKER members)
     */
    @Transactional(readOnly = true)
    public List<WorkerTypeAssignment> listWorkerTypeAssignments(Long projectId) {
        return projectMemberDao.findByProjectId(projectId).stream()
                .filter(member -> {
                    RoleEntity role = member.getProjectRole();
                    String roleCode = role == null ? null : role.getCode();
                    return TeamMemberOrdering.blockOf(roleCode) == TeamBlock.WORKERS;
                })
                .map(ProjectMemberService::toWorkerTypeAssignment)
                .toList();
    }

    /**
     * Maps one WORKER {@link ProjectMemberEntity} to a {@link WorkerTypeAssignment} (Requirement 14
     * criterion 13). The Worker_Type id / code come from the member's current Worker_Type and are
     * both {@code null} for an Uncategorized_Worker; an inactive Worker_Type is reported as-is (its
     * {@code active} flag is deliberately not inspected here, Requirement 14 criterion 10).
     */
    private static WorkerTypeAssignment toWorkerTypeAssignment(ProjectMemberEntity member) {
        UserEntity user = member.getUser();
        Long userId = user == null ? null : user.getId();
        WorkerTypeEntity type = member.getWorkerType();
        Long workerTypeId = type == null ? null : type.getId();         // explicit null for an Uncategorized_Worker
        String workerTypeCode = type == null ? null : type.getCode();   // includes an inactive type's code
        return new WorkerTypeAssignment(
                userId,
                member.getId(),
                member.getAssignmentStatus(),
                workerTypeId,
                workerTypeCode);
    }

    /**
     * TASK 7.5 — the enriched team list read (FOR-05-09 Requirement 4). Returns one
     * {@link TeamMemberView} per {@link ProjectMemberEntity} of {@code projectId}, in the
     * deterministic Team_Block order of {@link TeamMemberOrdering#comparator()} (Requirement 4
     * criterion 6), so two reads over unchanged data return identical order.
     *
     * <p><b>Who is included.</b> Every membership row of the project is mapped — including a member
     * whose user is an Inactive_User, whose user has status {@code INVITED}, or whose
     * Assignment_Status is {@code INACTIVE} (Requirement 4 criterion 1). An empty team yields an
     * empty list, never a 404 (Requirement 4 criterion 2). The result reflects every committed
     * assign / Attribute_Update / deactivate / reactivate / remove on the next read, because the rows
     * are read fresh from the DAO within the read transaction (Requirement 4 criterion 9).
     *
     * <p><b>Project id validation (Requirement 4 criterion 8).</b> A missing / null / non-positive
     * {@code projectId} is rejected with 400 {@code error.project.member.project.id.invalid} before
     * any lookup, returning no view. The FOR-03-04 project-scope gate (Accessible_Project / 404
     * indistinguishability) is applied by the inherited {@link ProjectScopedService} list filter and
     * the controller/interceptor layer; this method owns only the field-shape 400.
     *
     * <p><b>Localized Project_Role name (Requirement 4 criterion 4, Requirement 24 criterion 4).</b>
     * The {@code projectRoleName} is the role's {@code nameRU} for a {@code ru} request locale
     * (case-insensitive) and its {@code namePL} for any other / absent / unsupported locale, falling
     * back to the role {@code code} when the chosen localized name is blank. The same resolution is
     * applied to the internal {@code workerTypeName} (Requirement 4 criterion 10).
     *
     * <p><b>Internal fields + masking (task 7.6, Requirement 4.10/4.11).</b> For an admin-staff reader
     * every field is populated, including the internal worker-type / NIP / tag fields and
     * {@code workerTypeMissing = (block == WORKERS && workerTypeId == null)} (Requirement 14 criterion
     * 16). For a WORKER / CLIENT reader those Internal_Attributes are omitted (nulled, so the view's
     * {@code @JsonInclude(NON_NULL)} drops them); {@code assignmentStatus} is never masked. The
     * admin-staff flag is computed once per read via {@link #isCallerAdminStaff()} and threaded into
     * {@link #toView}.
     */
    @Transactional(readOnly = true)
    public List<TeamMemberView> listMemberViews(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.project.id.invalid", projectId);
        }
        boolean russian = isRussianLocale();
        boolean adminStaff = isCallerAdminStaff(); // D12: Internal_Attributes visible to admin-staff only.
        return projectMemberDao.findByProjectId(projectId).stream()
                .map(member -> toView(member, russian, adminStaff))
                .sorted(TeamMemberOrdering.comparator())
                .toList();
    }

    /**
     * Maps one {@link ProjectMemberEntity} to the enriched {@link TeamMemberView} (Requirement 4
     * criteria 3, 5, 7, 10). The Project_Role is the user's immutable Company_Role (D2), so the
     * {@code projectRoleId} / {@code projectRoleCode} / {@code companyRoleCode} all come from the
     * membership's {@code projectRole}; the {@link TeamBlock} is derived purely from the role code.
     * The worker-type / NIP fields are populated for a WORKERS member, and {@code workerTypeMissing}
     * is {@code true} exactly for a WORKERS member with no worker type. When {@code adminStaff} is
     * {@code false} the Internal_Attributes (worker type id/code/name/active, nip, workerTypeMissing,
     * tags) are omitted (nulled) so a WORKER/CLIENT reader never sees them (D12, task 7.6); the
     * never-internal {@code assignmentStatus} / {@code workerKind} / {@code contactPerson} are
     * unaffected.
     */
    private TeamMemberView toView(ProjectMemberEntity member, boolean russian, boolean adminStaff) {
        RoleEntity role = member.getProjectRole();
        String roleCode = role == null ? null : role.getCode();
        Long roleId = role == null ? null : role.getId();
        String roleName = localizedRoleName(role, russian);
        TeamBlock block = TeamMemberOrdering.blockOf(roleCode);

        UserEntity user = member.getUser();
        Long userId = user == null ? null : user.getId();
        String userName = user == null ? null : user.getName();
        String userEmail = user == null ? null : user.getEmail();
        String userStatus = user == null || user.getStatus() == null ? null : user.getStatus().name();
        Boolean userActive = user == null ? null : user.isActive();

        boolean workers = block == TeamBlock.WORKERS;
        WorkerKind workerKind = null;
        String contactPerson = null;
        String nip = null;
        if (workers && user != null) {
            // D8: a null stored Worker_Kind is treated as PERSON for a WORKERS view (Requirement 4 criterion 3).
            workerKind = user.getWorkerKind() == null ? WorkerKind.PERSON : user.getWorkerKind();
            contactPerson = user.getContactPerson();
            nip = user.getNip();
        }

        WorkerTypeEntity type = member.getWorkerType();
        Long workerTypeId = type == null ? null : type.getId();
        String workerTypeCode = type == null ? null : type.getCode();
        String workerTypeName = type == null ? null : localizedWorkerTypeName(type, russian);
        Boolean workerTypeActive = type == null ? null : type.isActive();
        // Req 14.16: workerTypeMissing is true iff the member is in the WORKERS block and has no
        // worker type. Boxed so a non-admin-staff reader can have it omitted (null) by the mask below.
        Boolean workerTypeMissing = workers && workerTypeId == null;

        List<String> tags = member.getTags() == null ? List.of() : List.copyOf(member.getTags());

        // D12 masking (task 7.6, Requirement 4.10/4.11). The Internal_Attributes — worker type
        // (id/code/name/active), nip, workerTypeMissing, and tags — are visible only to an
        // admin-staff reader; for a WORKER/CLIENT reader they are omitted (nulled, so the view's
        // @JsonInclude(NON_NULL) drops them from the payload). assignmentStatus, workerKind, and
        // contactPerson are NOT internal and are never masked. This record is immutable, so the mask
        // is applied by building the view with the internal fields nulled rather than by reflection.
        if (!adminStaff) {
            workerTypeId = null;
            workerTypeCode = null;
            workerTypeName = null;
            workerTypeActive = null;
            nip = null;
            workerTypeMissing = null; // Boolean -> omitted (not false) for a non-admin-staff reader
            tags = null;              // List -> omitted (not []) for a non-admin-staff reader
        }

        return new TeamMemberView(
                member.getId(),
                userId,
                member.getProjectId(),
                roleId,
                roleCode,
                roleName,
                roleCode,                 // companyRoleCode == projectRoleCode by D2
                block,
                member.getAssignmentStatus(),
                userName,
                userEmail,
                userStatus,
                userActive,
                workerKind,
                contactPerson,
                workerTypeId,
                workerTypeCode,
                workerTypeName,
                workerTypeActive,
                nip,
                workerTypeMissing,
                tags);
    }

    /**
     * The localized Project_Role name: {@code nameRU} for a {@code ru} request, {@code namePL}
     * otherwise, falling back to the role {@code code} when the chosen name is {@code null}/blank, and
     * to {@code null} for a {@code null} role (Requirement 4 criterion 4, Requirement 24 criterion 4).
     */
    private static String localizedRoleName(RoleEntity role, boolean russian) {
        if (role == null) {
            return null;
        }
        String name = russian ? role.getNameRU() : role.getNamePL();
        if (name == null || name.isBlank()) {
            return role.getCode();
        }
        return name;
    }

    /**
     * The localized Worker_Type name, resolved exactly as {@link #localizedRoleName} (ru → nameRU,
     * else namePL, falling back to the worker-type {@code code}; Requirement 4 criterion 10,
     * Requirement 24 criterion 4).
     */
    private static String localizedWorkerTypeName(WorkerTypeEntity type, boolean russian) {
        String name = russian ? type.getNameRU() : type.getNamePL();
        if (name == null || name.isBlank()) {
            return type.getCode();
        }
        return name;
    }

    /** True iff the request locale language is {@code ru} (case-insensitive); mirrors the app's i18n choice. */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    // --- Candidate lookup (FOR-05-09 task 10.1, Requirement 11, design §"Candidate lookup") ---

    /** The default candidate page size when the caller supplies none (Requirement 11 criterion 7). */
    static final int DEFAULT_CANDIDATE_PAGE_SIZE = 20;

    /** The maximum candidate page size; a larger requested size is clamped to it (Requirement 11 criterion 7). */
    static final int MAX_CANDIDATE_PAGE_SIZE = 50;

    /** The maximum accepted search-term length after trimming (Requirement 11 criterion 9). */
    private static final int MAX_CANDIDATE_TERM_LENGTH = 100;

    /**
     * TASK 10.1 — the paginated candidate search behind {@code GET /api/project-members/candidates}
     * (FOR-05-09 Requirement 11). Returns the page of users who may be assigned to {@code projectId}:
     * every user who is <em>not</em> already a Project_Member of the project (in any Assignment_Status)
     * and is <em>not</em> an Inactive_User, narrowed by the optional {@code term} / {@code role} /
     * {@code block} filters, ordered by display name then id, with the total match count carried by
     * the returned {@link Page} (Requirement 11 criteria 1, 2, 3, 7, 11). An {@code INVITED}+active
     * user and an uninvited WORKER record are both {@code active = true} and so are included.
     *
     * <p><b>Canonical order (Requirement 3 criterion 7).</b> Steps 1–2 (401 token, 403
     * {@code PROJECT_MEMBERS} CREATE) are enforced upstream by the security filter and the
     * {@code PermissionInterceptor} before this method runs (Requirement 11 criterion 10). This
     * method then applies, in order:
     * <ol>
     *   <li><b>Step 3 — mandatory fields (400).</b> A supplied {@code role} that is not an
     *       Assignable_Project_Role is rejected with {@code error.project.member.role.not.assignable}
     *       (Requirement 11 criterion 8); a supplied {@code block} that is not one of
     *       {@code ADMIN_STAFF}/{@code WORKERS}/{@code CLIENTS} with
     *       {@code error.project.member.block.invalid} (Requirement 11 criterion 12); a page index
     *       below zero, a page size below one, or a trimmed {@code term} longer than 100 characters
     *       with {@code error.project.member.candidate.page.invalid} (Requirement 11 criterion 9).
     *       No Candidate data is returned on any of these.</li>
     *   <li><b>Step 4 — project existence / access (404).</b> The {@link ProjectScopedService} gate
     *       ({@link #projectAccessCheck}) rejects a non-accessible or non-existent project with 404
     *       {@code error.entity.not.found}, byte-identical in both cases, with an ADMIN bypass
     *       (Requirement 11 criterion 6).</li>
     * </ol>
     * Validation runs before the DB query, so a rejected request issues no candidate query.
     *
     * <p><b>Filters.</b> {@code term} is trimmed; an empty/whitespace-only term is treated as not
     * supplied, otherwise it is lower-cased and matched as a case-insensitive substring over name OR
     * email (Requirement 11 criterion 2). {@code role} filters on the candidate's Company_Role code
     * (Requirement 11 criterion 3). {@code block} filters on the candidate's compatible Team_Block,
     * which is a pure function of the Company_Role (D3/D6): {@code WORKERS}/{@code CLIENTS} map to the
     * single role {@code WORKER}/{@code CLIENT}, and {@code ADMIN_STAFF} excludes those two roles
     * (Requirement 11 criterion 11). A {@code role}+{@code block} pair applies both filters.
     *
     * <p><b>Pagination.</b> The page size defaults to {@value #DEFAULT_CANDIDATE_PAGE_SIZE} and is
     * clamped to at most {@value #MAX_CANDIDATE_PAGE_SIZE}; the page index is zero-based and the sort
     * is the fixed name-then-id order encoded in the query (Requirement 11 criterion 7).
     *
     * <p><b>Candidate shape (Requirement 11 criteria 4, 5).</b> Each {@link Candidate} carries the
     * user id, display name, email (empty for an uninvited worker), status, Company_Role code and its
     * localized name, the derived Team_Block, and — only for a WORKERS candidate — the Worker_Kind
     * ({@code null} treated as {@code PERSON}) and contact person. It never carries a password, token,
     * rate, cost, worker type, NIP, or tag.
     *
     * @param projectId   the project candidates are sought for (required)
     * @param term        the raw search term (may be {@code null}/blank → not supplied)
     * @param role        the target Company_Role code filter (may be {@code null})
     * @param block       the target Team_Block name filter (may be {@code null})
     * @param page        the zero-based page index
     * @param size        the requested page size (defaulted / clamped as above)
     * @return the matching candidate page, carrying the total match count
     * @throws ForemenApiException the first tripped canonical check: 400
     *         {@code error.project.member.role.not.assignable} / {@code error.project.member.block.invalid}
     *         / {@code error.project.member.candidate.page.invalid}; 404 {@code error.entity.not.found}
     */
    @Transactional(readOnly = true)
    public Page<Candidate> searchCandidates(Long projectId,
                                            String term,
                                            String role,
                                            String block,
                                            int page,
                                            Integer size) {
        // Step 3 — mandatory fields (400), in the Requirement 11 order: role, block, then pagination.
        if (role != null && !TeamComposition.isAssignableProjectRole(role)) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.role.not.assignable", role);
        }
        TeamBlock targetBlock = parseBlock(block);           // 400 error.project.member.block.invalid on a bad value
        String trimmedTerm = trimToNull(term);
        if (trimmedTerm != null && trimmedTerm.length() > MAX_CANDIDATE_TERM_LENGTH) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.candidate.page.invalid", "term");
        }
        if (page < 0) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.candidate.page.invalid", "page");
        }
        int requestedSize = size == null ? DEFAULT_CANDIDATE_PAGE_SIZE : size;
        if (requestedSize < 1) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.candidate.page.invalid", "size");
        }
        int effectiveSize = Math.min(requestedSize, MAX_CANDIDATE_PAGE_SIZE); // clamp, never reject (Req 11.7)

        // Step 4 — project existence / access (404, ADMIN bypass; indistinguishable for missing / out-of-scope).
        TeamRejectionChecklist.run(TeamRejectionContext.builder()
                .projectAccess(projectAccessCheck(projectId))
                .build());

        // Translate the block filter into the DAO's role predicate (D3/D6): WORKERS/CLIENTS map to a
        // single role; ADMIN_STAFF excludes WORKER and CLIENT; no block = no block predicate.
        String blockRole = null;
        boolean excludeWorkerClient = false;
        if (targetBlock == TeamBlock.WORKERS) {
            blockRole = "WORKER";
        } else if (targetBlock == TeamBlock.CLIENTS) {
            blockRole = "CLIENT";
        } else if (targetBlock == TeamBlock.ADMIN_STAFF) {
            excludeWorkerClient = true;
        }

        String likeTerm = trimmedTerm == null ? null : "%" + trimmedTerm.toLowerCase(Locale.ROOT) + "%";
        Pageable pageable = PageRequest.of(page, effectiveSize); // sort is the fixed query ORDER BY

        boolean russian = isRussianLocale();
        return userDao.searchCandidates(projectId, likeTerm, role, blockRole, excludeWorkerClient, pageable)
                .map(user -> toCandidate(user, russian));
    }

    /**
     * Parses the raw {@code block} query value into a {@link TeamBlock}, treating a {@code null}/blank
     * value as "not supplied" ({@code null} return) and rejecting any other non-matching token with
     * 400 {@code error.project.member.block.invalid} (Requirement 11 criterion 12). The match is on
     * the exact enum name ({@code ADMIN_STAFF}/{@code WORKERS}/{@code CLIENTS}), case-insensitively.
     */
    private static TeamBlock parseBlock(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        try {
            return TeamBlock.valueOf(block.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, "error.project.member.block.invalid", block);
        }
    }

    /**
     * Maps a candidate {@link UserEntity} to the {@link Candidate} read model (Requirement 11
     * criteria 4, 5). The Company_Role code drives the derived {@link TeamBlock} (D3/D6) and the
     * localized role name (ru → {@code nameRU}, else {@code namePL}, falling back to the code). The
     * WORKERS-only {@code workerKind} ({@code null} treated as {@code PERSON}) and {@code contactPerson}
     * are populated only when the candidate's block is {@link TeamBlock#WORKERS}; every other block
     * leaves them {@code null} so {@code @JsonInclude(NON_NULL)} omits them. No password, token, rate,
     * cost, worker type, NIP, or tag is read or exposed.
     */
    private static Candidate toCandidate(UserEntity user, boolean russian) {
        RoleEntity role = user.getRole();
        String roleCode = role == null ? null : role.getCode();
        String roleName = localizedRoleName(role, russian);
        TeamBlock block = TeamMemberOrdering.blockOf(roleCode);
        String status = user.getStatus() == null ? null : user.getStatus().name();

        WorkerKind workerKind = null;
        String contactPerson = null;
        if (block == TeamBlock.WORKERS) {
            workerKind = user.getWorkerKind() == null ? WorkerKind.PERSON : user.getWorkerKind();
            contactPerson = user.getContactPerson();
        }

        return new Candidate(
                user.getId(),
                user.getName(),
                user.getEmail(),
                status,
                roleCode,
                roleName,
                block,
                workerKind,
                contactPerson);
    }

    /** Trims the value, returning {@code null} for a {@code null} or blank-after-trim string. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Returns the project ids the given user belongs to in <b>any</b> Assignment_Status, in ascending
     * order (FOR-05-09 Req 3.6). For a non-ADMIN caller the result is restricted to the caller's
     * Accessible_Projects (the intersection with {@link #allowedProjectIds(Long)}), so the endpoint
     * never discloses project ids outside the caller's scope; an ADMIN caller bypasses the restriction.
     *
     * <p>The result is an empty list (never a 404) when the user has no memberships, when the user
     * does not exist, or when none of the user's projects are accessible to the caller.
     */
    @Transactional(readOnly = true)
    public List<Long> listProjects(Long userId) {
        List<Long> projectIds = projectMemberDao.findDistinctProjectIdsByUserIdOrderByProjectIdAsc(userId);
        if (isCallerAdmin()) {
            return projectIds; // ADMIN bypass: every project id the user belongs to.
        }
        Set<Long> accessible = callerAllowedProjectIds();
        if (accessible.isEmpty()) {
            return List.of();
        }
        return projectIds.stream()
                .filter(accessible::contains) // ascending order preserved by the DAO query
                .toList();
    }

    // --- Readiness gate (FOR-05-09 task 13.1, Requirement 20, design §"Readiness") ---

    /**
     * TASK 13.1 — the server-computed {@code team} readiness gate behind
     * {@code GET /api/project-members/readiness} (FOR-05-09 Requirement 20, design §"Readiness",
     * Property 9). Returns a {@link TeamReadiness} whose {@link TeamReadiness#key()} is the constant
     * {@value TeamReadiness#KEY}, whose {@link TeamReadiness#counts()} report — per
     * Assignable_Project_Role — the number of <strong>ACTIVE</strong> members of that role on the
     * project (0 when none, INACTIVE members excluded, D14), and whose {@link TeamReadiness#state()}
     * is {@link ReadinessState#DONE} iff the ACTIVE {@code FOREMAN} count is at least one and
     * {@link ReadinessState#BLOCKED} otherwise (Requirement 20 criteria 1, 2).
     *
     * <p><b>Access (Requirement 20 criteria 9, 10).</b> The {@code PROJECT_MEMBERS} READ permission is
     * enforced upstream by the {@code PermissionInterceptor} (the controller handler carries
     * {@code @PermissionOperation("READ")}), so a caller without READ is rejected with 403
     * {@code error.access.denied} before this method runs. This method then applies the FOR-03-04
     * project-scope gate ({@link #projectAccessCheck}): an ADMIN caller bypasses, and a non-accessible
     * or non-existent project is reported with 404 {@code error.entity.not.found}, byte-identical in
     * both cases.
     *
     * <p><b>Freshness (Requirement 20 criterion 10).</b> The counts are read fresh from the live
     * {@code project_members} rows inside a {@code readOnly} transaction via the per-role ACTIVE COUNT
     * query, so the gate reflects every committed assign / deactivate / reactivate / remove on the
     * next read; it mutates no row.
     *
     * @param projectId the project whose team readiness is computed (required)
     * @return the {@code team} readiness gate with its per-role ACTIVE counts and DONE/BLOCKED state
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is non-existent
     *                             or not an Accessible_Project for a non-ADMIN caller
     */
    @Transactional(readOnly = true)
    public TeamReadiness readiness(Long projectId) {
        // Step 4 — project existence / access (404, ADMIN bypass; indistinguishable for missing /
        // out-of-scope). The 403 (missing READ) is enforced upstream by the PermissionInterceptor.
        TeamRejectionChecklist.run(TeamRejectionContext.builder()
                .projectAccess(projectAccessCheck(projectId))
                .build());

        int manager = countActiveOfRole(projectId, MANAGER_ROLE_CODE);
        int foreman = countActiveOfRole(projectId, FOREMAN_ROLE_CODE);
        int estimator = countActiveOfRole(projectId, ESTIMATOR_ROLE_CODE);
        int worker = countActiveOfRole(projectId, WORKER_ROLE_CODE);
        int financier = countActiveOfRole(projectId, FINANCIER_ROLE_CODE);
        int client = countActiveOfRole(projectId, CLIENT_ROLE_CODE);

        // DONE iff at least one ACTIVE FOREMAN; BLOCKED otherwise (Req 20.1/20.2, Property 9).
        ReadinessState state = foreman >= 1 ? ReadinessState.DONE : ReadinessState.BLOCKED;

        TeamReadiness.Counts counts =
                new TeamReadiness.Counts(manager, foreman, estimator, worker, financier, client);
        return new TeamReadiness(TeamReadiness.KEY, state, counts);
    }

    /** The Assignable_Project_Role codes whose ACTIVE members the readiness gate counts (Req 20.2). */
    private static final String FOREMAN_ROLE_CODE = "FOREMAN";
    private static final String ESTIMATOR_ROLE_CODE = "ESTIMATOR";
    private static final String WORKER_ROLE_CODE = "WORKER";
    private static final String FINANCIER_ROLE_CODE = "FINANCIER";

    /**
     * The number of ACTIVE Project_Members of {@code roleCode} on {@code projectId}, read fresh via
     * {@link ProjectMemberDao#countByProjectIdAndProjectRoleCodeAndAssignmentStatus} (ACTIVE only, so
     * INACTIVE members never count — D14, Req 20.2). The COUNT is clamped to an {@code int} for the
     * {@link TeamReadiness.Counts} record; a project cannot realistically hold {@code > Integer.MAX}
     * members of one role, so the narrowing is safe.
     */
    private int countActiveOfRole(Long projectId, String roleCode) {
        return (int) projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                projectId, roleCode, AssignmentStatus.ACTIVE);
    }

    /** The caller's Accessible_Projects, or an empty set when the caller cannot be resolved. */
    private Set<Long> callerAllowedProjectIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Set.of();
        }
        Long callerId = parseUserId(auth.getName());
        if (callerId == null) {
            return Set.of();
        }
        Set<Long> allowed = allowedProjectIds(callerId);
        return allowed == null ? Set.of() : allowed;
    }

    /** True iff the authenticated caller carries the exact ADMIN authority ({@code ROLE_ADMIN}/{@code ADMIN}). */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (("ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE).equals(a)
                    || ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff the authenticated caller is an Internal_Attribute_Viewer (FOR-05-09 D12, task 7.6,
     * Requirement 4.10/4.11): ADMIN or any admin-staff role (MANAGER / FOREMAN / ESTIMATOR /
     * FINANCIER). False for WORKER / CLIENT and for an unauthenticated caller — those readers get the
     * enriched {@link TeamMemberView}'s Internal_Attributes omitted. This is the same gate the
     * inherited {@link ReadOnlyAdminService#maskAdminOnlyFields} applies to the flat read model; it is
     * reused here because the enriched view is an immutable record that is masked at build time rather
     * than by reflection. The admin-staff role codes are shared via
     * {@link ReadOnlyAdminService#ADMIN_STAFF_ROLE_CODES}.
     */
    private static boolean isCallerAdminStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a == null) {
                continue;
            }
            String code = a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a;
            if (ReadOnlyAdminService.ADMIN_STAFF_ROLE_CODES.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** Parses the principal name as a numeric user id; {@code null} on a blank/non-numeric name. */
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
}
