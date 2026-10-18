package com.foremen.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.WorkReportProgressDto;
import com.foremen.controller.model.WorkReportRoomModel;
import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.WorkReportBatchItemException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.WorkReportServiceModel;
import com.foremen.service.model.mapper.WorkReportServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-01 (Requirements 1.4, 1.5, 2.2–2.6, 3.2, 3.4, 4.x, 5.x, 6.x, 10.5; design §4.1) — the
 * project-scoped service behind work reporting. It owns the <strong>revenue</strong> side of a
 * {@code WorkReport}: the per-element authoring validation (R2/R3), the frozen revenue valuation
 * (R6), the status machine + review cycle (R4), the FOREMAN self-report fast path (R5), and the
 * WORKER own-records row-level scope (R10.5). The cost side ({@code workerUnitPrice}/
 * {@code costValue}/{@code margin}) is FOR-06-10/11/06 and is NOT computed here.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@link ProjectScheduleService} /
 * {@code OfferService}), it implements exactly one CRUD contract — {@link ProjectScopedService} —
 * supplies the standard CRUD plumbing ({@link #getDao()}, {@link #getMapper()},
 * {@link #getEntityManager()}, {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single
 * mandatory per-entity override {@link #getProjectIdPath()} &rarr; {@code "project.id"}
 * (entity-creation-rules step 4; R10.2), and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache}.
 *
 * <p>Beyond the generic CRUD it adds:
 * <ul>
 *   <li>a full {@link #create(WorkReportServiceExtendedModel)} override that resolves the author
 *       (the caller), applies the WORKER self-only / MANAGER no-create row rules (R10.5, R10.7),
 *       validates and resolves the rows (R2/R3), freezes the revenue valuation (R6), and routes a
 *       FOREMAN author straight to {@code APPROVED} (the fast path, R5);</li>
 *   <li>{@link #validateUpdate} + {@link #update} edit guards (terminal immutability R6.4, WORKER
 *       own-records R10.5, re-validation + revenue re-derivation R2.6/R6);</li>
 *   <li>a {@link #addRequiredQuery()} override intersecting the project-scope filter with the WORKER
 *       own-records predicate ({@code worker = caller}, R10.5);</li>
 *   <li>the review transitions {@link #approve(Long)} / {@link #reject(Long, String)} /
 *       {@link #requestChanges(Long, String)} (FOREMAN/ADMIN only, source-status guarded, R4).</li>
 * </ul>
 *
 * <p>The notification side effects (R9) are emitted by a separate {@code WorkReportNotificationEmitter}
 * (task 5.1); this service exposes the committed transitions it hooks onto.
 */
@Service
@Transactional
public class WorkReportService
        implements ProjectScopedService<WorkReportServiceModel, WorkReportServiceExtendedModel,
        WorkReportEntity, Long> {

    // --- Role codes (project Company_Role) ---
    static final String FOREMAN_ROLE_CODE = "FOREMAN";
    static final String WORKER_ROLE_CODE = "WORKER";
    static final String MANAGER_ROLE_CODE = "MANAGER";
    static final String ADMIN_ROLE_CODE = "ADMIN";

    // --- Message codes (bundle wiring is task 7.1) ---
    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String WORK_ITEM_NOT_IN_ESTIMATE = "error.workReport.workItem.notInEstimate";
    static final String WORK_ITEM_NOT_COVERED = "error.workReport.workItem.notCovered";
    static final String ROOM_DUPLICATE = "error.workReport.room.duplicate";
    static final String ROOM_NO_PLAN = "error.workReport.room.noPlan";
    static final String QUANTITY_INVALID = "error.workReport.quantity.invalid";
    static final String STATUS_ILLEGAL_TRANSITION = "error.workReport.status.illegalTransition";
    static final String REJECTION_REASON_REQUIRED = "error.workReport.rejection.reasonRequired";
    static final String WORKER_NOT_SELF = "error.workReport.worker.notSelf";
    static final String ACCESS_NOT_OWNER = "error.workReport.access.notOwner";
    static final String REVIEW_FORBIDDEN = "error.workReport.review.forbidden";
    static final String CREATE_FORBIDDEN = "error.workReport.create.forbidden";
    static final String PERIOD_INVALID = "error.workReport.period.invalid";

    private static final String ROLE_PREFIX = "ROLE_";

    private final WorkReportDao workReportDao;
    private final ProjectMemberDao projectMemberDao;
    private final EstimateLineDao estimateLineDao;
    private final ProjectAccessCache projectAccessCache;
    private final WorkReportServiceMapper serviceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public WorkReportService(WorkReportDao workReportDao,
                             ProjectMemberDao projectMemberDao,
                             EstimateLineDao estimateLineDao,
                             ProjectAccessCache projectAccessCache,
                             WorkReportServiceMapper serviceMapper,
                             AuditLogDao auditLogDao,
                             EntityManager entityManager,
                             ApplicationEventPublisher eventPublisher) {
        this.workReportDao = workReportDao;
        this.projectMemberDao = projectMemberDao;
        this.estimateLineDao = estimateLineDao;
        this.projectAccessCache = projectAccessCache;
        this.serviceMapper = serviceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<WorkReportEntity, Long> getDao() {
        return workReportDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<WorkReportEntity, WorkReportServiceModel,
            WorkReportServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<WorkReportEntity> getDaoModelClass() {
        return WorkReportEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /** The single mandatory per-entity override (entity-creation-rules step 4; R10.2). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    /**
     * Intersects the inherited project-scope list filter with the WORKER own-records predicate
     * (R10.5): a WORKER caller's LIST reads return only reports where they are the {@code worker}.
     * ADMIN (bypassed by the base filter), FOREMAN, MANAGER, and the admin-staff reader roles carry
     * no such restriction and see every report of their accessible projects.
     */
    @Override
    public Specification<WorkReportEntity> addRequiredQuery() {
        Specification<WorkReportEntity> base = ProjectScopedService.super.addRequiredQuery();
        if (!isCallerWorker()) {
            return base;
        }
        Long callerId = callerUserId();
        // A WORKER with no resolvable id sees nothing (the own-records predicate can never match).
        Specification<WorkReportEntity> ownOnly = (root, query, cb) ->
                callerId == null ? cb.disjunction() : cb.equal(root.get("worker").get("id"), callerId);
        return base == null ? ownOnly : base.and(ownOnly);
    }

    // --- Create (R2, R3, R5, R6, R10.5, R10.7) ---

    /**
     * Creates a work report, overriding the generic {@link AdminService#create(Object)} so the whole
     * aggregate (project / work item / worker / author FKs, the resolved + snapshotted rooms, the
     * frozen revenue valuation, and the initial status) is assembled, validated, and persisted in one
     * transaction:
     *
     * <ol>
     *   <li>Resolve the authenticated {@code author} and the author's project {@code Company_Role}
     *       (R5, R10). A MANAGER holds no CREATE grant, so a MANAGER create is refused
     *       ({@link #CREATE_FORBIDDEN}, R10.7); a WORKER may create only {@code worker = self}
     *       ({@link #WORKER_NOT_SELF}, R10.5).</li>
     *   <li>Validate the authoring inputs (R2/R3) and resolve each row to an absolute quantity with a
     *       Plan_Quantity snapshot (R2.2/R2.6).</li>
     *   <li>Freeze the revenue valuation: {@code clientUnitPrice} = frozen {@code EstimateLine.unitPrice}
     *       (+ approved amendment deltas when FOR-06-08 is present, else 0), {@code totalQuantity} =
     *       {@code Σ rooms.quantity}, {@code revenueValue} = {@code round2(clientUnitPrice × totalQuantity)}
     *       (R1.4/R1.5/R6).</li>
     *   <li>Route the status: a FOREMAN author's report is created directly {@code APPROVED} with
     *       {@code approvedBy = author} / {@code approvedAt = now} and no notification (the fast path,
     *       R5); every other author's report starts {@code SUBMITTED} (R4.1).</li>
     * </ol>
     *
     * @param model the author-supplied write model
     * @return the persisted report as a read-extended service model
     */
    @Override
    @Transactional
    public WorkReportServiceExtendedModel create(WorkReportServiceExtendedModel model) {
        UserEntity author = requireAuthor();
        Long projectId = model.getProjectId();
        String authorRole = projectRoleCode(author.getId(), projectId);

        // R10.7 — a MANAGER (holding no CREATE grant) may never author a report.
        if (MANAGER_ROLE_CODE.equals(authorRole)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, CREATE_FORBIDDEN);
        }
        // R10.5 — a WORKER may create only their own report.
        if (WORKER_ROLE_CODE.equals(authorRole)
                && !Objects.equals(author.getId(), model.getWorkerId())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, WORKER_NOT_SELF);
        }

        WorkReportEntity entity = new WorkReportEntity();
        entity.setProject(reference(ProjectEntity.class, projectId));
        entity.setWorkItem(reference(WorkItemEntity.class, model.getWorkItemId()));
        entity.setWorker(reference(UserEntity.class, model.getWorkerId()));
        entity.setAuthor(author);
        entity.setPerformedFrom(model.getPerformedFrom());
        entity.setPerformedTo(model.getPerformedTo());
        entity.setNote(model.getNote());

        // R2/R3 validation + row resolution + revenue freeze (R1.4/R1.5/R6).
        assembleReport(entity, projectId, model.getWorkItemId(), model.getWorkerId(), model.getRooms());

        // R5 — the FOREMAN fast path: created already APPROVED, revenue frozen, no notification.
        boolean fastPath = FOREMAN_ROLE_CODE.equals(authorRole);
        if (fastPath) {
            entity.setStatus(WorkReportStatus.APPROVED);
            entity.setApprovedBy(author);
            entity.setApprovedAt(OffsetDateTime.now());
        } else {
            entity.setStatus(WorkReportStatus.SUBMITTED);
        }

        WorkReportEntity saved = workReportDao.save(entity);
        entityManager.flush();
        saveAudit(null, saved, "CREATE");

        // R9.1 — a non-FOREMAN submit notifies every ACTIVE FOREMAN member; the FOREMAN fast path
        // (R5) is auto-approved on create and emits nothing (R9.5), so no event is published for it.
        if (!fastPath) {
            publishNotification(WorkReportNotificationEvent.Trigger.SUBMITTED, saved, null);
        }
        return serviceMapper.toServiceExtendedModel(saved);
    }

    // --- Bulk create (Amendment A1, R16) ------------------------------------------------------

    /**
     * Creates a batch of work reports in <strong>one</strong> transaction (Amendment A1, R16.3/R16.4/
     * R16.5). Each item is run through the exact single-create path ({@link #create}) so every item
     * gets the SAME validation (Covering_Work R3.2, work-item-on-estimate R3.4, duplicate room R2.4,
     * quantity &gt; 0 R2.5, PERCENT-with-no-plan R2.3), the SAME PERCENT&rarr;absolute resolution +
     * Plan_Quantity snapshot (R2.2/R2.6), the SAME frozen revenue valuation (R6), the SAME FOREMAN
     * fast path (R5), and the SAME WORKER own-records rule (R10.5) as a single create.
     *
     * <p><b>All-or-nothing (R16.4).</b> {@code create(...)} joins this method's transaction
     * (propagation {@code REQUIRED}), so a {@link ForemenApiException} thrown while assembling any
     * item rolls back the whole batch — no report from the batch is persisted. The failure is
     * rethrown as a {@link WorkReportBatchItemException} carrying the zero-based {@code index} of the
     * failing payload (plus its {@code workItemId} and the offending {@code roomId} when the error is
     * room-scoped) so the controller (A1.3) can report exactly which payload failed, changing
     * nothing.
     *
     * <p><b>Notifications (R16.5).</b> Each item publishes its own notification event through the same
     * {@link #publishNotification} path as the single create. Those events are {@code AFTER_COMMIT}
     * listeners on this single transaction, so on a successful batch commit the emitter fires one
     * {@code submitted} per created non-FOREMAN report (to each ACTIVE FOREMAN) and emits nothing for
     * the FOREMAN-authored fast-path items (R5) — exactly as N separate single creates would. If the
     * batch rolls back, no event fires.
     *
     * @param items the author-supplied write models, each identical in shape to a single create (R16.1)
     * @return the persisted reports as read-extended service models, in submission order
     * @throws WorkReportBatchItemException if any item fails validation (the whole batch is rolled back)
     */
    @Transactional
    public List<WorkReportServiceExtendedModel> createBatch(List<WorkReportServiceExtendedModel> items) {
        List<WorkReportServiceExtendedModel> created = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return created;
        }
        for (int index = 0; index < items.size(); index++) {
            WorkReportServiceExtendedModel item = items.get(index);
            try {
                created.add(create(item));
            } catch (ForemenApiException e) {
                // R16.4 — abort the whole batch; identify the failing payload by index (+ workItem /
                // offending room). The outer @Transactional rolls back, so nothing is persisted and no
                // AFTER_COMMIT notification event fires.
                Long workItemId = item == null ? null : item.getWorkItemId();
                throw new WorkReportBatchItemException(index, workItemId, offendingRoomId(e), e);
            }
        }
        return created;
    }

    /**
     * Extracts the offending room id from a per-item failure for the batch error locator (R16.4). The
     * room-scoped validations ({@link #ROOM_DUPLICATE}, {@link #ROOM_NO_PLAN}, {@link #QUANTITY_INVALID})
     * carry the room id as the first message param; every other failure is report-scoped and resolves
     * to {@code null}.
     */
    private static Long offendingRoomId(ForemenApiException e) {
        String code = e.getMessageCode();
        boolean roomScoped = ROOM_DUPLICATE.equals(code) || ROOM_NO_PLAN.equals(code)
                || QUANTITY_INVALID.equals(code);
        if (!roomScoped) {
            return null;
        }
        Object[] params = e.getMessageParams();
        if (params != null && params.length > 0 && params[0] instanceof Long roomId) {
            return roomId;
        }
        return null;
    }

    // --- Update / edit (R2.6, R4.5, R6.4, R10.5) ---

    /**
     * Guards an edit before the inherited {@link ProjectScopedService#update(Object, Object)} applies
     * it (the project-scope access check runs first via the overridden {@code update}; this hook adds
     * the report-specific rules):
     * <ul>
     *   <li>a WORKER caller may edit only their own report ({@link #ACCESS_NOT_OWNER}, R10.5);</li>
     *   <li>a terminal report ({@code APPROVED}/{@code REJECTED}) is immutable
     *       ({@link #STATUS_ILLEGAL_TRANSITION}, R4.6/R6.4).</li>
     * </ul>
     * The field mapping, row re-resolution, revenue re-derivation, and resubmit transition are then
     * applied in {@link #update(Long, WorkReportServiceExtendedModel)}.
     */
    @Override
    public void validateUpdate(WorkReportEntity existing, WorkReportServiceExtendedModel update) {
        assertCallerOwnsIfWorker(existing);
        if (isTerminal(existing.getStatus())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
        }
    }

    /**
     * Edits a non-terminal report and (when it was {@code CHANGES_REQUESTED}) resubmits it (R4.5).
     * Overrides the inherited {@link ProjectScopedService#update(Object, Object)} so the owned rows
     * are re-resolved and the revenue valuation re-derived in the same transaction. The base
     * {@code update} still runs the project-scope access guard and {@link #validateUpdate} first.
     *
     * <p>The work item / worker may change (a correction before approval), so the rows and the frozen
     * {@code clientUnitPrice} are recomputed from the post-edit {@code (workItem, worker)}. A
     * {@code CHANGES_REQUESTED} report transitions back to {@code SUBMITTED} on edit (the author's
     * resubmit, R4.5); a {@code SUBMITTED} report stays {@code SUBMITTED}.
     */
    @Override
    @Transactional
    public WorkReportServiceExtendedModel update(Long id, WorkReportServiceExtendedModel model) {
        assertProjectAccess(id);
        WorkReportEntity existing = workReportDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        String before = serializeEntity(existing);
        validateUpdate(existing, model);

        // Flat scalars + optional FK corrections (projectId is fixed at create; the mapper leaves it).
        if (model.getWorkItemId() != null) {
            existing.setWorkItem(reference(WorkItemEntity.class, model.getWorkItemId()));
        }
        if (model.getWorkerId() != null) {
            existing.setWorker(reference(UserEntity.class, model.getWorkerId()));
        }
        if (model.getPerformedFrom() != null) {
            existing.setPerformedFrom(model.getPerformedFrom());
        }
        if (model.getPerformedTo() != null) {
            existing.setPerformedTo(model.getPerformedTo());
        }
        existing.setNote(model.getNote());

        Long projectId = existing.getProject().getId();
        Long workItemId = existing.getWorkItem() == null ? null : existing.getWorkItem().getId();
        Long workerId = existing.getWorker() == null ? null : existing.getWorker().getId();
        assembleReport(existing, projectId, workItemId, workerId, model.getRooms());

        // R4.5 — an edit of a CHANGES_REQUESTED report is the author's resubmit → SUBMITTED.
        if (existing.getStatus() == WorkReportStatus.CHANGES_REQUESTED) {
            existing.setStatus(WorkReportStatus.SUBMITTED);
        }

        WorkReportEntity saved = workReportDao.save(existing);
        entityManager.flush();
        saveAuditWithSnapshot(before, saved, "UPDATE");
        return serviceMapper.toServiceExtendedModel(saved);
    }

    /**
     * Delete guard: a WORKER may delete only their own report (R10.5); the project-scope access check
     * is applied first by the inherited {@link ProjectScopedService#deleteById(Object)} override,
     * which then delegates to this body.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        assertProjectAccess(id);
        WorkReportEntity existing = workReportDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        assertCallerOwnsIfWorker(existing);
        saveAudit(existing, null, "DELETE");
        workReportDao.deleteById(id);
        entityManager.flush();
    }

    /**
     * Single-report read with the WORKER own-records guard (R10.5): the project-scope access check
     * is applied first (via the inherited {@link ProjectScopedService#findById}'s
     * {@code assertProjectAccess}), then — when the caller is a WORKER — the report is refused unless
     * {@code report.worker = caller} ({@link #ACCESS_NOT_OWNER}). The LIST read is already own-records
     * filtered via {@link #addRequiredQuery()}; this closes the by-id read path so a WORKER cannot
     * read another worker's report by guessing its id.
     */
    @Override
    @Transactional(readOnly = true)
    public WorkReportServiceExtendedModel findById(Long id) {
        assertProjectAccess(id);
        WorkReportEntity existing = workReportDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        assertCallerOwnsIfWorker(existing);
        return serviceMapper.toServiceExtendedModel(existing);
    }

    // --- Progress matrix read projection (A1, R14) --------------------------------------------

    /** The report statuses that count toward the reported quantity (R14.2); {@code REJECTED} excluded. */
    private static final Set<WorkReportStatus> REPORTED_STATUSES = Set.of(
            WorkReportStatus.SUBMITTED, WorkReportStatus.CHANGES_REQUESTED, WorkReportStatus.APPROVED);

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /**
     * FOR-06-01 Amendment A1 (Requirement 14; design A1.1) — the progress matrix read projection for
     * one {@code (project, worker)}: the worker's <b>Covering_Works</b> (R3.1) that appear on the
     * project's estimate, grouped by work category (in {@code WorkCategory.orderNo} declaration
     * order), each work-item row carrying per-room {@code planQuantity} + {@code reportedQuantity}
     * (+ a capped {@code reportedPercent}).
     *
     * <p>Access (R14.6): READ is already gated {@code WORK_REPORTS}/READ by the controller; here the
     * projection additionally enforces the project scope ({@link #assertProjectRead(Long)} — ADMIN
     * bypass, else the project must be in the caller's accessible set) and the own-records rule — a
     * WORKER caller may request only their own {@code workerId} ({@link #ACCESS_NOT_OWNER}); a
     * FOREMAN/MANAGER/ADMIN may request any worker of the project.
     *
     * <p>Derivation:
     * <ul>
     *   <li>resolve the worker's project-member work categories (Covering_Works, R3.1); an empty set
     *       yields an empty matrix (R14.5);</li>
     *   <li>keep the estimate lines whose work-item category is a Covering_Work (R3.1/R3.4 — the
     *       estimate is the work-item source);</li>
     *   <li>for each kept line, read each {@code (workItem, room)} {@code planQuantity} from
     *       {@code EstimateLineRoomQty} and look up {@code reportedQuantity} from the pre-summed map
     *       (R14.2); {@code reportedPercent = round(reported / plan × 100)} capped at 100 when a plan
     *       exists, else {@code null} (R14.3);</li>
     *   <li>omit a work item whose every <b>planned</b> room is already {@code ≥ 100 %}; keep one with
     *       any not-yet-complete planned room or any absolute-only (no-plan) room (R14.4);</li>
     *   <li>project the room column axis as the distinct rooms referenced by the kept work items'
     *       plans, localized ru → pl → label/code (R12.1).</li>
     * </ul>
     *
     * @param projectId the project the matrix is for
     * @param workerId  the worker the matrix is for (a WORKER caller may pass only their own id)
     * @return the matrix source {@link WorkReportProgressDto} (empty categories/rooms when nothing
     *         is left to report, R14.5)
     */
    @Transactional(readOnly = true)
    public WorkReportProgressDto progress(Long projectId, Long workerId) {
        assertProjectRead(projectId);
        assertWorkerOwnIfWorker(workerId);

        boolean ru = isRussianLocale();

        // Covering_Works (R3.1): the worker's project-member work-category ids. Empty => empty matrix.
        Set<Long> coveredCategoryIds = coveredCategoryIds(projectId, workerId);
        if (coveredCategoryIds.isEmpty()) {
            return new WorkReportProgressDto(projectId, workerId, List.of(), List.of());
        }

        // Already-reported quantity per (workItem, room) over non-REJECTED reports (R14.2).
        Map<Long, Map<Long, BigDecimal>> reported = reportedByWorkItemRoom(projectId, workerId);

        // The room column axis, insertion-ordered as first referenced across the kept rows (R14.1).
        Map<Long, WorkReportProgressDto.RoomColumn> roomColumns = new LinkedHashMap<>();
        // Category groups keyed by category id, each preserving its WorkItemRow list.
        Map<Long, CategoryAccumulator> groups = new HashMap<>();

        for (EstimateLineEntity line : estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId)) {
            WorkItemEntity workItem = line.getWorkItem();
            WorkCategoryEntity category = workItem == null ? null : workItem.getWorkCategory();
            if (workItem == null || category == null
                    || !coveredCategoryIds.contains(category.getId())) {
                continue; // not a Covering_Work on the estimate — skip (R3.1).
            }

            Map<Long, BigDecimal> reportedForItem =
                    reported.getOrDefault(workItem.getId(), Map.of());

            // Build the cells for this work item across its planned rooms (R14.3).
            List<WorkReportProgressDto.Cell> cells = new ArrayList<>();
            boolean keep = false; // R14.4 — keep the row only if something is still reportable.
            if (line.getRoomQtys() != null) {
                for (EstimateLineRoomQtyEntity rq : line.getRoomQtys()) {
                    RoomEntity room = rq.getRoom();
                    if (room == null) {
                        continue;
                    }
                    Long roomId = room.getId();
                    BigDecimal plan = rq.getQuantity();
                    BigDecimal reportedQty = reportedForItem.getOrDefault(roomId, BigDecimal.ZERO);
                    Integer percent = reportedPercent(reportedQty, plan);
                    cells.add(new WorkReportProgressDto.Cell(roomId, plan, reportedQty, percent));

                    // A planned room still below 100% (or an absolute-only / no-plan room) keeps the row.
                    if (plan == null || plan.signum() <= 0 || reportedQty.compareTo(plan) < 0) {
                        keep = true;
                    }
                    roomColumns.computeIfAbsent(roomId,
                            k -> new WorkReportProgressDto.RoomColumn(k, resolveRoomName(room, ru)));
                }
            }
            // A work item with no planned rooms at all is nothing to report against — omit it (R14.4).
            if (!keep) {
                continue;
            }

            WorkReportProgressDto.WorkItemRow row = new WorkReportProgressDto.WorkItemRow(
                    workItem.getId(),
                    resolveWorkItemName(workItem, ru),
                    unitCode(line.getUnit()),
                    cells);
            groups.computeIfAbsent(category.getId(), k -> new CategoryAccumulator(category, ru))
                    .rows.add(row);
        }

        // Emit the category groups in declaration order (WorkCategory.orderNo, R17.2).
        List<WorkReportProgressDto.CategoryGroup> categories = groups.values().stream()
                .sorted(Comparator.comparing(CategoryAccumulator::orderNo)
                        .thenComparing(CategoryAccumulator::categoryId))
                .map(CategoryAccumulator::toGroup)
                .toList();

        return new WorkReportProgressDto(
                projectId, workerId, new ArrayList<>(roomColumns.values()), categories);
    }

    /**
     * {@code round(reportedQuantity / planQuantity × 100)} capped at 100 when a positive plan exists,
     * else {@code null} (no plan ⇒ absolute-only cell, R14.3).
     */
    private static Integer reportedPercent(BigDecimal reportedQty, BigDecimal plan) {
        if (plan == null || plan.signum() <= 0) {
            return null;
        }
        BigDecimal pct = reportedQty.multiply(ONE_HUNDRED)
                .divide(plan, 0, RoundingMode.HALF_UP);
        int value = pct.intValue();
        return value > 100 ? 100 : value;
    }

    /** The worker's project-member work-category ids (Covering_Works, R3.1); empty when none. */
    private Set<Long> coveredCategoryIds(Long projectId, Long workerId) {
        ProjectMemberEntity member = (workerId == null || projectId == null) ? null
                : projectMemberDao.findByUserIdAndProjectId(workerId, projectId).orElse(null);
        if (member == null || member.getWorkCategories() == null) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (WorkCategoryEntity wc : member.getWorkCategories()) {
            if (wc != null && wc.getId() != null) {
                ids.add(wc.getId());
            }
        }
        return ids;
    }

    /**
     * The already-reported quantity grouped {@code workItemId → (roomId → Σ quantity)} over the
     * worker's non-{@code REJECTED} reports (R14.2), read from the DAO aggregate.
     */
    private Map<Long, Map<Long, BigDecimal>> reportedByWorkItemRoom(Long projectId, Long workerId) {
        Map<Long, Map<Long, BigDecimal>> byItem = new HashMap<>();
        for (Object[] row : workReportDao.sumReportedQuantityByWorkItemAndRoom(
                projectId, workerId, REPORTED_STATUSES)) {
            Long workItemId = (Long) row[0];
            Long roomId = (Long) row[1];
            BigDecimal sum = (BigDecimal) row[2];
            byItem.computeIfAbsent(workItemId, k -> new HashMap<>())
                    .put(roomId, sum == null ? BigDecimal.ZERO : sum);
        }
        return byItem;
    }

    /**
     * Project-scope READ guard for the progress projection (R14.6): ADMIN bypasses; otherwise the
     * {@code projectId} must be in the caller's accessible project set (the same source the LIST
     * filter and {@code assertProjectAccess} use). Denied uniformly with a 404 so an out-of-scope
     * project is not revealed, consistent with {@link ProjectScopedService#assertProjectAccess}.
     */
    private void assertProjectRead(Long projectId) {
        if (isCallerAdmin()) {
            return;
        }
        Long callerId = callerUserId();
        if (callerId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
        Set<Long> allowed = allowedProjectIds(callerId);
        if (allowed == null || projectId == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
    }

    /**
     * Own-records guard for the progress projection (R14.6/R10.5): a WORKER caller may request only
     * their own {@code workerId} ({@link #ACCESS_NOT_OWNER}); FOREMAN/MANAGER/ADMIN may request any
     * worker of the (already access-checked) project.
     */
    private void assertWorkerOwnIfWorker(Long workerId) {
        if (!isCallerWorker()) {
            return;
        }
        Long callerId = callerUserId();
        if (callerId == null || !callerId.equals(workerId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_NOT_OWNER);
        }
    }

    // --- Localization helpers (ru → pl → code/label, R12.1) -----------------------------------

    /** Localized work-item name (ru → pl → code), mirroring the service mapper's resolution. */
    private static String resolveWorkItemName(WorkItemEntity workItem, boolean ru) {
        return firstNonBlank(ru ? workItem.getNameRU() : workItem.getNamePL(),
                ru ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    /** Localized room name (ru → pl → label/code): room label first, else the room-type name. */
    private static String resolveRoomName(RoomEntity room, boolean ru) {
        if (room == null) {
            return null;
        }
        RoomTypeEntity type = room.getRoomType();
        String typeName = type == null ? null
                : firstNonBlank(ru ? type.getNameRU() : type.getNamePL(),
                        ru ? type.getNamePL() : type.getNameRU(),
                        type.getCode());
        return firstNonBlank(room.getLabel(), typeName);
    }

    /** The measurement unit's code (the cell unit label), or {@code null} when the unit is absent. */
    private static String unitCode(MeasurementUnitEntity unit) {
        return unit == null ? null : unit.getCode();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * Mutable accumulator for a category row group while building the matrix: holds the category's
     * localized name + order and the work-item rows added so far, collapsed to an immutable
     * {@link WorkReportProgressDto.CategoryGroup} at the end.
     */
    private static final class CategoryAccumulator {
        private final WorkCategoryEntity category;
        private final String name;
        private final List<WorkReportProgressDto.WorkItemRow> rows = new ArrayList<>();

        CategoryAccumulator(WorkCategoryEntity category, boolean ru) {
            this.category = category;
            this.name = firstNonBlank(ru ? category.getNameRU() : category.getNamePL(),
                    ru ? category.getNamePL() : category.getNameRU(),
                    category.getCode());
        }

        Long categoryId() {
            return category.getId();
        }

        /** The category's declaration order; a null {@code orderNo} sorts last (R17.2). */
        int orderNo() {
            return category.getOrderNo() == null ? Integer.MAX_VALUE : category.getOrderNo();
        }

        WorkReportProgressDto.CategoryGroup toGroup() {
            return new WorkReportProgressDto.CategoryGroup(category.getId(), name, rows);
        }
    }

    // --- Review transitions (R4.2/R4.3/R4.4/R4.6/R4.7) ---

    /**
     * Approves a {@code SUBMITTED} or {@code CHANGES_REQUESTED} report (R4.2). Only an ACTIVE FOREMAN
     * approver of the project (or ADMIN) may do so (R4.7); the source status is guarded
     * ({@link #STATUS_ILLEGAL_TRANSITION}, R4.6). On approval the status becomes {@code APPROVED},
     * {@code approvedBy}/{@code approvedAt} are set, and the revenue valuation is frozen (already
     * frozen at create; left untouched here, R6.4).
     */
    @Transactional
    public WorkReportServiceExtendedModel approve(Long id) {
        WorkReportEntity report = loadForReview(id);
        assertApprover(report);
        requireSource(report, WorkReportStatus.SUBMITTED, WorkReportStatus.CHANGES_REQUESTED);
        String before = serializeEntity(report);
        report.setStatus(WorkReportStatus.APPROVED);
        report.setApprovedBy(currentApprover());
        report.setApprovedAt(OffsetDateTime.now());
        report.setRejectionReason(null);
        WorkReportEntity saved = workReportDao.save(report);
        entityManager.flush();
        saveAuditWithSnapshot(before, saved, "UPDATE");
        // R9.2 — notify the author of the approval.
        publishNotification(WorkReportNotificationEvent.Trigger.APPROVED, saved, null);
        return serviceMapper.toServiceExtendedModel(saved);
    }

    /**
     * Rejects a {@code SUBMITTED} report with a required non-empty reason (R4.3). FOREMAN/ADMIN only
     * (R4.7); the source status is guarded (R4.6). {@code REJECTED} is terminal.
     */
    @Transactional
    public WorkReportServiceExtendedModel reject(Long id, String reason) {
        WorkReportEntity report = loadForReview(id);
        assertApprover(report);
        requireSource(report, WorkReportStatus.SUBMITTED);
        requireReason(reason);
        String before = serializeEntity(report);
        report.setStatus(WorkReportStatus.REJECTED);
        report.setRejectionReason(reason.trim());
        WorkReportEntity saved = workReportDao.save(report);
        entityManager.flush();
        saveAuditWithSnapshot(before, saved, "UPDATE");
        // R9.3 — notify the author of the rejection, carrying the reason.
        publishNotification(WorkReportNotificationEvent.Trigger.REJECTED, saved, saved.getRejectionReason());
        return serviceMapper.toServiceExtendedModel(saved);
    }

    /**
     * Returns a {@code SUBMITTED} report to its author for correction with a required non-empty
     * reason (R4.4). FOREMAN/ADMIN only (R4.7); the source status is guarded (R4.6). The status
     * becomes {@code CHANGES_REQUESTED}, from which the author may edit + resubmit (R4.5).
     */
    @Transactional
    public WorkReportServiceExtendedModel requestChanges(Long id, String reason) {
        WorkReportEntity report = loadForReview(id);
        assertApprover(report);
        requireSource(report, WorkReportStatus.SUBMITTED);
        requireReason(reason);
        String before = serializeEntity(report);
        report.setStatus(WorkReportStatus.CHANGES_REQUESTED);
        report.setRejectionReason(reason.trim());
        WorkReportEntity saved = workReportDao.save(report);
        entityManager.flush();
        saveAuditWithSnapshot(before, saved, "UPDATE");
        // R9.4 — notify the author that changes were requested, carrying the reason.
        publishNotification(
                WorkReportNotificationEvent.Trigger.CHANGES_REQUESTED, saved, saved.getRejectionReason());
        return serviceMapper.toServiceExtendedModel(saved);
    }

    // --- Assembly: validation + row resolution + revenue freeze -------------------------------

    /**
     * The shared create/update assembly (R2/R3/R6): validates the period and the author inputs,
     * resolves the estimate line for {@code (project, workItem)} (R3.4), verifies the
     * {@code (worker, workItem)} is a Covering_Work (R3.2), rebuilds the owned {@code rooms}
     * collection from the submitted rows (resolving PERCENT → absolute + snapshotting Plan_Quantity,
     * rejecting duplicate rooms / non-positive quantity / PERCENT-with-no-plan), and freezes the
     * revenue valuation.
     */
    private void assembleReport(WorkReportEntity entity, Long projectId, Long workItemId,
                                Long workerId, List<WorkReportRoomModel> rows) {
        // R1.1 — the performed interval must be well-formed (performedTo >= performedFrom).
        if (entity.getPerformedFrom() == null || entity.getPerformedTo() == null
                || entity.getPerformedTo().isBefore(entity.getPerformedFrom())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PERIOD_INVALID);
        }
        if (rows == null || rows.isEmpty()) {
            // At least one row is required (R2.1); reuse the quantity-invalid signal with no row id.
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, QUANTITY_INVALID, (Object) null);
        }

        // R3.4 — the work item must be on the project's estimate.
        EstimateLineEntity line = findEstimateLine(projectId, workItemId);
        if (line == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, WORK_ITEM_NOT_IN_ESTIMATE, workItemId);
        }

        // R3.2 — the (worker, workItem) must be a Covering_Work via project_member_work_categories.
        assertCoveringWork(projectId, workerId, line.getWorkItem(), workItemId);

        // R2.3/R2.4/R2.5 + R2.2/R2.6 — resolve the rows against the line's per-room plan quantities.
        rebuildRooms(entity, line, rows);

        // R1.4/R1.5/R6 — derive and freeze the revenue valuation.
        BigDecimal clientUnitPrice = resolveClientUnitPrice(line);
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (WorkReportRoomEntity room : entity.getRooms()) {
            totalQuantity = totalQuantity.add(room.getQuantity());
        }
        entity.setClientUnitPrice(clientUnitPrice);
        entity.setTotalQuantity(totalQuantity);
        entity.setRevenueValue(clientUnitPrice.multiply(totalQuantity).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Rebuilds the owned {@code rooms} collection from the submitted rows in one pass (R2): rejects a
     * duplicate room (R2.4), resolves PERCENT → absolute against the room's Plan_Quantity and
     * snapshots {@code planQuantity} (R2.2/R2.6), rejects PERCENT-with-no-plan (R2.3), and rejects a
     * non-positive / null resolved quantity (R2.5). The collection is cleared first so orphanRemoval
     * discards any previous rows on an edit.
     */
    private void rebuildRooms(WorkReportEntity entity, EstimateLineEntity line,
                              List<WorkReportRoomModel> rows) {
        // Orphan-remove the previous rows and FLUSH the deletes before inserting the rebuilt rows.
        // Without this, an edit/resubmit that reuses a room makes Hibernate insert the new
        // (report, room) row in the same flush as the orphan delete; the (report_id, room_id) unique
        // constraint can then fire mid-flush (insert-before-delete ordering) and the save fails with
        // a data-integrity 409. Flushing the clear first guarantees the deletes land before the
        // inserts. On create the collection is empty, so the flush is a cheap no-op.
        if (!entity.getRooms().isEmpty()) {
            entity.getRooms().clear();
            entityManager.flush();
        }
        Set<Long> seenRooms = new HashSet<>();
        for (WorkReportRoomModel row : rows) {
            Long roomId = row.roomId();
            if (roomId == null || !seenRooms.add(roomId)) {
                // A null room id or a repeated room is a duplicate-room violation (R2.4).
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, ROOM_DUPLICATE, roomId);
            }
            WorkReportInputMode mode = row.inputMode();
            BigDecimal planQuantity = planQuantityFor(line, roomId); // nullable
            BigDecimal resolved;
            if (mode == WorkReportInputMode.PERCENT) {
                // R2.3 — PERCENT requires a plan; R2.2 — resolve planQuantity × percent / 100.
                if (planQuantity == null) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, ROOM_NO_PLAN, roomId);
                }
                BigDecimal percent = row.quantity();
                if (percent == null || percent.signum() <= 0
                        || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, QUANTITY_INVALID, roomId);
                }
                resolved = planQuantity.multiply(percent)
                        .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
            } else {
                // ABSOLUTE — the entered amount in the work unit; planQuantity snapshot may be null.
                resolved = row.quantity();
            }
            // R2.5 — the resolved absolute quantity must be strictly positive.
            if (resolved == null || resolved.signum() <= 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, QUANTITY_INVALID, roomId);
            }

            WorkReportRoomEntity roomEntity = new WorkReportRoomEntity();
            roomEntity.setReport(entity);
            roomEntity.setRoom(reference(RoomEntity.class, roomId));
            roomEntity.setQuantity(resolved);
            roomEntity.setPlanQuantity(planQuantity);
            roomEntity.setInputMode(mode == null ? WorkReportInputMode.ABSOLUTE : mode);
            entity.getRooms().add(roomEntity);
        }
    }

    /**
     * The frozen revenue unit price (R6.1/R6.2): the estimate line's frozen {@code unitPrice} plus the
     * sum of approved amendment deltas ({@code AmendmentLine.extraUnitPrice}, FOR-06-08). FOR-06-08 is
     * not present in this build, so the amendment delta is treated as zero (R6.2) and the result is
     * simply the line's frozen {@code unitPrice}.
     */
    private BigDecimal resolveClientUnitPrice(EstimateLineEntity line) {
        BigDecimal unitPrice = line.getUnitPrice() == null ? BigDecimal.ZERO : line.getUnitPrice();
        // FOR-06-08 amendment deltas are read here when present; absent → zero (R6.2).
        BigDecimal amendmentDelta = BigDecimal.ZERO;
        return unitPrice.add(amendmentDelta);
    }

    /**
     * The project's estimate line for {@code workItemId}, or {@code null} when the work item is not on
     * the project's estimate (R3.4). Reuses {@link EstimateLineDao#findByProjectIdWithWorkItemCategoryAndUnit}
     * so the line's work item / category / unit and (via the owning estimate) its room quantities are
     * reachable within the transaction.
     */
    private EstimateLineEntity findEstimateLine(Long projectId, Long workItemId) {
        if (projectId == null || workItemId == null) {
            return null;
        }
        for (EstimateLineEntity line : estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId)) {
            WorkItemEntity wi = line.getWorkItem();
            if (wi != null && workItemId.equals(wi.getId())) {
                return line;
            }
        }
        return null;
    }

    /** The Plan_Quantity for {@code (line, room)} from the line's room quantities, or {@code null}. */
    private static BigDecimal planQuantityFor(EstimateLineEntity line, Long roomId) {
        if (line.getRoomQtys() == null) {
            return null;
        }
        for (EstimateLineRoomQtyEntity rq : line.getRoomQtys()) {
            if (rq.getRoom() != null && roomId.equals(rq.getRoom().getId())) {
                return rq.getQuantity();
            }
        }
        return null;
    }

    /**
     * Asserts the {@code (worker, workItem)} is a Covering_Work (R3.2): the work item's
     * {@link WorkCategoryEntity} must be in the worker's project-member work categories
     * ({@code project_member_work_categories}, FOR-05-10b). Rejected with {@link #WORK_ITEM_NOT_COVERED}
     * naming the work item when it is not.
     */
    private void assertCoveringWork(Long projectId, Long workerId, WorkItemEntity workItem,
                                    Long workItemId) {
        WorkCategoryEntity category = workItem == null ? null : workItem.getWorkCategory();
        Long categoryId = category == null ? null : category.getId();
        ProjectMemberEntity member = workerId == null ? null
                : projectMemberDao.findByUserIdAndProjectId(workerId, projectId).orElse(null);
        boolean covered = false;
        if (member != null && categoryId != null && member.getWorkCategories() != null) {
            for (WorkCategoryEntity wc : member.getWorkCategories()) {
                if (wc != null && categoryId.equals(wc.getId())) {
                    covered = true;
                    break;
                }
            }
        }
        if (!covered) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, WORK_ITEM_NOT_COVERED, workItemId);
        }
    }

    // --- Review helpers -----------------------------------------------------------------------

    /** Loads a report for a review transition, applying the project-scope access guard first (404). */
    private WorkReportEntity loadForReview(Long id) {
        assertProjectAccess(id);
        return workReportDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
    }

    /**
     * Asserts the caller may run a review transition (R4.7): only an ACTIVE FOREMAN member of the
     * report's project, or an ADMIN, may approve / reject / request-changes. A WORKER (even on their
     * own report) and a MANAGER are refused with {@link #REVIEW_FORBIDDEN}.
     */
    private void assertApprover(WorkReportEntity report) {
        if (isCallerAdmin()) {
            return;
        }
        Long callerId = callerUserId();
        Long projectId = report.getProject() == null ? null : report.getProject().getId();
        ProjectMemberEntity member = (callerId == null || projectId == null) ? null
                : projectMemberDao.findByUserIdAndProjectId(callerId, projectId).orElse(null);
        boolean activeForeman = member != null
                && member.getAssignmentStatus() == AssignmentStatus.ACTIVE
                && member.getProjectRole() != null
                && FOREMAN_ROLE_CODE.equals(member.getProjectRole().getCode());
        if (!activeForeman) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, REVIEW_FORBIDDEN);
        }
    }

    /** The approver to stamp on an approval — the current caller as a managed {@link UserEntity}. */
    private UserEntity currentApprover() {
        Long callerId = callerUserId();
        return callerId == null ? null : reference(UserEntity.class, callerId);
    }

    /** Guards the source status of a transition (R4.6): refuses any status not in {@code allowed}. */
    private static void requireSource(WorkReportEntity report, WorkReportStatus... allowed) {
        for (WorkReportStatus status : allowed) {
            if (report.getStatus() == status) {
                return;
            }
        }
        throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
    }

    /** Requires a non-empty rejection / change reason (R4.3/R4.4). */
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, REJECTION_REASON_REQUIRED);
        }
    }

    /** {@code APPROVED} and {@code REJECTED} are terminal (R4.6/R6.4). */
    private static boolean isTerminal(WorkReportStatus status) {
        return status == WorkReportStatus.APPROVED || status == WorkReportStatus.REJECTED;
    }

    /** A WORKER caller may touch only their own report (R10.5); others carry no row restriction. */
    private void assertCallerOwnsIfWorker(WorkReportEntity report) {
        if (!isCallerWorker()) {
            return;
        }
        Long callerId = callerUserId();
        Long workerId = report.getWorker() == null ? null : report.getWorker().getId();
        if (callerId == null || !callerId.equals(workerId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_NOT_OWNER);
        }
    }

    // --- Notification emission (R9) -----------------------------------------------------------

    /**
     * Publishes a {@link WorkReportNotificationEvent} for a committed transition (R9). The event is
     * handed off through the {@link ApplicationEventPublisher} and consumed by
     * {@code WorkReportNotificationEmitter} on an {@code AFTER_COMMIT}
     * {@code @TransactionalEventListener}, so the notification is created only after this transition
     * durably commits and a failure while emitting can never roll it back. The event carries only
     * ids + the (nullable) reason — never the loaded entity — so it stays valid on the after-commit
     * thread. The FOREMAN fast path (R5) publishes nothing (R9.5); its caller simply skips this call.
     *
     * @param trigger the lifecycle change that occurred (selects the type + recipients at the emitter)
     * @param report  the just-saved report (its project / author / id are read here, pre-detach)
     * @param reason  the rejection / change reason for {@code REJECTED} / {@code CHANGES_REQUESTED}
     *                (R9.3/R9.4); {@code null} for {@code SUBMITTED} / {@code APPROVED}
     */
    private void publishNotification(WorkReportNotificationEvent.Trigger trigger,
                                     WorkReportEntity report, String reason) {
        Long projectId = report.getProject() == null ? null : report.getProject().getId();
        Long authorUserId = report.getAuthor() == null ? null : report.getAuthor().getId();
        eventPublisher.publishEvent(new WorkReportNotificationEvent(
                trigger, report.getId(), projectId, authorUserId, reason));
    }

    // --- Caller / role resolution -------------------------------------------------------------

    /** The authenticated author as a managed reference; 401 when the caller cannot be resolved. */
    private UserEntity requireAuthor() {
        Long callerId = callerUserId();
        if (callerId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, NOT_FOUND_MESSAGE);
        }
        return reference(UserEntity.class, callerId);
    }

    /** The caller's project {@code Company_Role} code for {@code projectId}, or {@code null}. */
    private String projectRoleCode(Long userId, Long projectId) {
        if (userId == null || projectId == null) {
            return null;
        }
        ProjectMemberEntity member =
                projectMemberDao.findByUserIdAndProjectId(userId, projectId).orElse(null);
        if (member == null || member.getProjectRole() == null) {
            return null;
        }
        return member.getProjectRole().getCode();
    }

    /** True iff the caller's security authority carries the WORKER role code (R10.5 row scope). */
    private boolean isCallerWorker() {
        return WORKER_ROLE_CODE.equals(callerAuthorityRoleCode());
    }

    /** The caller's role code from the {@code ROLE_<code>} authority, or {@code null} if unresolved. */
    private static String callerAuthorityRoleCode() {
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

    /** True iff the authenticated caller carries the exact ADMIN authority. */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (("ROLE_" + ADMIN_ROLE_CODE).equals(a) || ADMIN_ROLE_CODE.equals(a)) {
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

    /** A managed JPA reference ({@code getReference}, no SELECT) for a non-null id, else {@code null}. */
    private <T> T reference(Class<T> type, Long id) {
        return id == null ? null : entityManager.getReference(type, id);
    }
}
