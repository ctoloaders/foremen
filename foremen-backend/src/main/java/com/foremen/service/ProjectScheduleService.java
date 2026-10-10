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
import com.foremen.controller.model.schedule.AutoCreateRequest;
import com.foremen.controller.model.schedule.BarEntry;
import com.foremen.controller.model.schedule.SaveBarsRequest;
import com.foremen.controller.model.schedule.ScheduleLineView;
import com.foremen.controller.model.schedule.ScheduleReadiness;
import com.foremen.controller.model.schedule.ScheduleRowView;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
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
import com.foremen.service.schedule.ScheduleCalculator;
import com.foremen.service.schedule.ScheduleReadinessState;
import com.foremen.service.schedule.ScheduleRowDerivation;

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
     * 409 raised when {@link #autoCreate(Long, AutoCreateRequest)} is called for a project whose team
     * has no ACTIVE WORKER member, so no {@code Crew_Size} is available to lay the schedule out (R8.1).
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

        // 3 — stored bars indexed by work category id; orphan bars (category no longer a row) skipped.
        ProjectScheduleEntity schedule = projectScheduleDao.findByProjectId(projectId).orElse(null);
        long version = schedule == null || schedule.getVersion() == null ? 0L : schedule.getVersion();
        Map<Long, ProjectScheduleBarEntity> barsByCategory = indexBarsByCategory(schedule);

        // 4 — crew, rate, money/edit flags.
        int crewSize = (int) projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                projectId, WORKER_ROLE_CODE, AssignmentStatus.ACTIVE);
        BigDecimal rate = scheduleProperties.dailyOutputPerWorker();
        boolean moneyViewer = isMoneyViewer();
        boolean russian = isRussianLocale();
        boolean editable = EDITABLE_STATUSES.contains(project.getStatus()) && callerCanUpdateSchedule();

        // 5 — map each row (and collect the laid-out bars for the schedule-wide finish computation).
        List<ScheduleRowView> rows = new ArrayList<>(rowSources.size());
        List<ScheduleCalculator.BarLayout> scheduledBars = new ArrayList<>();
        for (ScheduleRowDerivation.RowSource source : rowSources) {
            WorkCategoryEntity category = source.category();
            Long categoryId = category.getId();
            ProjectScheduleBarEntity bar = barsByCategory.get(categoryId);

            Integer startDay = bar == null ? null : bar.getStartDay();
            Integer durationDays = bar == null ? null : bar.getDurationDays();
            Integer finishDay = bar == null
                    ? null
                    : ScheduleCalculator.finishDay(bar.getStartDay(), bar.getDurationDays());
            if (bar != null) {
                scheduledBars.add(new ScheduleCalculator.BarLayout(
                        categoryId, bar.getStartDay(), bar.getDurationDays()));
            }

            rows.add(new ScheduleRowView(
                    categoryId,
                    category.getCode(),
                    category.getOrderNo(),
                    localizedCategoryName(category, russian),
                    source.lineCount(),
                    ScheduleCalculator.suggested(source.categoryValue(), rate, crewSize),
                    moneyViewer ? source.categoryValue() : null,
                    startDay,
                    durationDays,
                    finishDay,
                    toLineViews(source.lines(), russian)));
        }

        // 6 — schedule-wide finish, finishDate, exceedsProjectEnd, and the money currency.
        LocalDate anchor = project.getStartDate();
        LocalDate endDate = project.getEndDate();
        Integer scheduleFinish = ScheduleCalculator.scheduleFinish(scheduledBars);
        LocalDate finishDate = ScheduleCalculator.finishDate(anchor, scheduleFinish);
        boolean exceedsProjectEnd = ScheduleCalculator.exceedsProjectEnd(anchor, endDate, scheduleFinish);
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
                rows);
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

        // 4 — business validation of every entry; nothing is mutated until all entries pass.
        validateEntries(request.bars(), currentRows.keySet());

        // Desired final bar state = stored bars of current rows (orphans dropped) with the listed
        // entries applied. A null-pair entry clears the category; a value entry sets/replaces it.
        Map<Long, ProjectScheduleBarEntity> storedByCategory = indexBarsByCategory(schedule);
        Map<Long, int[]> desired = computeDesiredBars(storedByCategory, currentRows.keySet(), request.bars());

        // No-op (R7.5): the schedule exists, the desired set equals the stored current-row set, and
        // there is no Orphan_Bar to purge. Nothing is written; the version and audit are untouched.
        if (schedule != null
                && !hasOrphan(storedByCategory, currentRows.keySet())
                && isNoChange(storedByCategory, currentRows.keySet(), desired)) {
            return getView(projectId);
        }

        // Capture the BEFORE snapshot of the stored bars (current rows, in row order) BEFORE applyBars
        // mutates the owned collection; empty on the first write (no prior schedule / bars), R13.3.
        String before = barsSnapshot(currentRows.keySet(), storedByCategory);

        // Mutation branch — create the schedule on the first write (R6.4).
        boolean firstWrite = schedule == null;
        if (firstWrite) {
            schedule = new ProjectScheduleEntity();
            schedule.setProject(project);
        }

        applyBars(schedule, currentRows, desired);                    // upsert / clear + purge orphans

        // Force exactly one @Version increment even when only child bar rows changed (R7.1, R11).
        entityManager.persist(schedule);
        entityManager.lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        try {
            entityManager.flush();                                    // surface DB races here (R6.4/11.3)
        } catch (RuntimeException e) {
            throw mapWriteConflict(e);
        }

        // One SAVE_BARS audit row per committed changing write (R13.1): the after-snapshot is the
        // persisted bar set, in row order; rejected / no-op saves never reach this point (R13.4).
        String after = barsSnapshot(currentRows.keySet(), indexBarsByCategory(schedule));
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
    private static void validateEntries(List<BarEntry> entries, Set<Long> currentCategoryIds) {
        Set<Long> seen = new HashSet<>();
        for (BarEntry entry : entries) {
            Long categoryId = entry.workCategoryId();
            if (categoryId == null
                    || !currentCategoryIds.contains(categoryId)
                    || !seen.add(categoryId)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, CATEGORY_INVALID_MESSAGE, categoryId);
            }
        }
        for (BarEntry entry : entries) {
            if (!ScheduleCalculator.validateBar(entry.startDay(), entry.durationDays())) {
                throw new ForemenApiException(
                        HttpStatus.BAD_REQUEST, BAR_INVALID_MESSAGE, entry.workCategoryId());
            }
        }
    }

    /**
     * Computes the desired final bar set as a map of category id &rarr; {@code [startDay, durationDays]}
     * for scheduled bars only. It starts from the stored bars of <em>current rows</em> (orphans
     * dropped, R4.5), then applies the listed entries: a value entry sets / replaces the pair, a
     * null-pair entry removes the category. Unlisted current-row categories keep their stored bar.
     */
    private static Map<Long, int[]> computeDesiredBars(
            Map<Long, ProjectScheduleBarEntity> storedByCategory,
            Set<Long> currentCategoryIds,
            List<BarEntry> entries) {
        Map<Long, int[]> desired = new HashMap<>();
        for (Long categoryId : currentCategoryIds) {
            ProjectScheduleBarEntity bar = storedByCategory.get(categoryId);
            if (bar != null && bar.getStartDay() != null && bar.getDurationDays() != null) {
                desired.put(categoryId, new int[] {bar.getStartDay(), bar.getDurationDays()});
            }
        }
        for (BarEntry entry : entries) {
            if (entry.startDay() == null && entry.durationDays() == null) {
                desired.remove(entry.workCategoryId());
            } else {
                desired.put(entry.workCategoryId(),
                        new int[] {entry.startDay(), entry.durationDays()});
            }
        }
        return desired;
    }

    /** Whether any stored bar is an {@code Orphan_Bar} (its category is no longer a current row, R4.5). */
    private static boolean hasOrphan(
            Map<Long, ProjectScheduleBarEntity> storedByCategory, Set<Long> currentCategoryIds) {
        for (Long categoryId : storedByCategory.keySet()) {
            if (!currentCategoryIds.contains(categoryId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the desired bar set equals the stored current-row bar set — the no-op test (R7.5). Equal
     * means the same scheduled categories with identical {@code startDay} / {@code durationDays}. Only
     * current-row stored bars are compared (orphans are handled separately by {@link #hasOrphan}).
     */
    private static boolean isNoChange(
            Map<Long, ProjectScheduleBarEntity> storedByCategory,
            Set<Long> currentCategoryIds,
            Map<Long, int[]> desired) {
        Map<Long, int[]> storedCurrent = new HashMap<>();
        for (Long categoryId : currentCategoryIds) {
            ProjectScheduleBarEntity bar = storedByCategory.get(categoryId);
            if (bar != null && bar.getStartDay() != null && bar.getDurationDays() != null) {
                storedCurrent.put(categoryId, new int[] {bar.getStartDay(), bar.getDurationDays()});
            }
        }
        if (storedCurrent.size() != desired.size()) {
            return false;
        }
        for (Map.Entry<Long, int[]> e : desired.entrySet()) {
            int[] stored = storedCurrent.get(e.getKey());
            if (stored == null || stored[0] != e.getValue()[0] || stored[1] != e.getValue()[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mutates the schedule's owned bar collection to match {@code desired} and purges every
     * {@code Orphan_Bar} (R4.6), relying on {@code cascade = ALL} + {@code orphanRemoval = true}:
     * <ul>
     *   <li>a bar whose category is not in {@code desired} (cleared, or an orphan) is removed from the
     *       collection, which deletes its row;</li>
     *   <li>a bar whose category is in {@code desired} has its period updated in place;</li>
     *   <li>a desired category with no existing bar gets a new {@link ProjectScheduleBarEntity} whose
     *       {@code workCategory} is the attached current-row entity.</li>
     * </ul>
     */
    private static void applyBars(
            ProjectScheduleEntity schedule,
            Map<Long, WorkCategoryEntity> currentRows,
            Map<Long, int[]> desired) {
        List<ProjectScheduleBarEntity> bars = schedule.getBars();

        // Update or remove existing bars; track which desired categories already have a bar.
        Set<Long> existing = new HashSet<>();
        Iterator<ProjectScheduleBarEntity> it = bars.iterator();
        while (it.hasNext()) {
            ProjectScheduleBarEntity bar = it.next();
            WorkCategoryEntity category = bar.getWorkCategory();
            Long categoryId = category == null ? null : category.getId();
            int[] target = categoryId == null ? null : desired.get(categoryId);
            if (target == null) {
                it.remove();                                          // cleared bar or Orphan_Bar
            } else {
                bar.setStartDay(target[0]);
                bar.setDurationDays(target[1]);
                existing.add(categoryId);
            }
        }

        // Add bars for desired categories that have none yet.
        for (Map.Entry<Long, int[]> e : desired.entrySet()) {
            if (existing.contains(e.getKey())) {
                continue;
            }
            ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
            bar.setSchedule(schedule);
            bar.setWorkCategory(currentRows.get(e.getKey()));
            bar.setStartDay(e.getValue()[0]);
            bar.setDurationDays(e.getValue()[1]);
            bars.add(bar);
        }
    }



    // --- Auto-create (task 6.2, Requirements 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 9.2) ---

    /**
     * Rebuilds a project's bars from scratch by laying every current {@code Schedule_Row} out back to
     * back from day 1, using the internal {@code Daily_Output_Rate} and the project's active-worker
     * {@code Crew_Size} (FOR-05-10 Requirement 8; the {@code WORK_SCHEDULE} UPDATE grant is enforced
     * upstream by the {@code PermissionInterceptor}). It <em>replaces</em> the whole plan: every row
     * gets a fresh {@code (startDay, durationDays)} and any bar not matching a current row (an
     * {@code Orphan_Bar}, or a stale bar from a previous layout) is purged (R8.5). The whole operation
     * runs in one transaction and increments the {@code Schedule_Version} exactly once.
     *
     * <p><b>Check order (R3.5).</b> The same order as {@link #saveBars(Long, SaveBarsRequest)}:
     * <ol>
     *   <li>{@link #loadAccessibleProject(Long)} — 404 for a missing / out-of-scope project
     *       (R3.2, R3.3).</li>
     *   <li>{@link #assertEditable(ProjectEntity)} — 409 {@code error.schedule.locked} in a
     *       {@code Schedule_Locked_Status} (R10.2).</li>
     *   <li>{@link #assertVersion(ProjectScheduleEntity, Long)} — 409 {@code error.schedule.conflict}
     *       on a stale version (R11.2).</li>
     *   <li>Business validation (below): 409 {@code error.schedule.crew.empty} when there is no ACTIVE
     *       WORKER (R8.1), 409 {@code error.schedule.rows.empty} when the estimate yields no rows
     *       (R8.6), and 409 {@code error.schedule.too.long} when the laid-out plan would run past day
     *       3650 (R8.7) — all raised before any bar is changed.</li>
     * </ol>
     *
     * <p><b>Layout and mutation.</b> With crew and rows in hand, {@link ScheduleCalculator#autoLayout}
     * computes each row's {@code durationDays = max(1, ceil(categoryValue / (rate × crew)))} and
     * places the rows contiguously from day 1 (every calendar day is a working day, R8.2, R8.3),
     * throwing {@code error.schedule.too.long} on overrun. The resulting bars <em>replace</em> all
     * stored bars: the schedule is loaded (or created on the first auto-create, R6.4), its bar
     * collection is rewritten to exactly the laid-out set via {@link #applyBars} (which also purges
     * orphans and stale bars), the parent is forced to bump its {@code @Version} once
     * ({@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}) even though only child bars change, and the
     * write is flushed with database-race mapping ({@link #mapWriteConflict(RuntimeException)},
     * R6.4/R11.3). A single {@code AUTO_CREATE} audit row is written on the committed write (task 6.3,
     * see {@link #writeAutoCreateAudit}).
     *
     * <p>Unlike {@code saveBars}, auto-create has no no-op short-circuit: a successful call always
     * replaces the plan and therefore always increments the version once, even if the computed layout
     * happens to equal the stored bars (R8.5 — "replace all bars"). The rate is read only from
     * {@link ScheduleProperties}; it is never accepted from the request and never returned (R9.2).
     *
     * @param projectId the project whose schedule is auto-created
     * @param request   the version the client last read (the request carries no rate — R9.2)
     * @return the fresh {@code Schedule_View} after the layout (HTTP 200 payload); never {@code null}
     * @throws ForemenApiException 404 {@code error.entity.not.found} (R3.2/3.3), 409
     *                             {@code error.schedule.locked} (R10.2), 409
     *                             {@code error.schedule.conflict} (R11.2/3), 409
     *                             {@code error.schedule.crew.empty} (R8.1), 409
     *                             {@code error.schedule.rows.empty} (R8.6), or 409
     *                             {@code error.schedule.too.long} (R8.7)
     */
    @Transactional
    public ScheduleView autoCreate(Long projectId, AutoCreateRequest request) {
        ProjectEntity project = loadAccessibleProject(projectId);     // 1 — 404
        assertEditable(project);                                      // 2 — 409 locked

        ProjectScheduleEntity schedule = projectScheduleDao.findByProjectId(projectId).orElse(null);
        assertVersion(schedule, request.version());                   // 3 — 409 conflict

        // 4a — crew must have at least one ACTIVE WORKER (R8.1); nothing is mutated on rejection.
        int crewSize = (int) projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                projectId, WORKER_ROLE_CODE, AssignmentStatus.ACTIVE);
        if (crewSize < 1) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CREW_EMPTY_MESSAGE);
        }

        // 4b — the estimate must yield at least one Schedule_Row (R8.6).
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        List<ScheduleRowDerivation.RowSource> rowSources = ScheduleRowDerivation.deriveRows(lines);
        if (rowSources.isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ROWS_EMPTY_MESSAGE);
        }

        // 4c — lay the rows out back to back from day 1 with the internal rate (R8.2, R8.3); throws
        //      409 error.schedule.too.long on an overrun past day 3650 (R8.7). Still nothing mutated.
        BigDecimal rate = scheduleProperties.dailyOutputPerWorker();
        List<ScheduleCalculator.BarLayout> layout =
                ScheduleCalculator.autoLayout(rowSources, rate, crewSize);

        // Replace-all desired set (R8.5): exactly the laid-out bars, keyed by category id.
        Map<Long, WorkCategoryEntity> currentRows = currentRowCategories(lines);
        Map<Long, int[]> desired = new HashMap<>();
        for (ScheduleCalculator.BarLayout bar : layout) {
            desired.put(bar.workCategoryId(), new int[] {bar.startDay(), bar.durationDays()});
        }

        // Capture the BEFORE snapshot of the stored bars (current rows, in row order) BEFORE applyBars
        // rewrites the owned collection; empty on the first auto-create (no prior schedule), R13.3.
        String before = barsSnapshot(currentRows.keySet(), indexBarsByCategory(schedule));

        // Mutation branch — create the schedule on the first write (R6.4). Auto-create always writes
        // (no no-op short-circuit): a successful layout replaces the plan and bumps the version once.
        if (schedule == null) {
            schedule = new ProjectScheduleEntity();
            schedule.setProject(project);
        }

        applyBars(schedule, currentRows, desired);                    // replace all bars + purge orphans

        // Force exactly one @Version increment even when only child bar rows changed (R8.5, R11).
        entityManager.persist(schedule);
        entityManager.lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        try {
            entityManager.flush();                                    // surface DB races here (R6.4/11.3)
        } catch (RuntimeException e) {
            throw mapWriteConflict(e);
        }

        // One AUTO_CREATE audit row per committed auto-create (R13.1): the after-snapshot is the
        // laid-out bar set, in row order; a rejected auto-create never reaches this point (R13.4).
        String after = barsSnapshot(currentRows.keySet(), indexBarsByCategory(schedule));
        writeScheduleAudit(schedule, AUTO_CREATE_AUDIT_KIND, before, after);

        return getView(projectId);
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

        // 4 — derive the state and return the schedule gate.
        ScheduleReadiness.State state = ScheduleReadinessState.deriveState(rowCount, scheduledCount);
        return new ScheduleReadiness(ScheduleReadiness.KEY, state, rowCount, scheduledCount);
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
            java.util.Collection<Long> orderedCategoryIds,
            Map<Long, ProjectScheduleBarEntity> barsByCategory) {
        ObjectNode root = AUDIT_SNAPSHOT_MAPPER.createObjectNode();
        ArrayNode bars = root.putArray("bars");
        for (Long categoryId : orderedCategoryIds) {
            ProjectScheduleBarEntity bar = barsByCategory.get(categoryId);
            if (bar == null || bar.getStartDay() == null || bar.getDurationDays() == null) {
                continue;
            }
            WorkCategoryEntity category = bar.getWorkCategory();
            ObjectNode node = bars.addObject();
            node.put("workCategoryCode", category == null ? null : category.getCode());
            node.put("startDay", bar.getStartDay());
            node.put("durationDays", bar.getDurationDays());
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
