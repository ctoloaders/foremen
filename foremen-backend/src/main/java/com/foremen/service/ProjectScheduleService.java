package com.foremen.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.ScheduleProperties;
import com.foremen.controller.model.schedule.AutoCreateAlert;
import com.foremen.controller.model.schedule.AutoCreateRequest;
import com.foremen.controller.model.schedule.AutoCreateResult;
import com.foremen.controller.model.schedule.BarEntry;
import com.foremen.controller.model.schedule.SaveBarsRequest;
import com.foremen.controller.model.schedule.ScheduleBarView;
import com.foremen.controller.model.schedule.ScheduleLineView;
import com.foremen.controller.model.schedule.ScheduleReadiness;
import com.foremen.controller.model.schedule.ScheduleRowView;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.controller.model.schedule.ScheduleWorkerView;
import com.foremen.controller.model.schedule.UncoveredCategoryRef;
import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.ProjectScheduleServiceExtendedModel;
import com.foremen.service.model.ProjectScheduleServiceModel;
import com.foremen.service.model.mapper.ProjectScheduleServiceMapper;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.schedule.CoverageResolver;
import com.foremen.service.schedule.ScheduleCalculator;
import com.foremen.service.schedule.ScheduleReadinessState;
import com.foremen.service.schedule.ScheduleRowDerivation;
import com.foremen.service.schedule.WorkerScheduleLayout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-05-10 (Requirements 3.1, 3.2, 3.3; design §ProjectScheduleService): the project-scoped service
 * behind the planning Gantt (Harmonogram) {@code Schedule_API}. It is the single owner of the
 * schedule read model, bar persistence, auto-create, readiness, lifecycle lock, optimistic-lock
 * version, ABAC project scoping, and audit.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code SignableDocumentService} /
 * {@code OfferService}), it implements exactly one CRUD contract — {@link ProjectScopedService} —
 * supplies the standard CRUD plumbing ({@link #getDao()}, {@link #getMapper()},
 * {@link #getEntityManager()}, {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single
 * mandatory per-entity override {@link #getProjectIdPath()} &rarr; {@code "project.id"}
 * (entity-creation-rules step 4; Requirement 3.1 — the schedule resolves its project boundary through
 * its {@code @OneToOne project} FK), and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache}. This service overrides no CRUD method, {@code addRequiredQuery()}, or
 * {@code getProjectId()}.
 *
 * <p><b>Custom API surface.</b> The schedule is read and mutated through dedicated methods
 * ({@code getView} / {@code saveBars} / {@code autoCreate} / {@code readiness}, later tasks 5.2, 5.3,
 * 6.x), not through the generic by-id CRUD — exactly like {@code SignableDocumentService}. Those
 * methods gate project access up front with {@link #loadAccessibleProject(Long)} rather than the
 * inherited {@code assertProjectAccess(id)} (there is no schedule-entity id in the request; the
 * {@code projectId} request parameter is the subject). The flat service models and mapper exist only
 * to satisfy the generic CRUD contract.
 *
 * <p><b>Access/scoping skeleton (task 5.1).</b> This revision establishes the class, its
 * dependencies, the {@code @Service} wiring, the {@link ProjectScopedService} contract, and the
 * {@link #loadAccessibleProject(Long)} gate with 404 bodies identical for a missing and a
 * non-accessible project (Requirements 3.2, 3.3). The read ({@code getView}), writes
 * ({@code saveBars} / {@code autoCreate}), and readiness are added by subsequent tasks.
 */
@Service
@Transactional
public class ProjectScheduleService
        implements ProjectScopedService<ProjectScheduleServiceModel,
        ProjectScheduleServiceExtendedModel, ProjectScheduleEntity, Long> {

    /**
     * 404 for a missing <em>or</em> a non-accessible project. The same message code and body are used
     * for both outcomes so an out-of-scope project is indistinguishable from a non-existent one
     * (Requirements 3.2, 3.3), matching the {@code ProjectScopedService} access-denied convention.
     */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 409 raised when a write targets a schedule whose project is in a Schedule_Locked_Status (R10.2). */
    static final String SCHEDULE_LOCKED_MESSAGE = "error.schedule.locked";

    /**
     * 409 raised for a stale version, a lost optimistic-lock update, or a lost concurrent first write
     * (R6.4, R11.2, R11.3) — all three outcomes share the one conflict code.
     */
    static final String SCHEDULE_CONFLICT_MESSAGE = "error.schedule.conflict";

    /**
     * 400 raised when a save entry's {@code (startDay, durationDays)} pair is half-null, out of the
     * {@code [1, 3650]} bounds, or has a {@code Finish_Day} beyond 3650; the offending work category id
     * is passed as the single message parameter (R7.3).
     */
    static final String BAR_INVALID_MESSAGE = "error.schedule.bar.invalid";

    /**
     * 400 raised when a save entry targets a work category that is not a current {@code Schedule_Row}
     * of the project, or the same category appears more than once in the request; the offending
     * category id is the single message parameter (R7.4).
     */
    static final String CATEGORY_INVALID_MESSAGE = "error.schedule.category.invalid";

    /**
     * 400 raised when a save entry's {@code (projectMemberId, workCategoryId)} pairing is invalid: the
     * member is not an ACTIVE WORKER of the project that covers the category (and is not {@code null},
     * the allowed virtual {@code Uncovered_Category} bar). The offending category id is the single
     * message parameter (FOR-05-10b R3.5).
     */
    static final String ASSIGNMENT_INVALID_MESSAGE = "error.schedule.assignment.invalid";

    /**
     * The FOR-05-10 empty-crew error code, <b>retained for backward compatibility only</b>. In the
     * FOR-05-10b per-worker model {@link #autoCreate(Long, AutoCreateRequest)} no longer hard-fails
     * on an empty crew (R4.6): an empty crew lays every category out as an {@code Uncovered_Category}
     * virtual bar and is reported through the {@link AutoCreateAlert#crewEmpty()} flag instead. The
     * code and its message-bundle entry are kept so any other reference / historical audit still
     * resolves, but no code path raises it any longer.
     */
    static final String CREW_EMPTY_MESSAGE = "error.schedule.crew.empty";

    /**
     * 409 raised when {@link #autoCreate(Long, AutoCreateRequest)} is called for a project whose
     * estimate yields no {@code Schedule_Rows} (no estimate or no lines), so there is nothing to lay
     * out (R8.6).
     */
    static final String ROWS_EMPTY_MESSAGE = "error.schedule.rows.empty";

    /** Audit kind for a committed {@link #saveBars(Long, SaveBarsRequest)} write (task 6.3, R13). */
    static final String SAVE_BARS_AUDIT_KIND = "SAVE_BARS";

    /** Audit kind for a committed {@link #autoCreate(Long, AutoCreateRequest)} write (task 6.3, R13). */
    static final String AUTO_CREATE_AUDIT_KIND = "AUTO_CREATE";

    /** The project role code counted toward the Crew_Size (ACTIVE members only, FOR-05-10 Q4). */
    static final String WORKER_ROLE_CODE = "WORKER";

    /** The ABAC resource/operation whose READ grant marks a Money_Viewer (R5.3; changeset 135). */
    static final String ESTIMATE_RESOURCE = "ESTIMATE";
    static final String READ_OPERATION = "READ";

    /** The ABAC resource/operation whose grant lets a caller edit the schedule (R5.1 editable). */
    static final String WORK_SCHEDULE_RESOURCE = "WORK_SCHEDULE";
    static final String UPDATE_OPERATION = "UPDATE";

    /** The {@code ROLE_<code>} authority prefix used to read the caller's role code. */
    private static final String ROLE_PREFIX = "ROLE_";

    /** The project statuses in which the schedule may be edited (R10.2, Schedule_Editable_Status). */
    private static final Set<ProjectStatus> EDITABLE_STATUSES = Set.of(
            ProjectStatus.DRAFT,
            ProjectStatus.READY_TO_OFFER,
            ProjectStatus.OFFERED,
            ProjectStatus.APPROVED,
            ProjectStatus.ON_HOLD);

    private final ProjectScheduleDao projectScheduleDao;
    private final ProjectDao projectDao;
    private final EstimateLineDao estimateLineDao;
    private final ProjectMemberDao projectMemberDao;
    private final ScheduleProperties scheduleProperties;
    private final ProjectAccessCache projectAccessCache;
    private final ForemenPermissionEvaluator permissionEvaluator;
    private final ProjectScheduleServiceMapper serviceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    public ProjectScheduleService(ProjectScheduleDao projectScheduleDao,
                                  ProjectDao projectDao,
                                  EstimateLineDao estimateLineDao,
                                  ProjectMemberDao projectMemberDao,
                                  ScheduleProperties scheduleProperties,
                                  ProjectAccessCache projectAccessCache,
                                  ForemenPermissionEvaluator permissionEvaluator,
                                  ProjectScheduleServiceMapper serviceMapper,
                                  AuditLogDao auditLogDao,
                                  EntityManager entityManager) {
        this.projectScheduleDao = projectScheduleDao;
        this.projectDao = projectDao;
        this.estimateLineDao = estimateLineDao;
        this.projectMemberDao = projectMemberDao;
        this.scheduleProperties = scheduleProperties;
        this.projectAccessCache = projectAccessCache;
        this.permissionEvaluator = permissionEvaluator;
        this.serviceMapper = serviceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<ProjectScheduleEntity, Long> getDao() {
        return projectScheduleDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ProjectScheduleEntity, ProjectScheduleServiceModel,
            ProjectScheduleServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ProjectScheduleEntity> getDaoModelClass() {
        return ProjectScheduleEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override (entity-creation-rules step 4; Requirement 3.1). The
     * schedule resolves its project boundary through its {@code @OneToOne project} association, so the
     * project-id path is the dotted {@code "project.id"}.
     */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Project access gate (Requirements 3.2, 3.3) ---

    /**
     * Loads the managed {@link ProjectEntity} for {@code projectId}, asserting the caller may access
     * it, and is the single entry gate every custom {@code Schedule_API} method
     * ({@code getView} / {@code saveBars} / {@code autoCreate} / {@code readiness}) runs first.
     *
     * <p>It mirrors the inherited {@code ProjectScopedService.assertProjectAccess} decision at the
     * <b>project</b> grain (the request subject is the {@code projectId} parameter, not a schedule-row
     * id): an ADMIN authority bypasses the Accessible_Project gate; a non-ADMIN caller must have
     * {@code projectId} in {@link #allowedProjectIds(Long)}. Both a non-existent project and a project
     * outside the caller's scope are rejected with HTTP 404 {@code error.entity.not.found} carrying an
     * <b>identical</b> body, so an out-of-scope project is indistinguishable from a missing one
     * (Requirements 3.2, 3.3). No data is changed on rejection.
     *
     * <p>Order of the two checks is immaterial to the response (both 404 with the same body), but the
     * accessibility gate runs before the existence lookup for non-ADMIN callers so a non-member cannot
     * probe project existence via a timing/row-presence difference.
     *
     * @param projectId the project whose schedule is being read or mutated
     * @return the managed project entity (never {@code null})
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    @Transactional(readOnly = true)
    public ProjectEntity loadAccessibleProject(Long projectId) {
        if (!isCallerAdmin()) {
            Long userId = callerUserId();
            Set<Long> allowed = userId == null ? null : allowedProjectIds(userId);
            if (projectId == null || allowed == null || !allowed.contains(projectId)) {
                throw notFound(projectId);
            }
        }
        return projectDao.findById(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    // --- Lifecycle lock and optimistic-lock version (task 5.3, Requirements 3.5, 6.4, 10.x, 11.x) ---

    /**
     * Asserts the project is in a {@code Schedule_Editable_Status}, the lifecycle-lock gate of the
     * write flow (R10.1, R10.2, R10.3). A schedule may be mutated only while the project status is one
     * of {@code DRAFT}, {@code READY_TO_OFFER}, {@code OFFERED}, {@code APPROVED}, {@code ON_HOLD}
     * (the {@link #EDITABLE_STATUSES} set); in every other status
     * ({@code Schedule_Locked_Status}: {@code ACTIVE}, {@code COMPLETED}, {@code CANCELLED}) the
     * schedule is read-only and this throws 409 {@code error.schedule.locked}, changing no data
     * (R10.2). The lock applies to every caller, ADMIN included — a locked schedule cannot be edited
     * by anyone (R10.3), because the gate keys off the project status, not the caller's role.
     *
     * <p><b>Check order (R3.5).</b> Both {@code saveBars} and {@code autoCreate} (task 6.x) call this
     * <em>after</em> {@link #loadAccessibleProject(Long)} (so project existence / accessibility → 404
     * precedes the lock) and <em>before</em> {@link #assertVersion(ProjectScheduleEntity, Long)} (so
     * the lifecycle lock → 409 {@code error.schedule.locked} precedes the version conflict → 409
     * {@code error.schedule.conflict}), and before any business validation of Requirements 7–8. This
     * is the design §"R3.5 order": access (404) → status lock (409 locked) → version (409 conflict) →
     * business validation.
     *
     * @param project the project whose schedule is about to be mutated (never {@code null})
     * @throws ForemenApiException 409 {@code error.schedule.locked} when the project status is a
     *                             {@code Schedule_Locked_Status}
     */
    void assertEditable(ProjectEntity project) {
        if (!EDITABLE_STATUSES.contains(project.getStatus())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_LOCKED_MESSAGE);
        }
    }

    /**
     * Asserts the client-supplied {@code requestVersion} matches the stored {@code Schedule_Version},
     * the optimistic-lock gate of the write flow (R11.1, R11.2). Each write carries the version the
     * client last read; if it no longer equals the stored version the schedule was changed by another
     * caller in the meantime, and this throws 409 {@code error.schedule.conflict}, changing no data
     * (R11.2) so the client reloads and retries.
     *
     * <p>A schedule that does not yet exist reads with version 0 (R5.6); a first write therefore
     * passes {@code requestVersion == 0} and {@code schedule.getVersion()} is treated as 0 when the
     * entity (or its version column) is {@code null}. The concurrent-first-write and concurrent-update
     * races that slip past this application-level check are caught by the database and
     * {@link #mapWriteConflict(RuntimeException)} (R6.4, R11.3): the JPA {@code @Version} column
     * surfaces a lost update as {@link ObjectOptimisticLockingFailureException}, and the
     * {@code project_id} unique constraint surfaces a lost first insert as a
     * {@link DataIntegrityViolationException}; both map to the same 409 {@code error.schedule.conflict}.
     *
     * <p><b>Check order (R3.5).</b> Callers run this after {@link #assertEditable(ProjectEntity)}, so a
     * locked status (409 {@code error.schedule.locked}) always takes precedence over a stale version
     * (409 {@code error.schedule.conflict}), and before any business validation of Requirements 7–8.
     *
     * @param schedule       the stored schedule, or {@code null} when none exists yet (version 0)
     * @param requestVersion the {@code Schedule_Version} the client submitted (non-null per request
     *                       bean validation)
     * @throws ForemenApiException 409 {@code error.schedule.conflict} when the versions differ
     */
    void assertVersion(ProjectScheduleEntity schedule, Long requestVersion) {
        long stored = schedule == null || schedule.getVersion() == null ? 0L : schedule.getVersion();
        long requested = requestVersion == null ? -1L : requestVersion;
        if (stored != requested) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_CONFLICT_MESSAGE);
        }
    }

    /**
     * Maps a database-level write race to the same 409 {@code error.schedule.conflict} the
     * application-level {@link #assertVersion(ProjectScheduleEntity, Long)} check raises, so a lost
     * update never surfaces as a 5xx (R6.4, R11.3). Two races slip past the application check and are
     * caught here when the write is flushed inside the write transaction (task 6.x calls this around
     * the save/flush):
     * <ul>
     *   <li>a concurrent <em>update</em> of an existing schedule — the JPA {@code @Version} column on
     *       {@link ProjectScheduleEntity} makes the loser's flush throw
     *       {@link ObjectOptimisticLockingFailureException} (R11.3); and</li>
     *   <li>a concurrent <em>first write</em> for a project with no schedule row yet — the
     *       {@code project_id} UNIQUE constraint admits exactly one insert and the loser's flush throws
     *       {@link DataIntegrityViolationException} (R6.4), mirroring the FOR-05-09
     *       {@code uk_project_members_user_project} / FOR-02 {@code users.email} translation.</li>
     * </ul>
     * Any other {@link RuntimeException} is rethrown unchanged so unrelated failures keep their
     * natural handling.
     *
     * @param e the exception thrown while persisting / flushing a schedule write
     * @return never returns normally; always throws
     * @throws ForemenApiException 409 {@code error.schedule.conflict} for the two mapped races
     * @throws RuntimeException    the original exception when it is neither mapped race
     */
    ForemenApiException mapWriteConflict(RuntimeException e) {
        if (e instanceof ObjectOptimisticLockingFailureException
                || e instanceof DataIntegrityViolationException) {
            return new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_CONFLICT_MESSAGE);
        }
        throw e;
    }

    // --- Read the schedule (task 5.2, Requirements 4.3-4.5, 5.1-5.8) ---

    /**
     * Builds the {@code Schedule_View} of a project for a caller with {@code WORK_SCHEDULE} READ
     * (FOR-05-10 Requirement 5; the READ grant is enforced upstream by the
     * {@code PermissionInterceptor}). This is a pure read: it never creates a {@code project_schedules}
     * row, so a project that has never been written reads with {@link ScheduleView#version()} 0 and
     * every row unscheduled (R5.6).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Load the project and assert the caller may access it (404 for missing / out-of-scope,
     *       {@link #loadAccessibleProject(Long)}, R3.2, R3.3).</li>
     *   <li>Load the project's estimate lines with their work item &rarr; category and unit fetched,
     *       and derive the ordered {@code Schedule_Rows} via {@link ScheduleRowDerivation#deriveRows}
     *       (one row per distinct work category, {@code orderNo ASC NULLS LAST, id ASC}; empty when
     *       there is no estimate or no lines — R4.1-4.3, R4.7).</li>
     *   <li>Load the stored bars (if any) and index them by work category id, <em>skipping every
     *       Orphan_Bar</em> whose category is no longer a row (R4.5): orphans are not returned (and
     *       the next write purges them — task 6.x).</li>
     *   <li>Resolve the {@code Crew_Size} (ACTIVE WORKER members), the internal {@code Daily_Output_Rate}
     *       (config only, never returned), the Money_Viewer flag, and the {@code editable} flag
     *       (editable status AND the caller holds {@code WORK_SCHEDULE} UPDATE — R5.1).</li>
     *   <li>Map each row: localized category name (ru &rarr; pl &rarr; code, R5.7), {@code lineCount},
     *       {@code Suggested_Duration} (null when crew 0, R5.4), {@code categoryValue} only for a
     *       Money_Viewer (R5.3), the bar's {@code startDay}/{@code durationDays}/{@code finishDay}
     *       (all null when unscheduled), and the localized estimate lines.</li>
     *   <li>Compute the schedule {@code Finish_Day}, {@code finishDate}, and {@code exceedsProjectEnd}
     *       from the bars and the anchor/end dates (R5.1, R5.5), and set {@code currency} only for a
     *       Money_Viewer (R5.3).</li>
     * </ol>
     *
     * <p>No field of the returned view ever carries a man-days figure or the {@code Daily_Output_Rate}
     * (R5.4, R9.3) — the rate feeds {@link ScheduleCalculator#suggested} only.
     *
     * @param projectId the project whose schedule is read
     * @return the schedule view (HTTP 200 payload); never {@code null}
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    @Transactional(readOnly = true)
    public ScheduleView getView(Long projectId) {
        ProjectEntity project = loadAccessibleProject(projectId);

        // 2 — rows derived from the estimate (fetch-joined so there are no N+1 lazy loads).
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        List<ScheduleRowDerivation.RowSource> rowSources = ScheduleRowDerivation.deriveRows(lines);

        // 3 — the ACTIVE WORKER members (id + display name + their Member_Work_Categories). These back
        //     the per-worker bar labels (R7.1), the color legend (workers list), the covered flag, and
        //     the crewSize. Ordered by member id so the legend and covering-worker lists are stable.
        List<ProjectMemberEntity> workerMembers = activeWorkerMembers(projectId);
        int crewSize = workerMembers.size();
        Map<Long, String> workerNameById = new LinkedHashMap<>();
        for (ProjectMemberEntity member : workerMembers) {
            workerNameById.put(member.getId(), memberDisplayName(member));
        }

        // 4 — coverage of the estimate categories by those members (CoverageResolver, R4.1/4.2); the
        //     covered flag per row and the Uncovered_Category set come from here.
        List<CoverageResolver.WorkerMember> coverageInput = new ArrayList<>(workerMembers.size());
        for (ProjectMemberEntity member : workerMembers) {
            coverageInput.add(new CoverageResolver.WorkerMember(
                    member.getId(), memberCategoryIds(member)));
        }
        List<Long> rowCategoryIds = new ArrayList<>(rowSources.size());
        for (ScheduleRowDerivation.RowSource source : rowSources) {
            rowCategoryIds.add(source.category().getId());
        }
        CoverageResolver.Coverage coverage = CoverageResolver.resolve(rowCategoryIds, coverageInput);

        // 5 — stored bars indexed by (member id, category id); orphan bars (category no longer a row)
        //     are skipped by only reading a row's own category key. A null member id keys the
        //     Uncovered_Category virtual bar (D-uncovered).
        ProjectScheduleEntity schedule = projectScheduleDao.findByProjectId(projectId).orElse(null);
        long version = schedule == null || schedule.getVersion() == null ? 0L : schedule.getVersion();
        Map<Long, List<ProjectScheduleBarEntity>> barsByCategory = indexBarsByCategoryPerWorker(schedule);

        BigDecimal rate = scheduleProperties.dailyOutputPerWorker();
        boolean moneyViewer = isMoneyViewer();
        boolean russian = isRussianLocale();
        boolean editable = EDITABLE_STATUSES.contains(project.getStatus()) && callerCanUpdateSchedule();

        // 6 — map each row to its per-worker bars (incl. the virtual uncovered bar) and the covered
        //     flag; collect every laid-out bar's half-day finish for the schedule-wide finish.
        List<ScheduleRowView> rows = new ArrayList<>(rowSources.size());
        List<Integer> finishHalfDays = new ArrayList<>();
        for (ScheduleRowDerivation.RowSource source : rowSources) {
            WorkCategoryEntity category = source.category();
            Long categoryId = category.getId();
            boolean covered = coverage.covered(categoryId);

            List<ScheduleBarView> barViews = new ArrayList<>();
            for (ProjectScheduleBarEntity bar : barsByCategory.getOrDefault(categoryId, List.of())) {
                Integer startHalfDays = bar.getStartHalfDays();
                Integer durationHalfDays = bar.getDurationHalfDays();
                if (startHalfDays == null || durationHalfDays == null) {
                    continue; // defensive: a persisted bar always has both (CHECK >= 1)
                }
                Long memberId = bar.getProjectMember() == null ? null : bar.getProjectMember().getId();
                barViews.add(new ScheduleBarView(
                        memberId,
                        memberId == null ? null : workerNameById.get(memberId),
                        ScheduleCalculator.startDayFromHalfDays(startHalfDays),
                        ScheduleCalculator.durationDaysFromHalfDays(durationHalfDays),
                        ScheduleCalculator.finishDayFromHalfDays(startHalfDays, durationHalfDays)));
                finishHalfDays.add(ScheduleCalculator.finishHalfDay(startHalfDays, durationHalfDays));
            }

            rows.add(new ScheduleRowView(
                    categoryId,
                    category.getCode(),
                    category.getOrderNo(),
                    localizedCategoryName(category, russian),
                    source.lineCount(),
                    ScheduleCalculator.suggested(source.categoryValue(), rate, crewSize),
                    moneyViewer ? source.categoryValue() : null,
                    covered,
                    barViews,
                    toLineViews(source.lines(), russian)));
        }

        // 7 — the worker legend (one entry per ACTIVE WORKER member; color computed client-side).
        List<ScheduleWorkerView> workers = new ArrayList<>(workerMembers.size());
        for (ProjectMemberEntity member : workerMembers) {
            workers.add(new ScheduleWorkerView(member.getId(), memberDisplayName(member), null));
        }

        // 8 — schedule-wide finish (half-day grid), finishDate, exceedsProjectEnd, and the currency.
        LocalDate anchor = project.getStartDate();
        LocalDate endDate = project.getEndDate();
        Double scheduleFinish = ScheduleCalculator.scheduleFinishDayFromHalfDays(finishHalfDays);
        LocalDate finishDate = ScheduleCalculator.finishDateFromHalfDays(anchor, finishHalfDays);
        boolean exceedsProjectEnd =
                ScheduleCalculator.exceedsProjectEndHalfDays(anchor, endDate, finishHalfDays);
        String currency = moneyViewer ? estimateCurrencyCode(lines) : null;

        return new ScheduleView(
                projectId,
                anchor,
                endDate,
                editable,
                version,
                crewSize,
                currency,
                scheduleFinish,
                finishDate,
                exceedsProjectEnd,
                workers,
                rows);
    }

    /**
     * The ACTIVE WORKER members of a project, in ascending member-id order, with their
     * Member_Work_Categories reachable (FOR-05-10b R7.1, R4.1). These feed the crew size, the worker
     * legend, the per-worker bar labels, and the {@link CoverageResolver} coverage. The lazy
     * {@code workCategories} / {@code user} associations are touched inside the read transaction, so
     * no detached-access occurs.
     */
    private List<ProjectMemberEntity> activeWorkerMembers(Long projectId) {
        List<ProjectMemberEntity> members = new ArrayList<>();
        for (ProjectMemberEntity member : projectMemberDao.findByProjectId(projectId)) {
            if (member.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
                continue;
            }
            if (member.getProjectRole() == null
                    || !WORKER_ROLE_CODE.equals(member.getProjectRole().getCode())) {
                continue;
            }
            members.add(member);
        }
        members.sort(java.util.Comparator.comparing(
                ProjectMemberEntity::getId,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return members;
    }

    /** The display name of a member — its user's name, or {@code null} when the user is unresolved. */
    private static String memberDisplayName(ProjectMemberEntity member) {
        return member.getUser() == null ? null : member.getUser().getName();
    }

    /** The non-null work-category ids of a member's Member_Work_Categories (R4.1 coverage input). */
    private static Set<Long> memberCategoryIds(ProjectMemberEntity member) {
        Set<Long> ids = new HashSet<>();
        if (member.getWorkCategories() != null) {
            for (WorkCategoryEntity category : member.getWorkCategories()) {
                if (category != null && category.getId() != null) {
                    ids.add(category.getId());
                }
            }
        }
        return ids;
    }

    /**
     * Indexes a schedule's stored bars by work category id into the ordered list of that category's
     * per-worker bars (FOR-05-10b, D-bar-key). A bar whose category id cannot be resolved is skipped
     * defensively; the <em>orphan</em> decision (reading only a current row's category key) is left
     * to the caller, exactly as the FOR-05-10 {@link #indexBarsByCategory(ProjectScheduleEntity)}
     * did. Within a category the bars keep the entity-collection order; the covered bars are then
     * presented in that order and the virtual uncovered bar (null member) sits among them.
     */
    private static Map<Long, List<ProjectScheduleBarEntity>> indexBarsByCategoryPerWorker(
            ProjectScheduleEntity schedule) {
        if (schedule == null || schedule.getBars() == null || schedule.getBars().isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ProjectScheduleBarEntity>> byCategory = new HashMap<>();
        for (ProjectScheduleBarEntity bar : schedule.getBars()) {
            WorkCategoryEntity category = bar.getWorkCategory();
            if (category != null && category.getId() != null) {
                byCategory.computeIfAbsent(category.getId(), c -> new ArrayList<>()).add(bar);
            }
        }
        return byCategory;
    }

    // --- Save bars (task 6.1, Requirements 4.6, 6.3, 6.4, 7.1-7.6) ---

    /**
     * Applies a {@code Schedule_Draft} save to a project's bars (FOR-05-10 Requirement 7; the
     * {@code WORK_SCHEDULE} UPDATE grant is enforced upstream by the {@code PermissionInterceptor}).
     * Each {@link BarEntry} either sets / replaces (both values present) or clears (both {@code null})
     * the bar of its {@code Schedule_Row}; rows not listed are left untouched (R7.1). The whole save
     * runs in one transaction.
     *
     * <p><b>Check order (R3.5).</b>
     * <ol>
     *   <li>{@link #loadAccessibleProject(Long)} — 404 for a missing / out-of-scope project
     *       (R3.2, R3.3).</li>
     *   <li>{@link #assertEditable(ProjectEntity)} — 409 {@code error.schedule.locked} in a
     *       {@code Schedule_Locked_Status} (R10.2).</li>
     *   <li>{@link #assertVersion(ProjectScheduleEntity, Long)} — 409 {@code error.schedule.conflict}
     *       on a stale version (R11.2).</li>
     *   <li>Business validation of every entry (below): 400 {@code error.schedule.bar.invalid} (R7.3)
     *       and 400 {@code error.schedule.category.invalid} (R7.4), none of which changes any bar.</li>
     * </ol>
     *
     * <p><b>Entry validation (no bar is changed if any entry fails).</b> First the categories are
     * checked against the current {@code Schedule_Rows}: a {@code workCategoryId} that is not a
     * current row, or any category appearing more than once in the request, yields 400
     * {@code error.schedule.category.invalid} naming the offending id (R7.4). Then each entry's
     * {@code (startDay, durationDays)} is checked with {@link ScheduleCalculator#validateBar}: a
     * half-null pair, an out-of-{@code [1, 3650]} value, or a {@code Finish_Day} beyond 3650 yields
     * 400 {@code error.schedule.bar.invalid} naming the offending id (R7.3).
     *
     * <p><b>Mutation.</b> The schedule row is loaded, or created on the first write (R6.4). For each
     * entry: both values present &rarr; the bar is created or its period replaced; both {@code null}
     * &rarr; the bar is removed. Every {@code Orphan_Bar} (a stored bar whose category is no longer a
     * current {@code Schedule_Row}) is purged in the same transaction (R4.6). A category entity for a
     * newly created bar is taken from the derived {@code Schedule_Row} (already attached from the
     * estimate-line fetch join), so no extra reference load is needed.
     *
     * <p><b>No-op detection (R7.5).</b> If, after applying the listed entries and purging orphans, the
     * resulting bar set is byte-for-byte the stored one (same categories, same {@code startDay} /
     * {@code durationDays}, no orphan to purge, and the schedule already exists), nothing is persisted:
     * the version is kept and no audit row is written. Otherwise the schedule is forced to increment
     * its {@code @Version} exactly once ({@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}) even when
     * only child bar rows changed (R7.1, R11), the write is flushed inside this transaction with
     * database-race mapping ({@link #mapWriteConflict(RuntimeException)}, R6.4/R11.3), and a single
     * audit row is written (task 6.3 — see {@link #writeSaveBarsAudit}).
     *
     * @param projectId the project whose bars are saved
     * @param request   the version the client last read and the list of changed rows
     * @return the fresh {@code Schedule_View} after the save (HTTP 200 payload); never {@code null}
     * @throws ForemenApiException 404 {@code error.entity.not.found} (R3.2/3.3), 409
     *                             {@code error.schedule.locked} (R10.2), 409
     *                             {@code error.schedule.conflict} (R11.2/3), 400
     *                             {@code error.schedule.bar.invalid} (R7.3), or 400
     *                             {@code error.schedule.category.invalid} (R7.4)
     */
    @Transactional
    public ScheduleView saveBars(Long projectId, SaveBarsRequest request) {
        ProjectEntity project = loadAccessibleProject(projectId);     // 1 — 404
        assertEditable(project);                                      // 2 — 409 locked

        ProjectScheduleEntity schedule = projectScheduleDao.findByProjectId(projectId).orElse(null);
        assertVersion(schedule, request.version());                   // 3 — 409 conflict

        // Current Schedule_Rows (orphan categories excluded by construction) keyed by category id, in
        // row order, so the category entity for a brand-new bar comes from the attached derivation.
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        Map<Long, WorkCategoryEntity> currentRows = currentRowCategories(lines);

        // The ACTIVE WORKER members of the project, indexed by id, with the categories each covers.
        // These back the (member, category) pairing validation (R3.5, error.schedule.assignment.invalid)
        // and provide the attached member entity for a brand-new bar's FK.
        List<ProjectMemberEntity> workerMembers = activeWorkerMembers(projectId);
        Map<Long, ProjectMemberEntity> memberById = new LinkedHashMap<>();
        Map<Long, Set<Long>> coveredCategoriesByMember = new HashMap<>();
        for (ProjectMemberEntity member : workerMembers) {
            memberById.put(member.getId(), member);
            coveredCategoriesByMember.put(member.getId(), memberCategoryIds(member));
        }

        // 4 — business validation of every entry; nothing is mutated until all entries pass. The
        //     category / pairing checks (R3.5) run first, then the grid/horizon bounds (R3.4).
        validateEntries(request.bars(), currentRows.keySet(), coveredCategoriesByMember);

        // Desired final bar state = stored bars of current rows (orphans dropped) keyed by
        // (member, category), with the listed entries applied. A null-pair entry clears that bar;
        // a value entry sets/replaces it.
        Map<BarKey, ProjectScheduleBarEntity> storedByKey = indexBarsByKey(schedule, currentRows.keySet());
        Map<BarKey, int[]> desired = computeDesiredBars(storedByKey, request.bars());

        // No-op (R7.5): the schedule exists, the desired set equals the stored current-row set, and
        // there is no Orphan_Bar to purge. Nothing is written; the version and audit are untouched.
        if (schedule != null
                && !hasOrphan(schedule, currentRows.keySet())
                && isNoChange(storedByKey, desired)) {
            return getView(projectId);
        }

        // Capture the BEFORE snapshot of the stored bars (current rows, in row order) BEFORE applyBars
        // mutates the owned collection; empty on the first write (no prior schedule / bars), R13.3.
        String before = barsSnapshot(currentRows, storedByKey);

        // Mutation branch — create the schedule on the first write (R6.4).
        boolean firstWrite = schedule == null;
        if (firstWrite) {
            schedule = new ProjectScheduleEntity();
            schedule.setProject(project);
        }

        applyBars(schedule, currentRows, memberById, desired);        // upsert / clear + purge orphans

        // Force exactly one @Version increment even when only child bar rows changed (R3.5, R9.2).
        entityManager.persist(schedule);
        entityManager.lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        try {
            entityManager.flush();                                    // surface DB races here (R6.4/11.3)
        } catch (RuntimeException e) {
            throw mapWriteConflict(e);
        }

        // One SAVE_BARS audit row per committed changing write (R9.2): the after-snapshot is the
        // persisted bar set, in row order; rejected / no-op saves never reach this point.
        String after = barsSnapshot(currentRows, indexBarsByKey(schedule, currentRows.keySet()));
        writeScheduleAudit(schedule, SAVE_BARS_AUDIT_KIND, before, after);

        return getView(projectId);
    }

    /**
     * The current {@code Schedule_Row} categories of a project keyed by category id, in row order
     * ({@code orderNo ASC NULLS LAST, id ASC}, R4.2). Built from the derived rows so each value is the
     * {@link WorkCategoryEntity} already attached from the estimate-line fetch join — usable directly
     * as the FK of a newly created bar without an extra reference load.
     */
    private static Map<Long, WorkCategoryEntity> currentRowCategories(List<EstimateLineEntity> lines) {
        Map<Long, WorkCategoryEntity> byId = new LinkedHashMap<>();
        for (ScheduleRowDerivation.RowSource source : ScheduleRowDerivation.deriveRows(lines)) {
            WorkCategoryEntity category = source.category();
            if (category != null && category.getId() != null) {
                byId.put(category.getId(), category);
            }
        }
        return byId;
    }

    /**
     * Validates every save entry against the business rules, changing no bar: unknown or duplicated
     * categories first (R7.4), then the per-entry bounds / half-null rule (R7.3). The category check
     * precedes the bounds check so a save that both targets a non-row and carries a bad period reports
     * the category problem, matching the design error-table order.
     *
     * @param entries           the request entries (never {@code null}; may be empty)
     * @param currentCategoryIds the ids of the current {@code Schedule_Rows}
     * @throws ForemenApiException 400 {@code error.schedule.category.invalid} for an unknown or
     *                             duplicated category (R7.4), or 400 {@code error.schedule.bar.invalid}
     *                             for a bad period (R7.3), each naming the offending category id
     */
    private static void validateEntries(
            List<BarEntry> entries,
            Set<Long> currentCategoryIds,
            Map<Long, Set<Long>> coveredCategoriesByMember) {
        Set<BarKey> seen = new HashSet<>();
        for (BarEntry entry : entries) {
            Long categoryId = entry.workCategoryId();
            // The category must be a current Schedule_Row, and each (member, category) pair at most
            // once in the request (R3.5, error.schedule.category.invalid).
            if (categoryId == null
                    || !currentCategoryIds.contains(categoryId)
                    || !seen.add(new BarKey(entry.projectMemberId(), categoryId))) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, CATEGORY_INVALID_MESSAGE, categoryId);
            }
            // The (member, category) pairing must be valid: a null member is the allowed virtual
            // uncovered bar; a non-null member must be an ACTIVE WORKER of the project that covers the
            // category (R3.5, error.schedule.assignment.invalid).
            Long memberId = entry.projectMemberId();
            if (memberId != null) {
                Set<Long> covered = coveredCategoriesByMember.get(memberId);
                if (covered == null || !covered.contains(categoryId)) {
                    throw new ForemenApiException(
                            HttpStatus.BAD_REQUEST, ASSIGNMENT_INVALID_MESSAGE, categoryId);
                }
            }
        }
        // Grid / horizon bounds for every value entry (R3.4): the day values must be on the 0.5 grid
        // and the half-day finish must stay within the horizon, else error.schedule.bar.invalid.
        for (BarEntry entry : entries) {
            Integer startHalfDays = ScheduleCalculator.halfDaysFromDay(entry.startDay());
            Integer durationHalfDays = ScheduleCalculator.halfDaysFromDay(entry.durationDays());
            boolean bothDaysNull = entry.startDay() == null && entry.durationDays() == null;
            // A half-null day pair or an off-grid value is already an invalid bar; otherwise defer to
            // the half-day validateBar for the >= 1 / horizon rule.
            boolean offGrid = !bothDaysNull
                    && ((entry.startDay() != null && startHalfDays == null)
                        || (entry.durationDays() != null && durationHalfDays == null));
            if (offGrid || !ScheduleCalculator.validateBar(startHalfDays, durationHalfDays)) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, BAR_INVALID_MESSAGE, entry.workCategoryId());
            }
        }
    }

    /**
     * Computes the desired final bar set as a map of {@link BarKey} &rarr;
     * {@code [startHalfDays, durationHalfDays]} for scheduled bars only. It starts from the stored
     * bars of <em>current rows</em> (orphans already dropped by {@link #indexBarsByKey}), then applies
     * the listed entries: a value entry sets / replaces the pair (converting the 0.5-grid day values
     * to half-days), a both-null entry removes that {@code (member, category)} bar. Unlisted
     * {@code (member, category)} bars keep their stored period.
     */
    private static Map<BarKey, int[]> computeDesiredBars(
            Map<BarKey, ProjectScheduleBarEntity> storedByKey,
            List<BarEntry> entries) {
        Map<BarKey, int[]> desired = new HashMap<>();
        for (Map.Entry<BarKey, ProjectScheduleBarEntity> e : storedByKey.entrySet()) {
            ProjectScheduleBarEntity bar = e.getValue();
            if (bar.getStartHalfDays() != null && bar.getDurationHalfDays() != null) {
                desired.put(e.getKey(), new int[] {bar.getStartHalfDays(), bar.getDurationHalfDays()});
            }
        }
        for (BarEntry entry : entries) {
            BarKey key = new BarKey(entry.projectMemberId(), entry.workCategoryId());
            if (entry.startDay() == null && entry.durationDays() == null) {
                desired.remove(key);
            } else {
                desired.put(key, new int[] {
                        ScheduleCalculator.halfDaysFromDay(entry.startDay()),
                        ScheduleCalculator.halfDaysFromDay(entry.durationDays())});
            }
        }
        return desired;
    }

    /** Whether any stored bar is an {@code Orphan_Bar} (its category is no longer a current row). */
    private static boolean hasOrphan(ProjectScheduleEntity schedule, Set<Long> currentCategoryIds) {
        if (schedule == null || schedule.getBars() == null) {
            return false;
        }
        for (ProjectScheduleBarEntity bar : schedule.getBars()) {
            WorkCategoryEntity category = bar.getWorkCategory();
            Long categoryId = category == null ? null : category.getId();
            if (categoryId == null || !currentCategoryIds.contains(categoryId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the desired bar set equals the stored current-row bar set — the no-op test (R7.5
     * carried over). Equal means the same {@code (member, category)} bars with identical
     * {@code startHalfDays} / {@code durationHalfDays}. Only current-row stored bars are compared
     * (orphans are handled separately by {@link #hasOrphan}).
     */
    private static boolean isNoChange(
            Map<BarKey, ProjectScheduleBarEntity> storedByKey,
            Map<BarKey, int[]> desired) {
        Map<BarKey, int[]> storedCurrent = new HashMap<>();
        for (Map.Entry<BarKey, ProjectScheduleBarEntity> e : storedByKey.entrySet()) {
            ProjectScheduleBarEntity bar = e.getValue();
            if (bar.getStartHalfDays() != null && bar.getDurationHalfDays() != null) {
                storedCurrent.put(e.getKey(), new int[] {bar.getStartHalfDays(), bar.getDurationHalfDays()});
            }
        }
        if (storedCurrent.size() != desired.size()) {
            return false;
        }
        for (Map.Entry<BarKey, int[]> e : desired.entrySet()) {
            int[] stored = storedCurrent.get(e.getKey());
            if (stored == null || stored[0] != e.getValue()[0] || stored[1] != e.getValue()[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mutates the schedule's owned bar collection to match {@code desired} (keyed per
     * {@code (member, category)}) and purges every {@code Orphan_Bar}, relying on
     * {@code cascade = ALL} + {@code orphanRemoval = true}:
     * <ul>
     *   <li>a bar whose {@code (member, category)} key is not in {@code desired} (cleared, or an
     *       orphan) is removed from the collection, which deletes its row;</li>
     *   <li>a bar whose key is in {@code desired} has its half-day period updated in place;</li>
     *   <li>a desired key with no existing bar gets a new {@link ProjectScheduleBarEntity} whose
     *       {@code workCategory} is the attached current-row entity and whose {@code projectMember} is
     *       the attached ACTIVE WORKER member (or {@code null} for the virtual uncovered bar).</li>
     * </ul>
     */
    private static void applyBars(
            ProjectScheduleEntity schedule,
            Map<Long, WorkCategoryEntity> currentRows,
            Map<Long, ProjectMemberEntity> memberById,
            Map<BarKey, int[]> desired) {
        List<ProjectScheduleBarEntity> bars = schedule.getBars();

        // Update or remove existing bars; track which desired keys already have a bar.
        Set<BarKey> existing = new HashSet<>();
        Iterator<ProjectScheduleBarEntity> it = bars.iterator();
        while (it.hasNext()) {
            ProjectScheduleBarEntity bar = it.next();
            WorkCategoryEntity category = bar.getWorkCategory();
            Long categoryId = category == null ? null : category.getId();
            Long memberId = bar.getProjectMember() == null ? null : bar.getProjectMember().getId();
            BarKey key = categoryId == null ? null : new BarKey(memberId, categoryId);
            int[] target = key == null ? null : desired.get(key);
            if (target == null) {
                it.remove();                                          // cleared bar or Orphan_Bar
            } else {
                bar.setStartHalfDays(target[0]);
                bar.setDurationHalfDays(target[1]);
                existing.add(key);
            }
        }

        // Add bars for desired keys that have none yet.
        for (Map.Entry<BarKey, int[]> e : desired.entrySet()) {
            BarKey key = e.getKey();
            if (existing.contains(key)) {
                continue;
            }
            ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
            bar.setSchedule(schedule);
            bar.setWorkCategory(currentRows.get(key.categoryId()));
            bar.setProjectMember(key.memberId() == null ? null : memberById.get(key.memberId()));
            bar.setStartHalfDays(e.getValue()[0]);
            bar.setDurationHalfDays(e.getValue()[1]);
            bars.add(bar);
        }
    }

    /**
     * The composite key of a per-worker bar (FOR-05-10b D-bar-key): the covering worker's
     * project-member id (or {@code null} for the virtual {@code Uncovered_Category} bar) paired with
     * the work-category id. Equality on both components keys the stored-bar index, the desired set,
     * and the no-op comparison.
     */
    private record BarKey(Long memberId, Long categoryId) {}

    /**
     * Indexes a schedule's stored bars by their {@code (member, category)} {@link BarKey}, keeping
     * only bars whose category is a current {@code Schedule_Row} ({@code currentCategoryIds}); an
     * {@code Orphan_Bar} (category no longer a row) is excluded, exactly as {@code getView} skips it.
     * A bar whose category id cannot be resolved is skipped defensively.
     */
    private static Map<BarKey, ProjectScheduleBarEntity> indexBarsByKey(
            ProjectScheduleEntity schedule, Set<Long> currentCategoryIds) {
        if (schedule == null || schedule.getBars() == null || schedule.getBars().isEmpty()) {
            return Map.of();
        }
        Map<BarKey, ProjectScheduleBarEntity> byKey = new LinkedHashMap<>();
        for (ProjectScheduleBarEntity bar : schedule.getBars()) {
            WorkCategoryEntity category = bar.getWorkCategory();
            Long categoryId = category == null ? null : category.getId();
            if (categoryId == null || !currentCategoryIds.contains(categoryId)) {
                continue; // Orphan_Bar / unresolved — excluded (R4.5)
            }
            Long memberId = bar.getProjectMember() == null ? null : bar.getProjectMember().getId();
            byKey.put(new BarKey(memberId, categoryId), bar);
        }
        return byKey;
    }



    // --- Auto-create (task 4.3, Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 9.2) ---

    /**
     * Rebuilds a project's bars from scratch into a <em>per-worker</em> plan (FOR-05-10b Requirement
     * 4; the {@code WORK_SCHEDULE} UPDATE grant is enforced upstream by the
     * {@code PermissionInterceptor}). For every current {@code Schedule_Row} it finds the row's
     * {@code Covering_Worker}s (via {@link CoverageResolver}), splits the category's workload equally
     * and in parallel among them, and lays each worker's bars sequentially along the worker's own
     * timeline (via {@link WorkerScheduleLayout}). A category with no covering worker is laid out as a
     * single virtual {@code Uncovered_Category} bar (null member) and reported in the returned alert
     * (R4.2, D-uncovered). The whole operation <em>replaces</em> the plan and runs in one transaction,
     * incrementing the {@code Schedule_Version} exactly once (R4.7).
     *
     * <p><b>Check order (R3.5).</b> The same order as {@link #saveBars(Long, SaveBarsRequest)}:
     * <ol>
     *   <li>{@link #loadAccessibleProject(Long)} — 404 for a missing / out-of-scope project
     *       (R3.2, R3.3).</li>
     *   <li>{@link #assertEditable(ProjectEntity)} — 409 {@code error.schedule.locked} in a
     *       {@code Schedule_Locked_Status} (R10.2).</li>
     *   <li>{@link #assertVersion(ProjectScheduleEntity, Long)} — 409 {@code error.schedule.conflict}
     *       on a stale version (R11.2).</li>
     *   <li>Business validation (below): 409 {@code error.schedule.rows.empty} when the estimate
     *       yields no {@code Schedule_Rows} (R4.7), and 409 {@code error.schedule.too.long} when the
     *       laid-out plan would run past the 3650-day horizon (R4.4) — both raised before any bar is
     *       changed. There is <b>no</b> empty-crew 409: an empty crew is a valid outcome reported via
     *       the alert (R4.6, superseding the FOR-05-10 {@code error.schedule.crew.empty}).</li>
     * </ol>
     *
     * <p><b>Layout and mutation.</b> Each row's workload is {@code categoryHalfDays =
     * max(1, 2 × ScheduleCalculator.duration(categoryValue, rate, 1))} — the per-worker-day FOR-05-10
     * formula at half-day resolution, computed against the global internal {@code Daily_Output_Rate}
     * and a single-worker denominator (the per-category worker split is applied by the layout, not by
     * the duration). {@link WorkerScheduleLayout#autoLayout} then produces the per-worker
     * {@code Worker_Bar}s (equal parallel split, per-worker sequencing, 1-day disjoint-neighbor
     * overlap), throwing {@code error.schedule.too.long} on an overrun (R4.4). The resulting bars
     * <em>replace</em> all stored bars via {@link #applyBars} (which also purges orphans and stale
     * bars), the parent is forced to bump its {@code @Version} once
     * ({@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}) even though only child bars change, and the
     * write is flushed with database-race mapping ({@link #mapWriteConflict(RuntimeException)},
     * R6.4/R11.3). A single {@code AUTO_CREATE} audit row is written on the committed write (R4.7).
     *
     * <p>Unlike {@code saveBars}, auto-create has no no-op short-circuit: a successful call always
     * replaces the plan and therefore always increments the version once. The rate is read only from
     * {@link ScheduleProperties}; it is never accepted from the request and never returned (R9.2).
     *
     * @param projectId the project whose schedule is auto-created
     * @param request   the version the client last read (the request carries no rate — R9.2)
     * @return the fresh {@link AutoCreateResult} — the {@code Schedule_View} plus the uncovered /
     *         empty-crew alert (HTTP 200 payload); never {@code null}
     * @throws ForemenApiException 404 {@code error.entity.not.found} (R3.2/3.3), 409
     *                             {@code error.schedule.locked} (R10.2), 409
     *                             {@code error.schedule.conflict} (R11.2/3), 409
     *                             {@code error.schedule.rows.empty} (R4.7), or 409
     *                             {@code error.schedule.too.long} (R4.4)
     */
    @Transactional
    public AutoCreateResult autoCreate(Long projectId, AutoCreateRequest request) {
        ProjectEntity project = loadAccessibleProject(projectId);     // 1 — 404
        assertEditable(project);                                      // 2 — 409 locked

        ProjectScheduleEntity schedule = projectScheduleDao.findByProjectId(projectId).orElse(null);
        assertVersion(schedule, request.version());                   // 3 — 409 conflict

        // 4a — the estimate must yield at least one Schedule_Row (R4.7). An empty crew is NOT a
        //      rejection here: it is laid out as all-uncovered and reported in the alert (R4.6).
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        List<ScheduleRowDerivation.RowSource> rowSources = ScheduleRowDerivation.deriveRows(lines);
        if (rowSources.isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ROWS_EMPTY_MESSAGE);
        }

        // 4b — resolve the coverage of the rows by the project's ACTIVE WORKER members, in row order
        //      (CoverageResolver, R4.1/R4.2). An empty crew yields every category uncovered (R4.6).
        List<ProjectMemberEntity> workerMembers = activeWorkerMembers(projectId);
        Map<Long, ProjectMemberEntity> memberById = new LinkedHashMap<>();
        List<CoverageResolver.WorkerMember> coverageInput = new ArrayList<>(workerMembers.size());
        for (ProjectMemberEntity member : workerMembers) {
            memberById.put(member.getId(), member);
            coverageInput.add(new CoverageResolver.WorkerMember(
                    member.getId(), memberCategoryIds(member)));
        }
        Map<Long, WorkCategoryEntity> currentRows = currentRowCategories(lines);
        CoverageResolver.Coverage coverage =
                CoverageResolver.resolve(currentRows.keySet(), coverageInput);

        // 4c — the per-category half-day workload (R4.1): categoryHalfDays = max(1, 2 × duration) with
        //      duration computed per worker-day against the internal rate (crew denominator 1; the
        //      per-category split is the layout's job). Categories in Schedule_Row order (R4.3).
        BigDecimal rate = scheduleProperties.dailyOutputPerWorker();
        List<WorkerScheduleLayout.CategoryWork> categoryWork = new ArrayList<>(rowSources.size());
        for (ScheduleRowDerivation.RowSource source : rowSources) {
            int categoryHalfDays = Math.max(1, 2 * ScheduleCalculator.duration(
                    source.categoryValue(), rate, 1));
            categoryWork.add(new WorkerScheduleLayout.CategoryWork(
                    source.category().getId(), categoryHalfDays));
        }

        // 4d — lay the per-worker bars out (R4.1-R4.4); throws 409 error.schedule.too.long on an
        //      overrun past the 3650-day (7300 half-day) horizon (R4.4). Still nothing mutated.
        WorkerScheduleLayout.LayoutResult layout =
                WorkerScheduleLayout.autoLayout(categoryWork, coverage);

        // Replace-all desired set (R4.1): exactly the laid-out per-worker bars, keyed by
        // (member, category). A null member is the virtual Uncovered_Category bar (D-uncovered).
        Map<BarKey, int[]> desired = new HashMap<>();
        for (WorkerScheduleLayout.WorkerBarLayout bar : layout.bars()) {
            desired.put(new BarKey(bar.projectMemberId(), bar.workCategoryId()),
                    new int[] {bar.startHalfDays(), bar.durationHalfDays()});
        }

        // Capture the BEFORE snapshot of the stored bars (current rows, in row order) BEFORE applyBars
        // rewrites the owned collection; empty on the first auto-create (no prior schedule), R13.3.
        String before = barsSnapshot(currentRows, indexBarsByKey(schedule, currentRows.keySet()));

        // Mutation branch — create the schedule on the first write (R6.4). Auto-create always writes
        // (no no-op short-circuit): a successful layout replaces the plan and bumps the version once.
        if (schedule == null) {
            schedule = new ProjectScheduleEntity();
            schedule.setProject(project);
        }

        applyBars(schedule, currentRows, memberById, desired);        // replace all bars + purge orphans

        // Force exactly one @Version increment even when only child bar rows changed (R4.7, R11).
        entityManager.persist(schedule);
        entityManager.lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        try {
            entityManager.flush();                                    // surface DB races here (R6.4/11.3)
        } catch (RuntimeException e) {
            throw mapWriteConflict(e);
        }

        // One AUTO_CREATE audit row per committed auto-create (R4.7): the after-snapshot is the
        // laid-out bar set, in row order; a rejected auto-create never reaches this point (R13.4).
        String after = barsSnapshot(currentRows, indexBarsByKey(schedule, currentRows.keySet()));
        writeScheduleAudit(schedule, AUTO_CREATE_AUDIT_KIND, before, after);

        // Build the alert (R4.5): the empty-crew flag + one UncoveredCategoryRef per
        // Uncovered_Category (id + localized name, ru → pl → code), in Schedule_Row order.
        AutoCreateAlert alert = buildAutoCreateAlert(layout, currentRows);
        return new AutoCreateResult(getView(projectId), alert);
    }

    /**
     * Builds the {@link AutoCreateAlert} from a {@link WorkerScheduleLayout.LayoutResult} (R4.5): the
     * {@code crewEmpty} flag straight from the layout, and one {@link UncoveredCategoryRef} per
     * {@code Uncovered_Category} carrying the category's localized name (ru &rarr; pl &rarr; code
     * fallback, R4.5) resolved from the current-row category entities. The refs keep the layout's
     * {@code Schedule_Row} order.
     */
    private AutoCreateAlert buildAutoCreateAlert(
            WorkerScheduleLayout.LayoutResult layout,
            Map<Long, WorkCategoryEntity> currentRows) {
        boolean russian = isRussianLocale();
        List<UncoveredCategoryRef> uncovered = new ArrayList<>(layout.uncoveredCategoryIds().size());
        for (Long categoryId : layout.uncoveredCategoryIds()) {
            WorkCategoryEntity category = currentRows.get(categoryId);
            String name = category == null ? null : localizedCategoryName(category, russian);
            uncovered.add(new UncoveredCategoryRef(categoryId, name));
        }
        return new AutoCreateAlert(layout.crewEmpty(), uncovered);
    }

    // --- Readiness (task 6.4, Requirements 12.1, 12.2) ---

    /**
     * Computes the {@code schedule} readiness gate of a project for a caller with
     * {@code WORK_SCHEDULE} READ (FOR-05-10 Requirement 12; the READ grant is enforced upstream by the
     * {@code PermissionInterceptor}). This is a pure read: it never creates a
     * {@code project_schedules} row, so a project that has never been written reports
     * {@code rowCount} from its estimate, {@code scheduledCount} 0, and state {@code BLOCKED} (R12.1).
     *
     * <p>Algorithm (design §readiness):
     * <ol>
     *   <li>Load the project and assert the caller may access it (404 for missing / out-of-scope,
     *       {@link #loadAccessibleProject(Long)}, R3.2, R3.3).</li>
     *   <li>Derive the current {@code Schedule_Rows} from the fetch-joined estimate lines
     *       ({@link ScheduleRowDerivation#deriveRows}, one row per distinct work category); the
     *       {@code rowCount} is their number (R12.1, R12.2).</li>
     *   <li>Index the stored bars by category and count the current rows that carry a bar; a bar whose
     *       category is no longer a current row (an {@code Orphan_Bar}) is never read, so it is
     *       excluded from {@code scheduledCount} — consistent with {@code getView} (R4.5).</li>
     *   <li>Derive the three-valued state with {@link ScheduleReadinessState#deriveState(int, int)}
     *       and return the {@code schedule} gate (R12.3).</li>
     * </ol>
     *
     * @param projectId the project whose readiness is computed
     * @return the {@code schedule} readiness gate (HTTP 200 payload); never {@code null}
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the project is missing or
     *                             not an Accessible_Project of the caller
     */
    @Transactional(readOnly = true)
    public ScheduleReadiness readiness(Long projectId) {
        loadAccessibleProject(projectId);                             // 1 — 404

        // 2 — current Schedule_Rows derived from the estimate (one row per distinct work category).
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        List<ScheduleRowDerivation.RowSource> rowSources = ScheduleRowDerivation.deriveRows(lines);
        int rowCount = rowSources.size();

        // 3 — count current rows that carry a stored bar; orphan bars (category no longer a current
        //     row) are never read, exactly as getView skips them.
        ProjectScheduleEntity schedule = projectScheduleDao.findByProjectId(projectId).orElse(null);
        Map<Long, ProjectScheduleBarEntity> barsByCategory = indexBarsByCategory(schedule);
        int scheduledCount = 0;
        for (ScheduleRowDerivation.RowSource source : rowSources) {
            WorkCategoryEntity category = source.category();
            Long categoryId = category == null ? null : category.getId();
            if (categoryId != null && barsByCategory.containsKey(categoryId)) {
                scheduledCount++;
            }
        }

        // 4 — resolve coverage of the current Schedule_Row categories by the project's ACTIVE WORKER
        //     members (CoverageResolver, R8.2). A category no ACTIVE WORKER covers is an
        //     Uncovered_Category; these drive the aggregated-readiness noWorkerForCategory items.
        List<ProjectMemberEntity> workerMembers = activeWorkerMembers(projectId);
        List<CoverageResolver.WorkerMember> coverageInput = new ArrayList<>(workerMembers.size());
        for (ProjectMemberEntity member : workerMembers) {
            coverageInput.add(new CoverageResolver.WorkerMember(
                    member.getId(), memberCategoryIds(member)));
        }
        List<Long> rowCategoryIds = new ArrayList<>(rowSources.size());
        Map<Long, WorkCategoryEntity> categoryById = new LinkedHashMap<>();
        for (ScheduleRowDerivation.RowSource source : rowSources) {
            WorkCategoryEntity category = source.category();
            if (category != null && category.getId() != null) {
                rowCategoryIds.add(category.getId());
                categoryById.putIfAbsent(category.getId(), category);
            }
        }
        CoverageResolver.Coverage coverage = CoverageResolver.resolve(rowCategoryIds, coverageInput);

        // 5 — the Uncovered_Category ids (in Schedule_Row order) plus their localized names, so the
        //     contributor can emit localized items without a second read (R8.2).
        boolean russian = isRussianLocale();
        List<Long> uncoveredCategoryIds = new ArrayList<>(coverage.uncovered());
        List<UncoveredCategoryRef> uncoveredCategories = new ArrayList<>(uncoveredCategoryIds.size());
        for (Long categoryId : uncoveredCategoryIds) {
            WorkCategoryEntity category = categoryById.get(categoryId);
            String name = category == null ? null : localizedCategoryName(category, russian);
            uncoveredCategories.add(new UncoveredCategoryRef(categoryId, name));
        }

        // 6 — derive the state and return the schedule gate. The gate state stays the FOR-05-10
        //     (rowCount, scheduledCount) derivation; the "BLOCKED when any uncovered" rule is applied
        //     by the aggregated-readiness ScheduleReadinessContributor's area state (design §Readiness).
        ScheduleReadiness.State state = ScheduleReadinessState.deriveState(rowCount, scheduledCount);
        return new ScheduleReadiness(
                ScheduleReadiness.KEY, state, rowCount, scheduledCount,
                uncoveredCategoryIds, uncoveredCategories);
    }

    // --- Audit (task 6.3, Requirement 13) ---

    /**
     * The dedicated serializer for the schedule {@code Bars_Snapshot}. A single reused instance mirrors
     * the FOR-05-09 {@code ProjectMemberService} audit mapper; it is configured with no features so the
     * snapshot shape is exactly the fields put on the node (R13.4).
     */
    private static final ObjectMapper AUDIT_SNAPSHOT_MAPPER = new ObjectMapper();

    /**
     * Writes exactly one schedule-change {@link AuditLogEntity} for a committed, changing write
     * (Requirement 13), in the caller's (mutating) transaction so an audit-write failure rolls the
     * whole {@code saveBars} / {@code autoCreate} back (R13.1). Both {@link #saveBars} and
     * {@link #autoCreate} call this only on the mutation branch after a successful flush, so a rejected
     * write (validation / lock / conflict) and a no-op save (R7.5) never reach it and write no row
     * (R13.4). The row carries, mirroring the FOR-05-09 team-change audit shape:
     *
     * <ul>
     *   <li>{@code entityClass} = {@code "ProjectScheduleEntity"} (the audited subject type);</li>
     *   <li>{@code entityId} = the schedule id (the subject of the change);</li>
     *   <li>{@code operation} = {@code UPDATE} — every schedule write is an update of the single
     *       per-project schedule, whether it creates or rewrites bars (R13.2);</li>
     *   <li>{@code performedBy} = the id of the authenticated user who performed the write, ADMIN
     *       included;</li>
     *   <li>{@code performedAt} = the time of the change;</li>
     *   <li>{@code snapshotBefore} / {@code snapshotAfter} = the {@code Bars_Snapshot}s
     *       ({@code {bars:[{workCategoryCode, startDay, durationDays}]}}), each in row order; the
     *       before-snapshot is the empty-bars snapshot on the first write (no prior schedule), R13.3.
     *       Neither snapshot carries money, man-days, or the {@code Daily_Output_Rate} (R13.4).</li>
     * </ul>
     *
     * <p>The {@code kind} ({@link #SAVE_BARS_AUDIT_KIND} / {@link #AUTO_CREATE_AUDIT_KIND}) is recorded
     * on the before-snapshot envelope so a reader can tell a manual save from an auto-create while the
     * {@code operation} column stays the uniform {@code UPDATE}.
     *
     * @param schedule the schedule as persisted by the committing write (never {@code null})
     * @param kind     {@link #SAVE_BARS_AUDIT_KIND} or {@link #AUTO_CREATE_AUDIT_KIND}
     * @param before   the before {@code Bars_Snapshot} JSON (empty-bars snapshot on the first write)
     * @param after    the after {@code Bars_Snapshot} JSON (the persisted bars)
     */
    private void writeScheduleAudit(ProjectScheduleEntity schedule, String kind, String before, String after) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setEntityClass(ProjectScheduleEntity.class.getSimpleName());
        auditLog.setEntityId(schedule == null ? null : schedule.getId());
        auditLog.setOperation(kind);
        auditLog.setPerformedBy(performingUser());
        auditLog.setPerformedAt(LocalDateTime.now());
        auditLog.setSnapshotBefore(before);
        auditLog.setSnapshotAfter(after);
        auditLogDao.save(auditLog);
    }

    /**
     * Builds the {@code Bars_Snapshot} JSON for an audit row (Requirement 13 criterion 4). The snapshot
     * contains EXACTLY {@code {"bars":[{"workCategoryCode", "startDay", "durationDays"}, ...]}} — one
     * entry per scheduled bar of a current {@code Schedule_Row}, in row order
     * ({@code orderNo ASC NULLS LAST, id ASC}, from the ordered {@code orderedCategoryIds}). It carries
     * NO money, man-days, or {@code Daily_Output_Rate} field; none is even read here (R13.4). A row
     * with no stored bar is omitted, so a schedule with no bars (the first write's before-state)
     * serializes to the empty-array snapshot {@code {"bars":[]}} (R13.3). The category is identified by
     * its {@code workCategoryCode}, never its id (R13.4, design §Audit).
     *
     * @param orderedCategoryIds the current {@code Schedule_Row} category ids, in row order
     * @param barsByCategory     the bars indexed by work category id (orphans are not in the ordered set)
     * @return the {@code Bars_Snapshot} JSON string
     */
    private static String barsSnapshot(
            Map<Long, WorkCategoryEntity> currentRows,
            Map<BarKey, ProjectScheduleBarEntity> barsByKey) {
        ObjectNode root = AUDIT_SNAPSHOT_MAPPER.createObjectNode();
        ArrayNode bars = root.putArray("bars");
        // One entry per scheduled per-worker bar, grouped by current Schedule_Row order
        // (orderNo ASC NULLS LAST, id ASC from currentRows) then by covering member id ascending with
        // the virtual (null-member) bar last. The category is identified by its code, the worker by
        // its member id (null = the Uncovered_Category virtual bar), never by category id (D-audit).
        for (Long categoryId : currentRows.keySet()) {
            WorkCategoryEntity category = currentRows.get(categoryId);
            List<Map.Entry<BarKey, ProjectScheduleBarEntity>> categoryBars = new ArrayList<>();
            for (Map.Entry<BarKey, ProjectScheduleBarEntity> e : barsByKey.entrySet()) {
                if (categoryId.equals(e.getKey().categoryId())) {
                    categoryBars.add(e);
                }
            }
            categoryBars.sort(java.util.Comparator.comparing(
                    e -> e.getKey().memberId(),
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            for (Map.Entry<BarKey, ProjectScheduleBarEntity> e : categoryBars) {
                ProjectScheduleBarEntity bar = e.getValue();
                if (bar.getStartHalfDays() == null || bar.getDurationHalfDays() == null) {
                    continue;
                }
                ObjectNode node = bars.addObject();
                node.put("workCategoryCode", category == null ? null : category.getCode());
                if (e.getKey().memberId() == null) {
                    node.putNull("projectMemberId");
                } else {
                    node.put("projectMemberId", e.getKey().memberId());
                }
                node.put("startHalfDays", bar.getStartHalfDays());
                node.put("durationHalfDays", bar.getDurationHalfDays());
            }
        }
        try {
            return AUDIT_SNAPSHOT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Defensive: never crash the committing write on a serialization fault — mirror the generic
            // audit's fallback shape rather than propagating.
            return "{\"error\":\"serialization_failed\",\"class\":\"ProjectScheduleEntity\"}";
        }
    }

    /**
     * The id of the authenticated user who performed the write, as the audit {@code performedBy}
     * (Requirement 13, including ADMIN callers). The security principal name is the numeric user id, so
     * it is returned verbatim; a missing / unauthenticated principal falls back to {@code "SYSTEM"},
     * matching the FOR-05-09 team-change audit and the generic audit convention.
     */
    private static String performingUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "SYSTEM";
    }

    /**
     * Indexes a schedule's stored bars by work category id. Returns an empty map when the schedule is
     * absent or has no bars; a bar whose category id cannot be resolved is skipped defensively. Only
     * the mapping is built here — the <em>orphan</em> decision (keeping only bars whose category is a
     * current row) is applied by the caller via a {@code barsByCategory.get(rowCategoryId)} lookup, so
     * a bar whose category is no longer a {@code Schedule_Row} is simply never read (R4.5).
     */
    private static Map<Long, ProjectScheduleBarEntity> indexBarsByCategory(ProjectScheduleEntity schedule) {
        if (schedule == null || schedule.getBars() == null || schedule.getBars().isEmpty()) {
            return Map.of();
        }
        Map<Long, ProjectScheduleBarEntity> byCategory = new HashMap<>();
        for (ProjectScheduleBarEntity bar : schedule.getBars()) {
            WorkCategoryEntity category = bar.getWorkCategory();
            if (category != null && category.getId() != null) {
                byCategory.put(category.getId(), bar);
            }
        }
        return byCategory;
    }

    /** Projects a category's estimate lines onto the localized {@link ScheduleLineView} read model. */
    private static List<ScheduleLineView> toLineViews(List<EstimateLineEntity> lines, boolean russian) {
        List<ScheduleLineView> views = new ArrayList<>(lines.size());
        for (EstimateLineEntity line : lines) {
            WorkItemEntity workItem = line.getWorkItem();
            views.add(new ScheduleLineView(
                    line.getId(),
                    workItem == null ? null : workItem.getId(),
                    localizedWorkItemName(workItem, russian),
                    line.getQuantity(),
                    localizedUnitLabel(line.getUnit(), russian)));
        }
        return views;
    }

    /**
     * The estimate currency code of a project, read from any line's estimate (all lines of a project
     * share the single estimate, FOR-05-03 R1.1), or {@code null} when there are no lines or no
     * currency. Returned only to a Money_Viewer (R5.3).
     */
    private static String estimateCurrencyCode(List<EstimateLineEntity> lines) {
        for (EstimateLineEntity line : lines) {
            if (line.getEstimate() != null && line.getEstimate().getCurrency() != null) {
                return line.getEstimate().getCurrency().getCode();
            }
        }
        return null;
    }

    /** The category name in the resolved language with the ru &rarr; pl &rarr; code fallback (R5.7). */
    private static String localizedCategoryName(WorkCategoryEntity category, boolean russian) {
        return localizedName(
                russian ? category.getNameRU() : category.getNamePL(),
                russian ? category.getNamePL() : category.getNameRU(),
                category.getCode());
    }

    /** The work item name in the resolved language with the ru &rarr; pl &rarr; code fallback (R5.7). */
    private static String localizedWorkItemName(WorkItemEntity workItem, boolean russian) {
        if (workItem == null) {
            return null;
        }
        return localizedName(
                russian ? workItem.getNameRU() : workItem.getNamePL(),
                russian ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    /** The unit label in the resolved language, falling back to the other language then the code. */
    private static String localizedUnitLabel(MeasurementUnitEntity unit, boolean russian) {
        if (unit == null) {
            return null;
        }
        return localizedName(
                russian ? unit.getNameRU() : unit.getNamePL(),
                russian ? unit.getNamePL() : unit.getNameRU(),
                unit.getCode());
    }

    /**
     * The ru &rarr; pl &rarr; code localized-name fallback (R5.7): the preferred-language name when it
     * is non-blank, otherwise the other language's name when it is non-blank, otherwise the code.
     */
    private static String localizedName(String preferred, String fallback, String code) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return code;
    }

    /**
     * Whether the caller is a Money_Viewer (R5.3): ADMIN, or a caller whose role holds the
     * {@code ESTIMATE} READ grant (changeset 135 restricted estimate money to ADMIN / MANAGER /
     * ESTIMATOR). The {@link ForemenPermissionEvaluator} applies the ADMIN bypass, so an ADMIN role
     * always evaluates to {@code true}; an unresolved role is a non-viewer (money fields omitted).
     */
    private boolean isMoneyViewer() {
        String roleCode = callerRoleCode();
        return roleCode != null
                && permissionEvaluator.isAllowed(roleCode, ESTIMATE_RESOURCE, READ_OPERATION);
    }

    /**
     * Whether the caller holds {@code WORK_SCHEDULE} UPDATE — the second half of the {@code editable}
     * flag (R5.1). The {@code PermissionInterceptor} enforced only the READ grant for the read
     * endpoint, so UPDATE is checked here through the evaluator (ADMIN bypassed). An unresolved role
     * is treated as not-updatable.
     */
    private boolean callerCanUpdateSchedule() {
        String roleCode = callerRoleCode();
        return roleCode != null
                && permissionEvaluator.isAllowed(roleCode, WORK_SCHEDULE_RESOURCE, UPDATE_OPERATION);
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

    /** True iff the request locale language is {@code ru} (case-insensitive); mirrors the app's i18n choice. */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * The {@code Access_Denied_Outcome}: a 404 {@code error.entity.not.found} carrying the project id,
     * byte-identical for a missing and a non-accessible project (Requirements 3.2, 3.3).
     */
    private static ForemenApiException notFound(Long projectId) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, projectId);
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
