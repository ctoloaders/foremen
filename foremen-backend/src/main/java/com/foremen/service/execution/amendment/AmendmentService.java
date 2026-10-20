package com.foremen.service.execution.amendment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.AddLineRequest;
import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.AmendmentServiceModel;
import com.foremen.service.model.mapper.AmendmentServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-08 (Requirements 1–7; design §Backend components) — the project-scoped service behind
 * amendment (aneks) authoring. It owns the {@code EstimateAmendment} / {@code AmendmentLine}
 * aggregate up to "ready to sign": the single-open-amendment invariant (R2), the create-amendment
 * eligibility guard (R2.5), the per-{@code Change_Type} add-line validation (R3), the read-only
 * baseline resolution for a {@code VOLUME_CHANGE} (R7.1), the same-subject {@code Merge_Conflict}
 * detection + resolution via the pure {@link AmendmentMergeResolver} (R5), and line / amendment
 * deletion while {@code OPEN} (R6). It is a pure overlay — no write touches the signed estimate,
 * the schedule, or any work report (R7.3); sending for signing and applying the deltas are FOR-06-09.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code WorkReportService} /
 * {@code MaterialsMatrixService}), it implements exactly one CRUD contract — {@link
 * ProjectScopedService} — supplies the standard CRUD plumbing, the single mandatory per-entity
 * override {@link #getProjectIdPath()} &rarr; {@code "project.id"} (entity-creation-rules step 4,
 * R9.1), and wires {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}, so every LIST
 * read is project-filtered and every by-id read/mutation runs the per-id project-access guard.
 *
 * <p>The merge arithmetic is delegated to the persistence-free {@link AmendmentMergeResolver}; this
 * service is the thin apply-layer that maps between the JPA entities and the resolver's
 * {@link AmendmentLineDelta} value type, resolving the {@code VOLUME_CHANGE} baseline from the signed
 * estimate read model ({@link EstimateLineDao#findByProjectIdWithWorkItemCategoryAndUnit}) — read
 * only, never mutated.
 */
@Service
@Transactional
public class AmendmentService
        implements ProjectScopedService<AmendmentServiceModel, AmendmentServiceExtendedModel,
        EstimateAmendmentEntity, Long> {

    // --- Message codes (bundle wiring is task 10.1) ---
    static final String NOT_FOUND = "error.entity.not.found";
    static final String OPEN_EXISTS = "error.amendment.openExists";
    static final String NOT_OPEN = "error.amendment.notOpen";
    static final String SUBJECT_CONFLICT = "error.amendment.subjectConflict";
    static final String NOT_ELIGIBLE = "error.amendment.notEligible";
    static final String PRICE_INVALID = "error.amendment.priceInvalid";
    static final String VOLUME_NEGATIVE = AmendmentMergeResolver.VOLUME_NEGATIVE;
    static final String VALIDATION_WORK_ITEM = "error.amendment.validation.workItem";
    static final String VALIDATION_ROOM = "error.amendment.validation.room";
    static final String VALIDATION_QUANTITY = "error.amendment.validation.quantity";
    static final String VALIDATION_SCHEDULE_DELTA = "error.amendment.validation.scheduleDelta";
    static final String LINE_NOT_FOUND = "error.amendment.line.notFound";

    private final EstimateAmendmentDao amendmentDao;
    private final EstimateDao estimateDao;
    private final EstimateLineDao estimateLineDao;
    private final ProjectAccessCache projectAccessCache;
    private final AmendmentMergeResolver mergeResolver;
    private final AmendmentServiceMapper serviceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    public AmendmentService(EstimateAmendmentDao amendmentDao,
                            EstimateDao estimateDao,
                            EstimateLineDao estimateLineDao,
                            ProjectAccessCache projectAccessCache,
                            AmendmentMergeResolver mergeResolver,
                            AmendmentServiceMapper serviceMapper,
                            AuditLogDao auditLogDao,
                            EntityManager entityManager) {
        this.amendmentDao = amendmentDao;
        this.estimateDao = estimateDao;
        this.estimateLineDao = estimateLineDao;
        this.projectAccessCache = projectAccessCache;
        this.mergeResolver = mergeResolver;
        this.serviceMapper = serviceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
    }

    // --- CRUD plumbing (inherited via ProjectScopedService) ------------------------------------

    @Override
    public AdminDao<EstimateAmendmentEntity, Long> getDao() {
        return amendmentDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<EstimateAmendmentEntity, AmendmentServiceModel,
            AmendmentServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<EstimateAmendmentEntity> getDaoModelClass() {
        return EstimateAmendmentEntity.class;
    }

    // --- ProjectScopedService overrides --------------------------------------------------------

    /** The single mandatory per-entity override (entity-creation-rules step 4; R9.1). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Create (R2) ---------------------------------------------------------------------------

    /**
     * Creates the project's {@code OPEN} amendment (R2.2). Guards eligibility (the project must be
     * {@code ACTIVE} with a {@code SIGNED} estimate baseline, R2.5 — {@link #NOT_ELIGIBLE}) and the
     * single-open-amendment invariant (a create while an {@code OPEN} amendment exists is rejected,
     * R2.1/R2.3 — {@link #OPEN_EXISTS}, complementing the partial unique index); then inserts an
     * {@code OPEN} amendment with the next {@code seqNo} and an empty line set.
     *
     * @param projectId the owning project id
     * @param note      optional free-text note (untranslated, R11.3)
     * @return the created amendment as a read-extended service model
     */
    public AmendmentServiceExtendedModel create(Long projectId, String note) {
        assertEligible(projectId);
        if (amendmentDao.findByProjectIdAndStatus(projectId, AmendmentStatus.OPEN).isPresent()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, OPEN_EXISTS);
        }

        EstimateAmendmentEntity amendment = new EstimateAmendmentEntity();
        amendment.setProject(reference(ProjectEntity.class, projectId));
        amendment.setStatus(AmendmentStatus.OPEN);
        amendment.setSeqNo(nextSeqNo(projectId));
        amendment.setNote(note);

        EstimateAmendmentEntity saved = amendmentDao.save(amendment);
        entityManager.flush();
        saveAudit(null, saved, "CREATE");
        return serviceMapper.toServiceExtendedModel(saved);
    }

    /**
     * The project's {@code OPEN} amendment, or {@code null} when the project has none (R1.3/R2).
     * Read-only; does not create the amendment (the create action does that explicitly).
     *
     * @param projectId the owning project id
     * @return the open amendment as a read-extended service model, or {@code null}
     */
    @Transactional(readOnly = true)
    public AmendmentServiceExtendedModel getOpenOrNull(Long projectId) {
        return amendmentDao.findByProjectIdAndStatus(projectId, AmendmentStatus.OPEN)
                .map(serviceMapper::toServiceExtendedModel)
                .orElse(null);
    }

    /**
     * The project's amendments ordered by {@code seqNo} (R1.1/R1.2) as list-row models. Ownership is
     * enforced by the caller passing a project the caller belongs to (the controller rides the
     * project-scope gate); the row models carry the per-amendment summary the list renders.
     *
     * @param projectId the owning project id
     * @return the project's amendments as list-row service models
     */
    @Transactional(readOnly = true)
    public List<AmendmentServiceModel> list(Long projectId) {
        return amendmentDao.findByProjectIdOrderBySeqNoAsc(projectId).stream()
                .map(serviceMapper::toServiceModel)
                .toList();
    }

    /**
     * A single amendment by id with its change lines (R1.4). The project-scope access guard runs
     * first via the inherited {@link ProjectScopedService#findById}.
     *
     * @param id the amendment id
     * @return the amendment as a read-extended service model
     */
    @Transactional(readOnly = true)
    public AmendmentServiceExtendedModel get(Long id) {
        return findById(id);
    }

    // --- Add line (R3, R5, R7.1) ---------------------------------------------------------------

    /**
     * Adds a change line to the project's {@code OPEN} amendment (R3), creating the open amendment
     * first if none exists (R3.5 / R2.2). The flow:
     *
     * <ol>
     *   <li>resolve the {@code OPEN} amendment (or create it, guarding eligibility) and assert it is
     *       {@code OPEN} (R2.4 — {@link #NOT_OPEN});</li>
     *   <li>validate the request per {@link ChangeType} (R3.6) and resolve the read-only
     *       {@code VOLUME_CHANGE} baseline quantity from the signed estimate (R7.1), building the
     *       persistence-free {@link AmendmentLineDelta} the resolver operates on;</li>
     *   <li>detect a same-subject {@code Merge_Conflict} against the existing lines via
     *       {@link AmendmentMergeResolver#conflicts} (R5.1): with no {@link AddLineRequest#mergeResolution()}
     *       reject {@link #SUBJECT_CONFLICT} (409) carrying the existing line's id so the UI opens the
     *       chooser (R5.6); with a resolution merge the surviving line <em>in place</em> via
     *       {@link AmendmentMergeResolver#resolve} (R5.4);</li>
     *   <li>otherwise (distinct subject, R5.5) append a new line.</li>
     * </ol>
     *
     * @param projectId the owning project id
     * @param request   the add-change payload (polymorphic by {@code changeType}, optional resolution)
     * @return the amendment as a read-extended service model after the add/merge
     */
    public AmendmentServiceExtendedModel addLine(Long projectId, AddLineRequest request) {
        Objects.requireNonNull(request, "request");
        ChangeType changeType = request.changeType();
        if (changeType == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_WORK_ITEM);
        }

        EstimateAmendmentEntity amendment = resolveOrCreateOpen(projectId);
        assertOpen(amendment);

        // R3.6 — per-type validation; builds the incoming delta with the resolved baseline (R7.1).
        AmendmentLineDelta incoming = validateAndBuildIncoming(projectId, request);

        // R5 — same-subject conflict detection against the existing lines.
        AmendmentLineEntity existingEntity = findSameSubject(amendment, incoming);
        if (existingEntity != null) {
            if (request.mergeResolution() == null) {
                // R5.1/R5.6 — surface the existing line so the UI can open the merge chooser.
                throw new ForemenApiException(
                        HttpStatus.CONFLICT, SUBJECT_CONFLICT, existingEntity.getId());
            }
            // R5.4 — merge in place via the pure resolver, then write the surviving line back.
            AmendmentLineDelta existingDelta = toDelta(existingEntity, incoming.getBaselineQuantity());
            AmendmentLineDelta survivor =
                    mergeResolver.resolve(existingDelta, incoming, request.mergeResolution());
            applyDeltaToEntity(existingEntity, survivor);
        } else {
            // R3.5 / R5.5 — distinct subject: append a new line.
            AmendmentLineEntity line = new AmendmentLineEntity();
            line.setAmendment(amendment);
            applyDeltaToEntity(line, incoming);
            line.setDescription(request.description());
            amendment.getLines().add(line);
        }

        EstimateAmendmentEntity saved = amendmentDao.save(amendment);
        entityManager.flush();
        return serviceMapper.toServiceExtendedModel(saved);
    }

    // --- Delete (R6) ---------------------------------------------------------------------------

    /**
     * Deletes a single change line from an {@code OPEN} amendment (R6.1); the amendment stays
     * {@code OPEN} even when it becomes empty (R6.3). The project-scope access guard runs first via
     * {@link ProjectScopedService#assertProjectAccess}. Rejects {@link #NOT_OPEN} when the amendment
     * has left {@code OPEN} (R6.4) and {@link #LINE_NOT_FOUND} when the line is not on it. Relies on
     * the amendment's {@code orphanRemoval = true} to delete the detached line row.
     *
     * @param amendmentId the owning amendment id
     * @param lineId      the line to remove
     * @return the amendment as a read-extended service model after the deletion
     */
    public AmendmentServiceExtendedModel deleteLine(Long amendmentId, Long lineId) {
        assertProjectAccess(amendmentId);
        EstimateAmendmentEntity amendment = loadAmendment(amendmentId);
        assertOpen(amendment);

        AmendmentLineEntity line = amendment.getLines().stream()
                .filter(l -> Objects.equals(l.getId(), lineId))
                .findFirst()
                .orElseThrow(() ->
                        new ForemenApiException(HttpStatus.NOT_FOUND, LINE_NOT_FOUND, lineId));

        amendment.getLines().remove(line);
        EstimateAmendmentEntity saved = amendmentDao.save(amendment);
        entityManager.flush();
        return serviceMapper.toServiceExtendedModel(saved);
    }

    /**
     * Deletes a whole {@code OPEN} amendment (R6.2) so a fresh one can be created — a hard delete that
     * cascades its lines ({@code orphanRemoval} / {@code ON DELETE CASCADE}) and leaves every
     * downstream baseline untouched (R6.5). The project-scope access guard runs first; a non-{@code
     * OPEN} amendment is refused {@link #NOT_OPEN} (R6.4).
     *
     * @param amendmentId the amendment to delete
     */
    public void deleteAmendment(Long amendmentId) {
        assertProjectAccess(amendmentId);
        EstimateAmendmentEntity amendment = loadAmendment(amendmentId);
        assertOpen(amendment);
        saveAudit(amendment, null, "DELETE");
        amendmentDao.deleteById(amendmentId);
        entityManager.flush();
    }

    // --- Internal helpers ----------------------------------------------------------------------

    /**
     * Resolves the project's {@code OPEN} amendment, creating it (eligibility-guarded) when none
     * exists (R3.5 / R2.2).
     */
    private EstimateAmendmentEntity resolveOrCreateOpen(Long projectId) {
        return amendmentDao.findByProjectIdAndStatus(projectId, AmendmentStatus.OPEN)
                .orElseGet(() -> {
                    assertEligible(projectId);
                    EstimateAmendmentEntity amendment = new EstimateAmendmentEntity();
                    amendment.setProject(reference(ProjectEntity.class, projectId));
                    amendment.setStatus(AmendmentStatus.OPEN);
                    amendment.setSeqNo(nextSeqNo(projectId));
                    return amendmentDao.save(amendment);
                });
    }

    /** The next per-project {@code seqNo} = {@code max + 1} (1 for the first amendment, R2.2). */
    private int nextSeqNo(Long projectId) {
        Integer max = amendmentDao.findMaxSeqNoByProjectId(projectId);
        return max == null ? 1 : max + 1;
    }

    /**
     * The eligibility guard (R2.5): the project must be {@code ACTIVE} and have a {@code SIGNED}
     * estimate baseline; otherwise {@link #NOT_ELIGIBLE} (400).
     */
    private void assertEligible(Long projectId) {
        ProjectEntity project = projectId == null ? null
                : entityManager.find(ProjectEntity.class, projectId);
        if (project == null || project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NOT_ELIGIBLE);
        }
        EstimateEntity estimate = estimateDao.findByProjectId(projectId).orElse(null);
        if (estimate == null || estimate.getStatus() != EstimateStatus.SIGNED) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NOT_ELIGIBLE);
        }
    }

    /** Loads an amendment by id or 404s (R6); the access guard is applied by the caller. */
    private EstimateAmendmentEntity loadAmendment(Long id) {
        return amendmentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id));
    }

    /** Every line mutation requires the amendment to be {@code OPEN} (R2.4/R6.4 — {@link #NOT_OPEN}). */
    private void assertOpen(EstimateAmendmentEntity amendment) {
        if (amendment.getStatus() != AmendmentStatus.OPEN) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_OPEN);
        }
    }

    /**
     * Validates an {@link AddLineRequest} per its {@link ChangeType} (R3.6) and builds the incoming
     * {@link AmendmentLineDelta} the merge resolver operates on, resolving the read-only
     * {@code VOLUME_CHANGE} baseline quantity from the signed estimate (R7.1).
     */
    private AmendmentLineDelta validateAndBuildIncoming(Long projectId, AddLineRequest request) {
        ChangeType changeType = request.changeType();
        AmendmentLineDelta delta = new AmendmentLineDelta(changeType);

        switch (changeType) {
            case SCHEDULE_CHANGE -> {
                Integer halfDays = request.scheduleDeltaHalfDays();
                // R3.6 — a schedule change must carry a non-zero signed half-day delta.
                if (halfDays == null || halfDays == 0) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_SCHEDULE_DELTA);
                }
                delta.setScheduleDeltaHalfDays(halfDays);
                // Optional target category (null = whole-project shift, R3.2).
                delta.setWorkCategoryId(request.workCategoryId());
            }
            case ADDITIONAL_WORK -> {
                requireCostFields(request);
                // R3.3 — a brand-new work's volume is a positive quantity.
                if (request.deltaQuantity().signum() <= 0) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_QUANTITY);
                }
                delta.setWorkItemId(request.workItemId());
                delta.setRoomId(request.roomId());
                delta.setDeltaQuantity(request.deltaQuantity());
                delta.setExtraUnitPrice(request.extraUnitPrice());
                // An additional work has no pre-existing baseline (its own volume is the result).
                delta.setBaselineQuantity(BigDecimal.ZERO);
            }
            case VOLUME_CHANGE -> {
                requireCostFields(request);
                // R3.6 — a volume change with a zero delta is rejected.
                if (request.deltaQuantity().signum() == 0) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_QUANTITY);
                }
                delta.setWorkItemId(request.workItemId());
                delta.setRoomId(request.roomId());
                delta.setDeltaQuantity(request.deltaQuantity());
                delta.setExtraUnitPrice(request.extraUnitPrice());
                // R7.1 — resolve the signed-estimate baseline quantity read-only for the
                // resulting-volume (non-negative) guard (R3.6).
                BigDecimal baseline =
                        resolveBaselineQuantity(projectId, request.workItemId(), request.roomId());
                delta.setBaselineQuantity(baseline);
                // The resulting volume (baseline + delta) must not go negative (R3.6).
                if (baseline.add(request.deltaQuantity()).signum() < 0) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, VOLUME_NEGATIVE);
                }
            }
            default -> throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_WORK_ITEM);
        }
        return delta;
    }

    /**
     * Shared cost-line field validation (R3.3/R3.4): {@code workItem}, {@code room},
     * {@code deltaQuantity} and a positive {@code extraUnitPrice} are required for
     * {@code ADDITIONAL_WORK} / {@code VOLUME_CHANGE}.
     */
    private void requireCostFields(AddLineRequest request) {
        if (request.workItemId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_WORK_ITEM);
        }
        if (request.roomId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_ROOM);
        }
        if (request.deltaQuantity() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, VALIDATION_QUANTITY);
        }
        // R3.6 — a non-positive unit price is invalid.
        if (request.extraUnitPrice() == null || request.extraUnitPrice().signum() <= 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PRICE_INVALID);
        }
    }

    /**
     * The signed-estimate baseline quantity for {@code (workItem, room)} (R7.1), read-only: the
     * project's estimate line for the work item, then that line's {@code (line, room)} per-room
     * quantity. {@code 0} when the work item is not on the estimate or has no quantity in the room —
     * a {@code VOLUME_CHANGE} against a missing baseline resolves the resulting volume to the delta
     * itself.
     */
    private BigDecimal resolveBaselineQuantity(Long projectId, Long workItemId, Long roomId) {
        if (projectId == null || workItemId == null || roomId == null) {
            return BigDecimal.ZERO;
        }
        for (EstimateLineEntity line
                : estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId)) {
            WorkItemEntity wi = line.getWorkItem();
            if (wi == null || !workItemId.equals(wi.getId())) {
                continue;
            }
            if (line.getRoomQtys() != null) {
                for (EstimateLineRoomQtyEntity rq : line.getRoomQtys()) {
                    if (rq.getRoom() != null && roomId.equals(rq.getRoom().getId())
                            && rq.getQuantity() != null) {
                        return rq.getQuantity();
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * The existing line of {@code amendment} that is the same {@code Amendment_Subject} as
     * {@code incoming} (R5.1), or {@code null} when none — a distinct subject simply appends (R5.5).
     */
    private AmendmentLineEntity findSameSubject(EstimateAmendmentEntity amendment,
                                                AmendmentLineDelta incoming) {
        AmendmentSubject incomingSubject = mergeResolver.subjectOf(incoming);
        for (AmendmentLineEntity line : amendment.getLines()) {
            AmendmentLineDelta existingDelta = toDelta(line, null);
            if (mergeResolver.subjectOf(existingDelta).equals(incomingSubject)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Projects a persisted line entity into the resolver's {@link AmendmentLineDelta} value type,
     * carrying {@code baselineQuantity} (the resulting-volume guard reads it on a cost-line merge).
     */
    private static AmendmentLineDelta toDelta(AmendmentLineEntity line, BigDecimal baselineQuantity) {
        return new AmendmentLineDelta(line.getChangeType())
                .setWorkItemId(line.getWorkItem() == null ? null : line.getWorkItem().getId())
                .setRoomId(line.getRoom() == null ? null : line.getRoom().getId())
                .setWorkCategoryId(
                        line.getWorkCategory() == null ? null : line.getWorkCategory().getId())
                .setDeltaQuantity(line.getDeltaQuantity())
                .setExtraUnitPrice(line.getExtraUnitPrice())
                .setScheduleDeltaHalfDays(line.getScheduleDeltaHalfDays())
                .setBaselineQuantity(baselineQuantity);
    }

    /**
     * Writes a resolved/validated {@link AmendmentLineDelta} back onto a line entity, mapping the
     * flat FK ids to managed references so the save persists the surviving line (append or
     * merge-in-place).
     */
    private void applyDeltaToEntity(AmendmentLineEntity line, AmendmentLineDelta delta) {
        line.setChangeType(delta.getChangeType());
        line.setWorkItem(reference(WorkItemEntity.class, delta.getWorkItemId()));
        line.setRoom(reference(RoomEntity.class, delta.getRoomId()));
        line.setWorkCategory(reference(WorkCategoryEntity.class, delta.getWorkCategoryId()));
        line.setDeltaQuantity(delta.getDeltaQuantity());
        line.setExtraUnitPrice(delta.getExtraUnitPrice());
        line.setScheduleDeltaHalfDays(delta.getScheduleDeltaHalfDays());
    }

    /** A managed JPA reference ({@code getReference}, no SELECT) for a non-null id, else {@code null}. */
    private <T> T reference(Class<T> type, Long id) {
        return id == null ? null : entityManager.getReference(type, id);
    }
}
