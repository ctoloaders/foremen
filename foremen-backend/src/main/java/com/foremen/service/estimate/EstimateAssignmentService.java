package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.foremen.dao.AdminDao;
import com.foremen.dao.AssortmentPositionPriceDao;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.matrix.EstimateMatrixAssembler;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.formula.VolumeResolver;
import com.foremen.service.model.EstimateLineRoomMaterialServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomMaterialServiceModel;
import com.foremen.service.model.mapper.EstimateLineRoomMaterialServiceMapper;
import com.foremen.service.pricing.FinishingPriceRangeResolver;
import com.foremen.service.pricing.PriceRangeResolver;

import jakarta.persistence.EntityManager;

/**
 * The matrix <b>write orchestrator</b> for the Estimate tab (FOR-05-05, design §B4) — the single
 * service that owns the persisting side of assigning works to rooms and freezing their copied prices.
 *
 * <p>It is a {@link ProjectScopedService} keyed on {@link EstimateLineRoomMaterialEntity} (the
 * estimate's frozen copied-price material line, design §B1), so its {@link #getProjectIdPath()}
 * resolves an entity's owning project through {@code roomQty.line.estimate.project}. That keeps the
 * ABAC list filter / by-id action guard consistent with the sibling estimate services
 * ({@code EstimateLineRoomQtyService} → {@code "line.estimate.project.id"}); the bespoke
 * orchestration methods below take the {@code projectId} directly and are guarded at the controller
 * (task 6.2) by {@code @RequiresPermission(ESTIMATE, UPDATE)}.
 *
 * <p><b>Frozen-price snapshot (R13.1, R13.2, R3.4).</b> On {@link #assign} the work's single catalog
 * labour price is copied onto the line's {@code unitPrice} (with a {@code workPrice} provenance FK),
 * and one {@link EstimateLineRoomMaterialEntity} is created per consumption type carrying the copied
 * {@code Type_Price_Range} — construction ranges via {@link PriceRangeResolver}, finishing ranges via
 * the <b>package-less</b> {@link FinishingPriceRangeResolver} (the widest honest band; R3.4). The
 * estimate owns its copies: later catalog price changes never retro-mutate it (R13.3).
 *
 * <p><b>One Volume per cell (R4.1).</b> The cell's Volume is resolved once by {@link VolumeResolver}
 * (applicable formula → unit fallback) against the room's 14 dimensions and stored as the room-qty
 * {@code quantity}; that same Volume drives labour and every material downstream.
 *
 * <p><b>Batched Save (R15.2, R15.3, R15.7).</b> {@link #applyAssignments} persists a whole staged set
 * — cell edits (assign/unassign/material add-remove/choose-concrete/bulk-choose) AND any
 * {@code APPLY_PACKAGE} / {@code RECOMPUTE_FINISHING}-originated staged edits — in one transaction,
 * re-copying the finishing ranges for recompute-originated edits, and finishes by calling the shipped
 * {@link EstimateRecomputeService#recomputeEstimate(EstimateEntity)} so line/estimate totals stay
 * derived. It is the only server write path for matrix edits.
 *
 * <p>The read-only {@code calculate*}/preview cores for {@code Apply_Package},
 * {@code Work_Row_Apply} and {@code Recompute_Finishing_Prices} (design §B4, tasks 5.3) and the
 * direct material {@code add}/{@code remove}/{@code chooseConcrete}/{@code bulkChoose} operations
 * (task 5.2) build on the same primitives; their entry points are declared here as clearly-marked
 * extension points so this task compiles and the follow-up tasks slot in without a signature churn.
 */
@Service
public class EstimateAssignmentService
        implements ProjectScopedService<EstimateLineRoomMaterialServiceModel,
        EstimateLineRoomMaterialServiceExtendedModel, EstimateLineRoomMaterialEntity, Long> {

    /** Message code for a missing referenced entity (404), reused from the sibling services. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private final EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;
    private final EstimateLineRoomMaterialServiceMapper estimateLineRoomMaterialServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    private final EstimateDao estimateDao;
    private final EstimateLineDao estimateLineDao;
    private final EstimateLineRoomQtyDao estimateLineRoomQtyDao;
    private final RoomDao roomDao;
    private final WorkItemDao workItemDao;
    private final WorkPriceDao workPriceDao;
    private final WorkMaterialConsumptionDao workMaterialConsumptionDao;
    private final WorkVolumeFormulaDao workVolumeFormulaDao;
    private final WorkPackageOverrideDao workPackageOverrideDao;
    private final OfferPackageDao offerPackageDao;
    private final ConstructionMaterialDao constructionMaterialDao;
    private final FinishingMaterialDao finishingMaterialDao;
    private final ConstructionMaterialTypeDao constructionMaterialTypeDao;
    private final MaterialTypeDao materialTypeDao;
    private final AssortmentPositionPriceDao assortmentPositionPriceDao;

    private final DraftGateGuard draftGateGuard;
    private final EstimateRecomputeService estimateRecomputeService;
    private final PriceRangeResolver priceRangeResolver;
    private final FinishingPriceRangeResolver finishingPriceRangeResolver;
    private final EstimateMatrixAssembler estimateMatrixAssembler;

    public EstimateAssignmentService(
            EstimateLineRoomMaterialDao estimateLineRoomMaterialDao,
            EstimateLineRoomMaterialServiceMapper estimateLineRoomMaterialServiceMapper,
            ProjectAccessCache projectAccessCache,
            AuditLogDao auditLogDao,
            EntityManager entityManager,
            EstimateDao estimateDao,
            EstimateLineDao estimateLineDao,
            EstimateLineRoomQtyDao estimateLineRoomQtyDao,
            RoomDao roomDao,
            WorkItemDao workItemDao,
            WorkPriceDao workPriceDao,
            WorkMaterialConsumptionDao workMaterialConsumptionDao,
            WorkVolumeFormulaDao workVolumeFormulaDao,
            WorkPackageOverrideDao workPackageOverrideDao,
            OfferPackageDao offerPackageDao,
            ConstructionMaterialDao constructionMaterialDao,
            FinishingMaterialDao finishingMaterialDao,
            ConstructionMaterialTypeDao constructionMaterialTypeDao,
            MaterialTypeDao materialTypeDao,
            AssortmentPositionPriceDao assortmentPositionPriceDao,
            DraftGateGuard draftGateGuard,
            EstimateRecomputeService estimateRecomputeService,
            PriceRangeResolver priceRangeResolver,
            FinishingPriceRangeResolver finishingPriceRangeResolver,
            EstimateMatrixAssembler estimateMatrixAssembler) {
        this.estimateLineRoomMaterialDao = estimateLineRoomMaterialDao;
        this.estimateLineRoomMaterialServiceMapper = estimateLineRoomMaterialServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.estimateDao = estimateDao;
        this.estimateLineDao = estimateLineDao;
        this.estimateLineRoomQtyDao = estimateLineRoomQtyDao;
        this.roomDao = roomDao;
        this.workItemDao = workItemDao;
        this.workPriceDao = workPriceDao;
        this.workMaterialConsumptionDao = workMaterialConsumptionDao;
        this.workVolumeFormulaDao = workVolumeFormulaDao;
        this.workPackageOverrideDao = workPackageOverrideDao;
        this.offerPackageDao = offerPackageDao;
        this.constructionMaterialDao = constructionMaterialDao;
        this.finishingMaterialDao = finishingMaterialDao;
        this.constructionMaterialTypeDao = constructionMaterialTypeDao;
        this.materialTypeDao = materialTypeDao;
        this.assortmentPositionPriceDao = assortmentPositionPriceDao;
        this.draftGateGuard = draftGateGuard;
        this.estimateRecomputeService = estimateRecomputeService;
        this.priceRangeResolver = priceRangeResolver;
        this.finishingPriceRangeResolver = finishingPriceRangeResolver;
        this.estimateMatrixAssembler = estimateMatrixAssembler;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<EstimateLineRoomMaterialEntity, Long> getDao() {
        return estimateLineRoomMaterialDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<EstimateLineRoomMaterialEntity, EstimateLineRoomMaterialServiceModel,
            EstimateLineRoomMaterialServiceExtendedModel> getMapper() {
        return estimateLineRoomMaterialServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<EstimateLineRoomMaterialEntity> getDaoModelClass() {
        return EstimateLineRoomMaterialEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. A material line resolves its project boundary through
     * {@code roomQty.line.estimate.project}, so the project-id path is the dotted association path
     * {@code "roomQty.line.estimate.project.id"} (mirroring {@code EstimateLineRoomQtyService}'s
     * {@code "line.estimate.project.id"} one hop deeper).
     */
    @Override
    public String getProjectIdPath() {
        return "roomQty.line.estimate.project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // =====================================================================================
    // Bespoke write orchestration — the matrix's persisting operations (design §B4)
    // =====================================================================================

    /**
     * Assigns {@code workItemId} to {@code roomId} in the project's estimate (R3.1–R3.5): creates or
     * extends the {@link EstimateLineEntity} for the work, adds an {@link EstimateLineRoomQtyEntity}
     * whose {@code quantity} is the resolved Volume, copies the work's labour price onto the line, and
     * creates one frozen {@link EstimateLineRoomMaterialEntity} per consumption type with the copied
     * {@code Type_Price_Range}. Idempotent per {@code (line, room)}: re-assigning an existing cell
     * refreshes its Volume but does not duplicate the room-qty.
     *
     * @param projectId  the owning project's estimate to write into
     * @param workItemId the catalog work to assign
     * @param roomId     the room (column) to assign it to
     * @param packageCode the active offer-package code driving the override formula, or {@code null}
     * @return the created/existing {@code (line, room)} room-qty, with its material lines populated
     */
    @Transactional
    public EstimateLineRoomQtyEntity assign(Long projectId, Long workItemId, Long roomId, String packageCode) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);
        WorkItemEntity workItem = resolveWorkItem(workItemId);
        RoomEntity room = resolveRoom(roomId);
        assertRoomInProject(room, projectId);
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);

        EstimateLineRoomQtyEntity roomQty = doAssign(estimate, workItem, room, offerPackage);

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
        return roomQty;
    }

    /**
     * Unassigns {@code workItemId} from {@code roomId} (R15.3): removes the {@code (line, room)}
     * room-qty (its material lines cascade away via the owner collection's orphan removal), and drops
     * the owning line entirely once it has no rooms left. A no-op when the cell is not assigned.
     *
     * @param projectId  the owning project's estimate
     * @param workItemId the catalog work to unassign
     * @param roomId     the room (column) to unassign from
     */
    @Transactional
    public void unassign(Long projectId, Long workItemId, Long roomId) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);
        EstimateLineEntity line = findLine(estimate, workItemId);
        if (line == null) {
            return; // nothing assigned for this work -> no-op
        }
        doUnassign(estimate, line, roomId);

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
    }

    /**
     * The single batched {@code Save} write path (design §B4, R15.2, R15.3, R15.7): persists the whole
     * staged set in one transaction and finishes by recomputing the estimate's totals.
     *
     * <p>Every {@link StagedEdit} is replayed in order onto the estimate's entity graph. Cell edits
     * (assign / unassign / material add-remove / choose-concrete / bulk-choose) and any
     * {@code APPLY_PACKAGE} / {@code RECOMPUTE_FINISHING}-originated staged edits go through the same
     * upsert primitives; a {@code RECOMPUTE_FINISHING} edit re-copies the package-scoped finishing
     * range onto the already-assigned finishing material lines (R12.2, R12.5) without touching
     * construction, labour, volumes, or a chosen concrete product (R12.3, R12.4).
     *
     * @param projectId   the owning project's estimate to persist into
     * @param stagedEdits the ordered staged set to apply (may be empty)
     * @return the recomputed owning estimate
     */
    @Transactional
    public EstimateEntity applyAssignments(Long projectId, List<StagedEdit> stagedEdits) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);

        if (stagedEdits != null) {
            for (StagedEdit edit : stagedEdits) {
                applyStagedEdit(estimate, projectId, edit);
            }
        }

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
        return estimate;
    }

    // =====================================================================================
    // Matrix read model + write-then-read entry points for the controller (design §B6, task 6.2)
    // =====================================================================================

    /**
     * Assembles the full Estimate tab read model for {@code projectId} (design §B6,
     * {@code GET /project/{projectId}/matrix}). Read-only: it loads the project's estimate and
     * delegates to the {@link EstimateMatrixAssembler}, which lays out the works&times;rooms grid,
     * per-cell cost ranges + fill state, group subtotals, header totals, and the fill indicator from
     * the estimate's frozen copied prices. The {@code editable} flag (project DRAFT + caller has
     * {@code ESTIMATE} UPDATE, R15.5) is resolved by the controller and passed through.
     *
     * @param projectId the owning project whose estimate to render
     * @param editable  whether the estimate may be written (DRAFT + caller ESTIMATE UPDATE, R15.5)
     * @return the assembled matrix read model
     */
    @Transactional(readOnly = true)
    public EstimateMatrixDto getMatrix(Long projectId, boolean editable) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        return estimateMatrixAssembler.assemble(estimate, projectId, editable);
    }

    /**
     * Whether the project's estimate is currently in {@link EstimateStatus#DRAFT} — the lifecycle
     * half of the {@code editable} flag (R15.5). The controller combines it with the caller's
     * {@code ESTIMATE} UPDATE grant to compute {@code editable}.
     *
     * @param projectId the owning project whose estimate lifecycle to read
     * @return {@code true} iff the estimate exists and is DRAFT
     */
    @Transactional(readOnly = true)
    public boolean isDraft(Long projectId) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        return estimate.getStatus() == EstimateStatus.DRAFT;
    }

    /**
     * The single batched Save (design §B4/§B6, {@code POST /project/{projectId}/assignments}): persists
     * the whole staged set through {@link #applyAssignments} and returns the refreshed matrix read
     * model so the client can replace its state and clear the staged store (R15.2, R15.7). A Save is
     * only reachable for an {@code ESTIMATE} UPDATE caller against a DRAFT estimate, so the returned
     * matrix is always {@code editable = true}.
     *
     * @param projectId   the owning project's estimate to persist into
     * @param stagedEdits the ordered staged set to apply (may be empty)
     * @return the assembled matrix read model after the batched write
     */
    @Transactional
    public EstimateMatrixDto saveAndAssemble(Long projectId, List<StagedEdit> stagedEdits) {
        EstimateEntity estimate = applyAssignments(projectId, stagedEdits);
        return estimateMatrixAssembler.assemble(estimate, projectId, true);
    }

    /**
     * Read-only {@code Apply_Package} preview (design §B4/§B6, {@code POST .../apply-package}): computes
     * the apply plan via the pure {@link #calculateApplyPackage} core, replays it onto the in-memory
     * estimate graph exactly as the batched Save would, assembles the resulting matrix, and then
     * <b>rolls the transaction back</b> so nothing is persisted (R11.2, R15.6). The returned matrix is
     * the preview the client stages as undoable {@code APPLY_PACKAGE} edits; the actual write happens
     * only on the batched Save.
     *
     * @param projectId   the owning project's estimate to preview against
     * @param packageCode the package whose member works to apply
     * @param editable    the caller's editable flag (carried onto the preview matrix)
     * @return the preview matrix (never persisted)
     */
    @Transactional
    public EstimateMatrixDto previewApplyPackage(Long projectId, String packageCode, boolean editable) {
        CalculatedApply plan = calculateApplyPackage(projectId, packageCode);
        return assemblePreview(projectId, plan.toStagedEdits(), editable);
    }

    /**
     * Read-only work-row apply (hammer) preview (design §B4/§B6, {@code POST .../apply-work}): computes
     * the single-work apply plan via {@link #calculateApplyWorkToRooms}, replays it as a preview, and
     * rolls back so nothing persists (R9.5, R15.6).
     *
     * @param projectId   the owning project's estimate to preview against
     * @param workItemId  the single work to apply over its Room_Type_Attachment
     * @param packageCode the active package driving the override formula + assortment, or {@code null}
     * @param editable    the caller's editable flag (carried onto the preview matrix)
     * @return the preview matrix (never persisted)
     */
    @Transactional
    public EstimateMatrixDto previewApplyWork(
            Long projectId, Long workItemId, String packageCode, boolean editable) {
        CalculatedApply plan = calculateApplyWorkToRooms(projectId, workItemId, packageCode);
        return assemblePreview(projectId, plan.toStagedEdits(), editable);
    }

    /**
     * Read-only {@code Recompute_Finishing_Prices} preview (design §B4/§B6,
     * {@code POST .../recompute-finishing}): computes the recompute plan via
     * {@link #calculateRecomputeFinishingPrices}, replays the {@code RECOMPUTE_FINISHING} edits onto
     * the in-memory graph (re-copying finishing ranges only), assembles the resulting matrix, and rolls
     * back so nothing persists (R12.2, R15.6). When nothing is recomputable (R12.6) the plan is empty,
     * so the preview equals the current matrix — the client indicates that nothing was recomputable.
     *
     * @param projectId   the owning project's estimate to preview against
     * @param packageCode the package whose finishing prices to recompute
     * @param editable    the caller's editable flag (carried onto the preview matrix)
     * @return the preview matrix (never persisted)
     */
    @Transactional
    public EstimateMatrixDto previewRecomputeFinishing(Long projectId, String packageCode, boolean editable) {
        CalculatedRecompute plan = calculateRecomputeFinishingPrices(projectId, packageCode);
        return assemblePreview(projectId, plan.toStagedEdits(), editable);
    }

    /**
     * Replays {@code stagedEdits} onto the project's estimate graph, assembles the resulting matrix,
     * and marks the current transaction <b>rollback-only</b> so the preview mutations are discarded and
     * NOTHING is persisted (R15.6). The graph is mutated only to produce a faithful preview identical
     * to what the batched Save would yield; rolling back guarantees the calculate/preview endpoints
     * stay pure reads (they carry {@code ESTIMATE} READ, R19.1). The recompute is intentionally skipped
     * here — the preview needs only the assembled read model, not persisted derived totals.
     */
    private EstimateMatrixDto assemblePreview(Long projectId, List<StagedEdit> stagedEdits, boolean editable) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        if (stagedEdits != null) {
            for (StagedEdit edit : stagedEdits) {
                applyStagedEdit(estimate, projectId, edit);
            }
        }
        entityManager.flush(); // materialize ids/collections for a faithful assembly
        EstimateMatrixDto preview = estimateMatrixAssembler.assemble(estimate, projectId, editable);
        // Discard every preview mutation — the calculate/preview endpoints persist nothing (R15.6).
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return preview;
    }

    /** Replays a single staged edit onto the estimate graph. */
    private void applyStagedEdit(EstimateEntity estimate, Long projectId, StagedEdit edit) {
        if (edit == null || edit.kind() == null) {
            return;
        }
        switch (edit.kind()) {
            case ASSIGN -> {
                WorkItemEntity workItem = resolveWorkItem(edit.workItemId());
                RoomEntity room = resolveRoom(edit.roomId());
                assertRoomInProject(room, projectId);
                doAssign(estimate, workItem, room, resolvePackageOrNull(edit.packageCode()));
            }
            case UNASSIGN -> {
                EstimateLineEntity line = findLine(estimate, edit.workItemId());
                if (line != null) {
                    doUnassign(estimate, line, edit.roomId());
                }
            }
            // Cell material edits and apply/recompute-originated edits share the primitives below.
            // APPLY_PACKAGE stages a batch of ASSIGN-shaped rows (its own computation is the
            // read-only calculate core of task 5.3); each staged row is replayed as a plain assign,
            // so no distinct persistence branch is required here.
            case APPLY_PACKAGE -> {
                WorkItemEntity workItem = resolveWorkItem(edit.workItemId());
                RoomEntity room = resolveRoom(edit.roomId());
                assertRoomInProject(room, projectId);
                doAssign(estimate, workItem, room, resolvePackageOrNull(edit.packageCode()));
            }
            case RECOMPUTE_FINISHING -> recopyFinishingRange(estimate, edit);
            case ADD_MATERIAL, REMOVE_MATERIAL, CHOOSE_CONCRETE, BULK_CHOOSE_CONCRETE ->
                    applyMaterialEdit(estimate, edit);
        }
    }

    // =====================================================================================
    // Material edits — add / remove / choose-concrete / bulk-choose (design §B4, task 5.2)
    // =====================================================================================

    /**
     * Adds a {@link EstimateLineRoomMaterialEntity} of {@code branch}/{@code typeId} to the cell's
     * room-qty, copying the {@code Type_Price_Range} on add (R6.2): construction ranges via
     * {@link PriceRangeResolver}, finishing ranges via the package-less {@link FinishingPriceRangeResolver}
     * (the assignment-default widest honest band, R3.4). Idempotent per {@code (roomQty, branch, type)}:
     * an already-present line of the same key is returned unchanged rather than duplicated (the DB
     * {@code UNIQUE (room_qty_id, branch, construction_type_id, finishing_type_id)} keying, R13.4).
     *
     * @param projectId  the owning project's estimate to write into
     * @param roomQtyId  the cell (room-qty) to add the material line to
     * @param branch     the material branch (construction | finishing)
     * @param typeId     the material type id of the branch
     * @return the created (or already-present) material line
     */
    @Transactional
    public EstimateLineRoomMaterialEntity addMaterialLine(
            Long projectId, Long roomQtyId, ConsumptionBranch branch, Long typeId) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);
        EstimateLineRoomQtyEntity roomQty = resolveRoomQtyInEstimate(roomQtyId, estimate);

        EstimateLineRoomMaterialEntity material = doAddMaterialLine(roomQty, branch, typeId);

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
        return material;
    }

    /**
     * Removes the {@code (roomQty, branch, type)} material line from the cell (R6.2). A no-op when no
     * such line exists. The row is removed through its owner collection so orphan removal deletes it.
     *
     * @param projectId  the owning project's estimate
     * @param roomQtyId  the cell (room-qty) to remove the material line from
     * @param branch     the material branch (construction | finishing)
     * @param typeId     the material type id of the branch
     */
    @Transactional
    public void removeMaterialLine(Long projectId, Long roomQtyId, ConsumptionBranch branch, Long typeId) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);
        EstimateLineRoomQtyEntity roomQty = resolveRoomQtyInEstimate(roomQtyId, estimate);

        doRemoveMaterialLine(roomQty, branch, typeId);

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
    }

    /**
     * Chooses a concrete product for a material line (R6.3, R6.4): sets the branch-matched concrete
     * FK and copies the product's {@code retailNet} into {@code concreteNet}, collapsing the line to a
     * point. The concrete product's branch must match the material line's branch.
     *
     * @param projectId      the owning project's estimate
     * @param materialLineId the target material line
     * @param materialId     the chosen concrete product id (a construction or finishing material,
     *                       matching the line's branch)
     * @return the updated material line
     */
    @Transactional
    public EstimateLineRoomMaterialEntity chooseConcrete(Long projectId, Long materialLineId, Long materialId) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);
        EstimateLineRoomMaterialEntity material = resolveMaterialLine(materialLineId);
        assertMaterialInEstimate(material, estimate);

        applyChooseConcrete(material, materialId);

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
        return material;
    }

    /**
     * Applies a concrete product to a {@code (branch, type)} across ALL of a work's assigned cells
     * (Work_Material_Summary bulk fill, R9.3): for every {@code (line, room)} room-qty of the work's
     * line, the matching material line has its concrete product set and its {@code concreteNet} copied
     * (collapsing each affected line). A no-op when the work is unassigned.
     *
     * @param projectId  the owning project's estimate
     * @param workItemId the work whose cells to fill
     * @param branch     the material branch (construction | finishing)
     * @param typeId     the material type id to fill across the work's cells
     * @param materialId the chosen concrete product id (matching the branch)
     */
    @Transactional
    public void bulkChooseConcreteForWork(
            Long projectId, Long workItemId, ConsumptionBranch branch, Long typeId, Long materialId) {
        EstimateEntity estimate = resolveDraftEstimate(projectId);
        EstimateLineEntity line = findLine(estimate, workItemId);
        if (line == null) {
            return; // work unassigned -> nothing to bulk-fill
        }

        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            EstimateLineRoomMaterialEntity material = findMaterialLine(roomQty, branch, typeId);
            if (material != null) {
                applyChooseConcrete(material, materialId);
            }
        }

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
    }

    // --- material-edit primitives (shared by the public entry points + staged replay) -------

    /**
     * Adds (or returns the existing) material line of {@code (branch, type)} on {@code roomQty},
     * copying the copied {@code Type_Price_Range} and the consumption norm from the work's consumption
     * for that type when present. Does NOT recompute.
     */
    private EstimateLineRoomMaterialEntity doAddMaterialLine(
            EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        EstimateLineRoomMaterialEntity existing = findMaterialLine(roomQty, branch, typeId);
        if (existing != null) {
            return existing; // keyed by (roomQty, branch, type) -> no duplicate (R13.4)
        }

        EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
        material.setRoomQty(roomQty);
        material.setBranch(branch);
        material.setNormQty(resolveNormQty(roomQty, branch, typeId));

        if (branch == ConsumptionBranch.construction) {
            ConstructionMaterialTypeEntity type = resolveConstructionType(typeId);
            material.setConstructionType(type);
            PriceRangeResolver.PriceRange range = priceRangeResolver.rangeFor(
                    constructionMaterialDao.findByActiveTrueAndRetailNetNotNull(), typeId);
            material.setRangeMin(range.min());
            material.setRangeMax(range.max());
        } else if (branch == ConsumptionBranch.finishing) {
            MaterialTypeEntity type = resolveFinishingType(typeId);
            material.setFinishingType(type);
            // Assignment default is the package-less fold across all packages (R3.4).
            FinishingPriceRangeResolver.PriceRange range = finishingPriceRangeResolver.rangeFor(
                    finishingMaterialDao.findByActiveTrueAndRetailNetNotNull(), typeId, null);
            material.setRangeMin(range.min());
            material.setRangeMax(range.max());
        } else {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ENTITY_NOT_FOUND_MESSAGE, "branch", branch);
        }

        estimateLineRoomMaterialDao.save(material);
        roomQty.getMaterials().add(material);
        return material;
    }

    /** Removes the {@code (branch, type)} material line from {@code roomQty} via orphan removal. */
    private void doRemoveMaterialLine(EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        EstimateLineRoomMaterialEntity material = findMaterialLine(roomQty, branch, typeId);
        if (material == null) {
            return;
        }
        roomQty.getMaterials().remove(material);
        estimateLineRoomMaterialDao.delete(material);
        entityManager.flush();
    }

    /**
     * Sets the branch-matched concrete product on {@code material} and copies its {@code retailNet}
     * into {@code concreteNet}, collapsing the line to a point (R6.4). The chosen product's branch
     * must match the material line's branch.
     */
    private void applyChooseConcrete(EstimateLineRoomMaterialEntity material, Long materialId) {
        if (material.getBranch() == ConsumptionBranch.construction) {
            ConstructionMaterialEntity product = materialId == null ? null
                    : constructionMaterialDao.findById(materialId).orElse(null);
            if (product == null) {
                throw new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "materialId", materialId);
            }
            material.setConcreteConstructionMaterial(product);
            material.setConcreteFinishingMaterial(null);
            material.setConcreteNet(product.getRetailNet());
        } else if (material.getBranch() == ConsumptionBranch.finishing) {
            FinishingMaterialEntity product = materialId == null ? null
                    : finishingMaterialDao.findById(materialId).orElse(null);
            if (product == null) {
                throw new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "materialId", materialId);
            }
            material.setConcreteFinishingMaterial(product);
            material.setConcreteConstructionMaterial(null);
            material.setConcreteNet(product.getRetailNet());
        } else {
            throw new ForemenApiException(
                    HttpStatus.BAD_REQUEST, ENTITY_NOT_FOUND_MESSAGE, "branch", material.getBranch());
        }
        estimateLineRoomMaterialDao.save(material);
    }

    /** The {@code (branch, type)} material line of {@code roomQty}, or {@code null} when absent. */
    private EstimateLineRoomMaterialEntity findMaterialLine(
            EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        if (branch == null || typeId == null) {
            return null;
        }
        for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
            if (material.getBranch() != branch) {
                continue;
            }
            Long lineTypeId = branch == ConsumptionBranch.construction
                    ? (material.getConstructionType() != null ? material.getConstructionType().getId() : null)
                    : (material.getFinishingType() != null ? material.getFinishingType().getId() : null);
            if (typeId.equals(lineTypeId)) {
                return material;
            }
        }
        return null;
    }

    /**
     * The consumption norm the work declares for {@code (branch, type)}, or {@code null} when the work
     * has no consumption for that type (an ad-hoc material line added beyond the work's declared
     * consumption still gets a null norm — it contributes no physical quantity until edited).
     */
    private BigDecimal resolveNormQty(EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        EstimateLineEntity line = roomQty.getLine();
        WorkItemEntity workItem = line != null ? line.getWorkItem() : null;
        if (workItem == null) {
            return null;
        }
        List<WorkMaterialConsumptionEntity> consumptions =
                workMaterialConsumptionDao.findByWorkItemIdIn(List.of(workItem.getId()));
        for (WorkMaterialConsumptionEntity consumption : consumptions) {
            if (consumption.getBranch() != branch) {
                continue;
            }
            Long consumptionTypeId = branch == ConsumptionBranch.construction
                    ? (consumption.getConstructionMaterialType() != null
                            ? consumption.getConstructionMaterialType().getId() : null)
                    : (consumption.getFinishingMaterialType() != null
                            ? consumption.getFinishingMaterialType().getId() : null);
            if (typeId.equals(consumptionTypeId)) {
                return consumption.getNormQty();
            }
        }
        return null;
    }

    /** Resolves a construction material type by id (404 when missing). */
    private ConstructionMaterialTypeEntity resolveConstructionType(Long typeId) {
        ConstructionMaterialTypeEntity type = typeId == null ? null
                : constructionMaterialTypeDao.findById(typeId).orElse(null);
        if (type == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "typeId", typeId);
        }
        return type;
    }

    /** Resolves a finishing material type by id (404 when missing). */
    private MaterialTypeEntity resolveFinishingType(Long typeId) {
        MaterialTypeEntity type = typeId == null ? null
                : materialTypeDao.findById(typeId).orElse(null);
        if (type == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "typeId", typeId);
        }
        return type;
    }

    /** Resolves a material line by id (404 when missing). */
    private EstimateLineRoomMaterialEntity resolveMaterialLine(Long materialLineId) {
        EstimateLineRoomMaterialEntity material = materialLineId == null ? null
                : estimateLineRoomMaterialDao.findById(materialLineId).orElse(null);
        if (material == null) {
            throw new ForemenApiException(
                    HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "materialLineId", materialLineId);
        }
        return material;
    }

    /** Resolves a room-qty by id and asserts it belongs to the target estimate. */
    private EstimateLineRoomQtyEntity resolveRoomQtyInEstimate(Long roomQtyId, EstimateEntity estimate) {
        EstimateLineRoomQtyEntity roomQty = roomQtyId == null ? null
                : estimateLineRoomQtyDao.findById(roomQtyId).orElse(null);
        if (roomQty == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "roomQtyId", roomQtyId);
        }
        EstimateLineEntity line = roomQty.getLine();
        EstimateEntity owning = line != null ? line.getEstimate() : null;
        if (owning == null || !owning.getId().equals(estimate.getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "roomQtyId", roomQtyId);
        }
        return roomQty;
    }

    // --- assign / unassign primitives -------------------------------------------------------

    /**
     * Creates/extends the line for {@code workItem} in {@code estimate}, upserts the {@code (line,
     * room)} room-qty with the resolved Volume, and (on a fresh room-qty) seeds one frozen material
     * line per consumption type. Returns the persisted room-qty. Does NOT recompute — the public
     * entry points recompute once after the whole batch.
     */
    private EstimateLineRoomQtyEntity doAssign(
            EstimateEntity estimate, WorkItemEntity workItem, RoomEntity room, OfferPackageEntity offerPackage) {
        EstimateLineEntity line = findLine(estimate, workItem.getId());
        if (line == null) {
            line = createLine(estimate, workItem);
        }

        BigDecimal volume = resolveVolume(workItem, offerPackage, room);

        EstimateLineRoomQtyEntity roomQty = findRoomQty(line, room.getId());
        if (roomQty == null) {
            roomQty = new EstimateLineRoomQtyEntity();
            roomQty.setLine(line);
            roomQty.setRoom(room);
            roomQty.setQuantity(volume);
            estimateLineRoomQtyDao.save(roomQty);
            line.getRoomQtys().add(roomQty);
            seedMaterialLines(workItem, roomQty, offerPackage);
        } else {
            // Re-assigning an already-assigned cell refreshes its Volume without duplicating rows.
            roomQty.setQuantity(volume);
            estimateLineRoomQtyDao.save(roomQty);
        }
        return roomQty;
    }

    /**
     * Removes the {@code (line, room)} room-qty (its material lines cascade via orphan removal) and
     * drops the line when it has no rooms left. Does NOT recompute.
     */
    private void doUnassign(EstimateEntity estimate, EstimateLineEntity line, Long roomId) {
        EstimateLineRoomQtyEntity roomQty = findRoomQty(line, roomId);
        if (roomQty == null) {
            return;
        }
        line.getRoomQtys().remove(roomQty);
        estimateLineRoomQtyDao.delete(roomQty);
        entityManager.flush();

        if (line.getRoomQtys().isEmpty()) {
            estimate.getLines().remove(line);
            estimateLineDao.delete(line);
            entityManager.flush();
        }
    }

    /** Creates a new {@link EstimateLineEntity} for {@code workItem}, copying the frozen labour price. */
    private EstimateLineEntity createLine(EstimateEntity estimate, WorkItemEntity workItem) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(workItem);
        line.setUnit(workItem.getUnit());

        // Frozen labour price copy + provenance FK (R3.4, R13.1).
        WorkPriceEntity workPrice = workPriceDao.findByWorkItemId(workItem.getId()).orElse(null);
        line.setWorkPrice(workPrice);
        line.setUnitPrice(workPrice != null && workPrice.getNetPrice() != null
                ? workPrice.getNetPrice()
                : BigDecimal.ZERO);
        line.setQuantity(BigDecimal.ZERO);
        line.setValueNet(BigDecimal.ZERO);

        estimateLineDao.save(line);
        estimate.getLines().add(line);
        return line;
    }

    /**
     * Seeds one frozen {@link EstimateLineRoomMaterialEntity} per consumption type of {@code workItem}
     * onto a freshly created room-qty, copying the {@code Type_Price_Range} (R3.4, R13.2): construction
     * ranges from {@link PriceRangeResolver}, finishing ranges from the package-less
     * {@link FinishingPriceRangeResolver} (R3.4 — the widest honest band when no package context).
     */
    private void seedMaterialLines(
            WorkItemEntity workItem, EstimateLineRoomQtyEntity roomQty, OfferPackageEntity offerPackage) {
        List<WorkMaterialConsumptionEntity> consumptions =
                workMaterialConsumptionDao.findByWorkItemIdIn(List.of(workItem.getId()));
        if (consumptions.isEmpty()) {
            return;
        }

        Collection<ConstructionMaterialEntity> constructionMaterials = null;
        Collection<FinishingMaterialEntity> finishingMaterials = null;

        for (WorkMaterialConsumptionEntity consumption : consumptions) {
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setRoomQty(roomQty);
            material.setBranch(consumption.getBranch());
            material.setNormQty(consumption.getNormQty());

            if (consumption.getBranch() == ConsumptionBranch.construction
                    && consumption.getConstructionMaterialType() != null) {
                Long typeId = consumption.getConstructionMaterialType().getId();
                material.setConstructionType(consumption.getConstructionMaterialType());
                if (constructionMaterials == null) {
                    constructionMaterials = constructionMaterialDao.findByActiveTrueAndRetailNetNotNull();
                }
                PriceRangeResolver.PriceRange range = priceRangeResolver.rangeFor(constructionMaterials, typeId);
                material.setRangeMin(range.min());
                material.setRangeMax(range.max());
            } else if (consumption.getBranch() == ConsumptionBranch.finishing
                    && consumption.getFinishingMaterialType() != null) {
                Long typeId = consumption.getFinishingMaterialType().getId();
                material.setFinishingType(consumption.getFinishingMaterialType());
                if (finishingMaterials == null) {
                    finishingMaterials = finishingMaterialDao.findByActiveTrueAndRetailNetNotNull();
                }
                // Assignment default is the package-less fold across all packages (R3.4).
                FinishingPriceRangeResolver.PriceRange range =
                        finishingPriceRangeResolver.rangeFor(finishingMaterials, typeId, null);
                material.setRangeMin(range.min());
                material.setRangeMax(range.max());
            } else {
                continue; // malformed consumption (branch/type mismatch) -> skip, do not seed a row
            }

            estimateLineRoomMaterialDao.save(material);
            roomQty.getMaterials().add(material);
        }
    }

    /** Resolves the cell's single Volume (R4.1): applicable formula → unit fallback, vs the room's dims. */
    private BigDecimal resolveVolume(WorkItemEntity workItem, OfferPackageEntity offerPackage, RoomEntity room) {
        WorkVolumeFormulaEntity defaultFormula =
                workVolumeFormulaDao.findByWorkItemId(workItem.getId()).orElse(null);
        WorkPackageOverrideEntity override = offerPackage == null ? null
                : workPackageOverrideDao
                        .findByWorkItemIdAndOfferPackageId(workItem.getId(), offerPackage.getId())
                        .orElse(null);
        String unitCode = workItem.getUnit() != null ? workItem.getUnit().getCode() : null;
        VolumeResolver.Resolution resolution = VolumeResolver.resolve(override, defaultFormula, unitCode, room);
        return resolution.volume();
    }

    /** Re-copies the package-scoped finishing range onto an already-assigned finishing material line. */
    private void recopyFinishingRange(EstimateEntity estimate, StagedEdit edit) {
        EstimateLineRoomMaterialEntity material = edit.materialLineId() == null ? null
                : estimateLineRoomMaterialDao.findById(edit.materialLineId()).orElse(null);
        if (material == null || material.getBranch() != ConsumptionBranch.finishing
                || material.getFinishingType() == null) {
            return; // recompute touches finishing lines only (R12.3)
        }
        assertMaterialInEstimate(material, estimate);
        OfferPackageEntity offerPackage = resolvePackageOrNull(edit.packageCode());
        Long packageId = offerPackage != null ? offerPackage.getId() : null;
        FinishingPriceRangeResolver.PriceRange range = finishingPriceRangeResolver.rangeFor(
                finishingMaterialDao.findByActiveTrueAndRetailNetNotNull(),
                material.getFinishingType().getId(),
                packageId);
        if (range.min() == null && range.max() == null) {
            return; // no recomputable finishing prices for this type in the package -> leave unchanged (R12.6)
        }
        material.setRangeMin(range.min());
        material.setRangeMax(range.max());
        estimateLineRoomMaterialDao.save(material);
    }

    // =====================================================================================
    // Read-only calculate / preview cores — Apply_Package, Work_Row_Apply, Recompute (task 5.3)
    // =====================================================================================

    /**
     * <b>Read-only (persists NOTHING).</b> Computes what an {@code Apply_Package} of {@code packageCode}
     * would stage (design §B4, R11.2–R11.7, R10.2/R10.3, R15.3/R15.6): for every work whose
     * {@link WorkPackageOverrideEntity#getMember() member} flag is set under the package, it plans the
     * attachment to the applicable rooms (the work's {@code Room_Type_Attachment}, or ALL rooms when
     * empty — R10.2/R10.3), the per-room Volume from the package override AST (else the default
     * formula, via {@link VolumeResolver}), <b>layering</b> without touching already-assigned cells
     * (R11.5), and the finishing {@code Assortment_Placeholder}s seeded by material-type match against
     * the package's assortment positions (R11.6/R11.8).
     *
     * <p>It returns the {@link CalculatedApply} for the client to stage into the matrix (undoable /
     * redoable, R15.3), written to the server only through the batched {@link #applyAssignments} Save
     * (R15.6). Nothing is written here.
     *
     * @param projectId   the owning project's estimate to plan against
     * @param packageCode the package whose member works to apply (a blank/unknown code plans nothing)
     * @return the calculated, non-persisted apply plan
     */
    @Transactional(readOnly = true)
    public CalculatedApply calculateApplyPackage(Long projectId, String packageCode) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);
        if (offerPackage == null) {
            return CalculatedApply.EMPTY;
        }

        List<WorkItemEntity> memberWorks = memberWorksOf(offerPackage);
        List<RoomEntity> rooms = roomDao.findByProjectId(projectId);
        Set<CellKey> existing = existingAssignments(estimate);
        Map<Long, AssortmentPositionPriceEntity> assortmentByType = assortmentPricesByType(offerPackage);

        List<AssignmentPlan> plans = new ArrayList<>();
        for (WorkItemEntity work : memberWorks) {
            planWorkOverRooms(work, offerPackage, rooms, existing, assortmentByType, plans);
        }
        return new CalculatedApply(offerPackage.getCode(), List.copyOf(plans));
    }

    /**
     * <b>Read-only (persists NOTHING).</b> The work-row hammer's calculate core (design §B4, R9.5/R9.6,
     * R10.2/R10.3, R15.3): the same layering computation as {@link #calculateApplyPackage} scoped to a
     * single work over its {@code Room_Type_Attachment} (all rooms when empty), computing each attached
     * room's Volume individually by the work's formula and <b>never</b> overriding an already-assigned
     * cell's Volume (R9.6). The optional {@code packageCode} supplies the override formula + the
     * finishing assortment placeholders exactly as a package apply would, or plans against the default
     * formula and no assortment placeholders when {@code null}.
     *
     * @param projectId   the owning project's estimate to plan against
     * @param workItemId  the single work to apply to its attached rooms
     * @param packageCode the active package driving the override formula + assortment, or {@code null}
     * @return the calculated, non-persisted apply plan for the one work
     */
    @Transactional(readOnly = true)
    public CalculatedApply calculateApplyWorkToRooms(Long projectId, Long workItemId, String packageCode) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        WorkItemEntity work = resolveWorkItem(workItemId);
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);

        List<RoomEntity> rooms = roomDao.findByProjectId(projectId);
        Set<CellKey> existing = existingAssignments(estimate);
        Map<Long, AssortmentPositionPriceEntity> assortmentByType =
                offerPackage == null ? Map.of() : assortmentPricesByType(offerPackage);

        List<AssignmentPlan> plans = new ArrayList<>();
        planWorkOverRooms(work, offerPackage, rooms, existing, assortmentByType, plans);
        return new CalculatedApply(offerPackage != null ? offerPackage.getCode() : null, List.copyOf(plans));
    }

    /**
     * <b>Read-only (persists NOTHING).</b> Computes the {@code Recompute_Finishing_Prices} plan for the
     * project's estimate under {@code packageCode} (design §B4, R12.1–R12.6, R15.3): for every
     * already-assigned finishing {@link EstimateLineRoomMaterialEntity} it recomputes the
     * package-scoped {@link FinishingPriceRangeResolver} range for the line's finishing type and, when
     * that package has at least one active priced finishing material of the type (a non-empty range),
     * plans a new {@code rangeMin..rangeMax}. It touches neither construction, labour, volumes, nor a
     * chosen concrete product (R12.3/R12.4), and it plans <b>no</b> change for a type with no
     * recomputable price (R12.6).
     *
     * <p>The returned {@link CalculatedRecompute} is staged by the client (undoable / redoable) and
     * written only on the batched {@link #applyAssignments} Save via a {@code RECOMPUTE_FINISHING}
     * staged edit (R15.6). Nothing is written here.
     *
     * @param projectId   the owning project's estimate to plan against
     * @param packageCode the package whose finishing prices to recompute (blank/unknown ⇒ empty plan)
     * @return the calculated, non-persisted recompute plan
     */
    @Transactional(readOnly = true)
    public CalculatedRecompute calculateRecomputeFinishingPrices(Long projectId, String packageCode) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);
        if (offerPackage == null) {
            return CalculatedRecompute.EMPTY;
        }

        Collection<FinishingMaterialEntity> finishingMaterials =
                finishingMaterialDao.findByActiveTrueAndRetailNetNotNull();

        List<FinishingRangePlan> plans = new ArrayList<>();
        for (EstimateLineEntity line : estimate.getLines()) {
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                    planFinishingRecompute(material, offerPackage, finishingMaterials, plans);
                }
            }
        }
        return new CalculatedRecompute(offerPackage.getCode(), List.copyOf(plans));
    }

    // --- calculate cores: pure per-work / per-line planning (property-testable, task 5.4–5.6) ------

    /**
     * Plans one work over the applicable rooms into {@code out} (the pure per-work apply core, R11.2–
     * R11.7 / R9.5/R9.6): filters {@code rooms} by the work's {@code Room_Type_Attachment} (all rooms
     * when empty), and for every room not already assigned to the work, resolves the Volume via
     * {@link VolumeResolver} (package override AST → default formula → unit fallback) and emits an
     * {@link AssignmentPlan}. An already-assigned {@code (work, room)} cell is left out entirely so the
     * layering never overrides an existing Volume (R11.5/R9.6). Pure: reads only its arguments, mutates
     * only {@code out}, persists nothing.
     */
    private void planWorkOverRooms(
            WorkItemEntity work,
            OfferPackageEntity offerPackage,
            List<RoomEntity> rooms,
            Set<CellKey> existing,
            Map<Long, AssortmentPositionPriceEntity> assortmentByType,
            List<AssignmentPlan> out) {
        WorkVolumeFormulaEntity defaultFormula =
                workVolumeFormulaDao.findByWorkItemId(work.getId()).orElse(null);
        WorkPackageOverrideEntity override = offerPackage == null ? null
                : workPackageOverrideDao
                        .findByWorkItemIdAndOfferPackageId(work.getId(), offerPackage.getId())
                        .orElse(null);
        String unitCode = work.getUnit() != null ? work.getUnit().getCode() : null;
        List<WorkMaterialConsumptionEntity> consumptions =
                workMaterialConsumptionDao.findByWorkItemIdIn(List.of(work.getId()));
        Set<Long> attachedTypeIds = attachedRoomTypeIds(work);

        for (RoomEntity room : rooms) {
            if (!roomAttaches(room, attachedTypeIds)) {
                continue; // room type not in the work's non-empty attachment (R10.2/R11.6)
            }
            if (existing.contains(new CellKey(work.getId(), room.getId()))) {
                continue; // already assigned -> layer without overriding the existing Volume (R11.5)
            }
            VolumeResolver.Resolution resolution =
                    VolumeResolver.resolve(override, defaultFormula, unitCode, room);
            List<MaterialLinePlan> materials =
                    planMaterialLines(consumptions, offerPackage, assortmentByType);
            out.add(new AssignmentPlan(
                    work.getId(),
                    room.getId(),
                    resolution.volume(),
                    resolution.fallbackUsed(),
                    offerPackage != null ? offerPackage.getCode() : null,
                    materials));
        }
    }

    /**
     * Plans the frozen material lines a fresh assignment would seed (R11.6/R11.8, R13.2): one line per
     * consumption type carrying the copied {@code Type_Price_Range} — construction via
     * {@link PriceRangeResolver}, finishing via the package-scoped {@link FinishingPriceRangeResolver}.
     * A finishing consumption whose material type matches a package assortment position additionally
     * carries that position's per-package band as an {@code Assortment_Placeholder} (R11.8); a finishing
     * type with no matching assortment position falls back to the resolver range and is NOT seeded from
     * assortment (R11.6 "SHALL NOT seed a placeholder for … no matching … position"). Pure over its
     * inputs (the resolvers are pure; the material collections are loaded once by the caller).
     */
    private List<MaterialLinePlan> planMaterialLines(
            List<WorkMaterialConsumptionEntity> consumptions,
            OfferPackageEntity offerPackage,
            Map<Long, AssortmentPositionPriceEntity> assortmentByType) {
        if (consumptions.isEmpty()) {
            return List.of();
        }
        Collection<ConstructionMaterialEntity> constructionMaterials = null;
        Collection<FinishingMaterialEntity> finishingMaterials = null;
        Long packageId = offerPackage != null ? offerPackage.getId() : null;

        List<MaterialLinePlan> lines = new ArrayList<>();
        for (WorkMaterialConsumptionEntity consumption : consumptions) {
            if (consumption.getBranch() == ConsumptionBranch.construction
                    && consumption.getConstructionMaterialType() != null) {
                Long typeId = consumption.getConstructionMaterialType().getId();
                if (constructionMaterials == null) {
                    constructionMaterials = constructionMaterialDao.findByActiveTrueAndRetailNetNotNull();
                }
                PriceRangeResolver.PriceRange range = priceRangeResolver.rangeFor(constructionMaterials, typeId);
                lines.add(new MaterialLinePlan(
                        ConsumptionBranch.construction, typeId, consumption.getNormQty(),
                        range.min(), range.max(), false));
            } else if (consumption.getBranch() == ConsumptionBranch.finishing
                    && consumption.getFinishingMaterialType() != null) {
                Long typeId = consumption.getFinishingMaterialType().getId();
                AssortmentPositionPriceEntity assortment = assortmentByType.get(typeId);
                if (assortment != null) {
                    // Assortment_Placeholder: carry the position's per-package band (R11.8).
                    lines.add(new MaterialLinePlan(
                            ConsumptionBranch.finishing, typeId, consumption.getNormQty(),
                            assortment.getMinPrice(), assortment.getMaxPrice(), true));
                } else {
                    if (finishingMaterials == null) {
                        finishingMaterials = finishingMaterialDao.findByActiveTrueAndRetailNetNotNull();
                    }
                    FinishingPriceRangeResolver.PriceRange range =
                            finishingPriceRangeResolver.rangeFor(finishingMaterials, typeId, packageId);
                    lines.add(new MaterialLinePlan(
                            ConsumptionBranch.finishing, typeId, consumption.getNormQty(),
                            range.min(), range.max(), false));
                }
            }
            // malformed consumption (branch/type mismatch) -> not planned
        }
        return lines;
    }

    /**
     * Plans the recompute of one finishing material line into {@code out} (the pure per-line recompute
     * core, R12.2–R12.6): a no-op for a non-finishing line, a line without a finishing type, or a type
     * with no recomputable package price (empty range → R12.6). Otherwise emits a
     * {@link FinishingRangePlan} carrying only the new {@code rangeMin..rangeMax} — the chosen concrete
     * product, norm, and everything else are left untouched (R12.3/R12.4). Pure: reads only its
     * arguments, mutates only {@code out}, persists nothing.
     */
    private void planFinishingRecompute(
            EstimateLineRoomMaterialEntity material,
            OfferPackageEntity offerPackage,
            Collection<FinishingMaterialEntity> finishingMaterials,
            List<FinishingRangePlan> out) {
        if (material.getBranch() != ConsumptionBranch.finishing || material.getFinishingType() == null) {
            return; // recompute touches finishing lines only (R12.3)
        }
        FinishingPriceRangeResolver.PriceRange range = finishingPriceRangeResolver.rangeFor(
                finishingMaterials, material.getFinishingType().getId(), offerPackage.getId());
        if (range.min() == null && range.max() == null) {
            return; // no recomputable finishing price for this type in the package -> no plan (R12.6)
        }
        out.add(new FinishingRangePlan(material.getId(), range.min(), range.max()));
    }

    // --- calculate-core helpers -------------------------------------------------------------

    /** The set of {@code (workItemId, roomId)} cells already assigned in the estimate (for layering). */
    private Set<CellKey> existingAssignments(EstimateEntity estimate) {
        Set<CellKey> keys = new HashSet<>();
        for (EstimateLineEntity line : estimate.getLines()) {
            Long workItemId = line.getWorkItem() != null ? line.getWorkItem().getId() : null;
            if (workItemId == null) {
                continue;
            }
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                if (roomQty.getRoom() != null) {
                    keys.add(new CellKey(workItemId, roomQty.getRoom().getId()));
                }
            }
        }
        return keys;
    }

    /** The member works of {@code offerPackage} (its {@code WorkPackageOverride.member} rows, R11.2). */
    private List<WorkItemEntity> memberWorksOf(OfferPackageEntity offerPackage) {
        List<WorkItemEntity> works = new ArrayList<>();
        for (WorkPackageOverrideEntity override : workPackageOverrideDao.findAllWithWorkItemAndPackage()) {
            if (override.getOfferPackage() != null
                    && offerPackage.getId().equals(override.getOfferPackage().getId())
                    && Boolean.TRUE.equals(override.getMember())
                    && override.getWorkItem() != null) {
                works.add(override.getWorkItem());
            }
        }
        return works;
    }

    /**
     * Indexes the package's assortment position bands by finishing material type id (R11.8): for the
     * given package, maps each assortment position's {@code materialType.id} to its per-package price
     * row so {@link #planMaterialLines} can seed an {@code Assortment_Placeholder} by material-type
     * match. A position with no price row for the package contributes no entry.
     */
    private Map<Long, AssortmentPositionPriceEntity> assortmentPricesByType(OfferPackageEntity offerPackage) {
        Map<Long, AssortmentPositionPriceEntity> byType = new HashMap<>();
        for (AssortmentPositionPriceEntity price : assortmentPositionPriceDao.findAll()) {
            AssortmentPositionEntity position = price.getPosition();
            OfferPackageEntity pkg = price.getOfferPackage();
            if (position == null || position.getMaterialType() == null || pkg == null) {
                continue;
            }
            if (offerPackage.getId().equals(pkg.getId())) {
                byType.putIfAbsent(position.getMaterialType().getId(), price);
            }
        }
        return byType;
    }

    /** The work's attached room-type ids, or {@code null} when the attachment is empty (⇒ all rooms). */
    private Set<Long> attachedRoomTypeIds(WorkItemEntity work) {
        Set<RoomTypeEntity> roomTypes = work.getRoomTypes();
        if (roomTypes == null || roomTypes.isEmpty()) {
            return null; // empty attachment -> attaches to all rooms (R10.3/R11.7)
        }
        Set<Long> ids = new HashSet<>();
        for (RoomTypeEntity type : roomTypes) {
            if (type != null && type.getId() != null) {
                ids.add(type.getId());
            }
        }
        return ids;
    }

    /**
     * Whether {@code room} attaches given the work's {@code attachedTypeIds}: {@code true} for all rooms
     * when the attachment is empty ({@code attachedTypeIds == null}), else only when the room's type is
     * in the set (R10.2/R10.3, R11.6/R11.7).
     */
    private boolean roomAttaches(RoomEntity room, Set<Long> attachedTypeIds) {
        if (attachedTypeIds == null) {
            return true;
        }
        RoomTypeEntity roomType = room.getRoomType();
        return roomType != null && roomType.getId() != null && attachedTypeIds.contains(roomType.getId());
    }

    /** Loads the project's estimate for a read-only calculate (no draft gate: previews never persist). */
    private EstimateEntity resolveEstimateForRead(Long projectId) {
        return estimateDao.findByProjectId(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));
    }

    // =====================================================================================
    // Calculate-result contracts (the read-only preview payloads the client stages, design §B4)
    // =====================================================================================

    /** A {@code (workItemId, roomId)} cell identity, used to detect already-assigned cells for layering. */
    private record CellKey(Long workItemId, Long roomId) {
    }

    /**
     * One planned material line of a calculated assignment (design §B4): the branch + material type,
     * the copied consumption norm, the copied {@code Type_Price_Range} band, and whether the band came
     * from a package {@code Assortment_Placeholder} (R11.8) rather than the type-price resolver. A
     * placeholder (no concrete product yet); carries no chosen product.
     *
     * @param branch    the material branch (construction | finishing)
     * @param typeId    the material type id of the branch
     * @param normQty   the copied consumption norm per one work-unit (R4.2)
     * @param rangeMin  the copied {@code Type_Price_Range} min (per one work-unit)
     * @param rangeMax  the copied {@code Type_Price_Range} max
     * @param assortment {@code true} iff the band came from a package assortment position (R11.8)
     */
    public record MaterialLinePlan(
            ConsumptionBranch branch,
            Long typeId,
            BigDecimal normQty,
            BigDecimal rangeMin,
            BigDecimal rangeMax,
            boolean assortment) {
    }

    /**
     * One planned assignment of a calculated apply (design §B4, R11.2/R11.4): the {@code (workItemId,
     * roomId)} cell to stage, its resolved Volume, whether the Volume came from the unit fallback (for
     * the {@code Cell_Report} disclosure, R5.3), the applied package code, and the planned material
     * lines. Only cells that are NOT already assigned appear here — the layering never overrides an
     * existing Volume (R11.5/R9.6).
     *
     * @param workItemId   the work to assign
     * @param roomId       the room to assign it to
     * @param volume       the resolved Volume for the cell (never {@code null})
     * @param fallbackUsed {@code true} iff the Volume came from the unit&rarr;dimension fallback (R5.3)
     * @param packageCode  the applied package code, or {@code null} (work-row apply with no package)
     * @param materials    the planned frozen material lines for the cell
     */
    public record AssignmentPlan(
            Long workItemId,
            Long roomId,
            BigDecimal volume,
            boolean fallbackUsed,
            String packageCode,
            List<MaterialLinePlan> materials) {

        /** The staged edit that persists this planned assignment on the batched Save (R15.6). */
        public StagedEdit toStagedEdit() {
            return StagedEdit.assign(workItemId, roomId, packageCode);
        }
    }

    /**
     * The calculated {@code Apply_Package} / {@code Work_Row_Apply} result (design §B4): the applied
     * package code (or {@code null} for a package-less work-row apply) and the ordered assignment plans
     * for the client to stage. Persists nothing — it is written only through the batched
     * {@link #applyAssignments} Save (R15.6).
     *
     * @param packageCode the applied package code, or {@code null}
     * @param assignments the ordered planned assignments (empty when nothing attaches or layers)
     */
    public record CalculatedApply(String packageCode, List<AssignmentPlan> assignments) {

        /** An empty apply plan (blank/unknown package, or nothing to attach). */
        public static final CalculatedApply EMPTY = new CalculatedApply(null, List.of());

        /** The staged {@code ASSIGN}/{@code APPLY_PACKAGE} edits this plan maps to on Save (R15.6). */
        public List<StagedEdit> toStagedEdits() {
            List<StagedEdit> edits = new ArrayList<>(assignments.size());
            for (AssignmentPlan plan : assignments) {
                edits.add(plan.toStagedEdit());
            }
            return edits;
        }
    }

    /**
     * One planned finishing-range recompute (design §B4, R12.2): the target already-assigned finishing
     * material line and its new {@code rangeMin..rangeMax}. Carries ONLY the new range — the chosen
     * concrete product, norm, construction lines, labour, and volumes are all untouched (R12.3/R12.4).
     *
     * @param materialLineId the target finishing material line
     * @param rangeMin       the recomputed range min
     * @param rangeMax       the recomputed range max
     */
    public record FinishingRangePlan(Long materialLineId, BigDecimal rangeMin, BigDecimal rangeMax) {

        /** The staged {@code RECOMPUTE_FINISHING} edit this plan maps to on the batched Save (R15.6). */
        public StagedEdit toStagedEdit(String packageCode) {
            return new StagedEdit(EditKind.RECOMPUTE_FINISHING, null, null, null, null, null,
                    materialLineId, null, packageCode);
        }
    }

    /**
     * The calculated {@code Recompute_Finishing_Prices} result (design §B4, R12.2/R12.6): the package
     * code and the per-line new ranges for the client to stage. Empty when no already-assigned finishing
     * line has a recomputable price in the package (R12.6) — the client then leaves ranges unchanged and
     * indicates that nothing was recomputable. Persists nothing — written only on the batched Save.
     *
     * @param packageCode the recompute package code
     * @param ranges      the per-line planned new ranges (empty ⇒ nothing recomputable, R12.6)
     */
    public record CalculatedRecompute(String packageCode, List<FinishingRangePlan> ranges) {

        /** An empty recompute plan (blank/unknown package, or nothing recomputable — R12.6). */
        public static final CalculatedRecompute EMPTY = new CalculatedRecompute(null, List.of());

        /** {@code true} iff nothing is recomputable (no staged edit is produced — R12.6). */
        public boolean isEmpty() {
            return ranges.isEmpty();
        }

        /** The staged {@code RECOMPUTE_FINISHING} edits this plan maps to on the batched Save (R15.6). */
        public List<StagedEdit> toStagedEdits() {
            List<StagedEdit> edits = new ArrayList<>(ranges.size());
            for (FinishingRangePlan plan : ranges) {
                edits.add(plan.toStagedEdit(packageCode));
            }
            return edits;
        }
    }

    // =====================================================================================
    // Extension points for tasks 5.2 (direct material edits) and 5.3 (calculate/preview cores)
    // =====================================================================================

    /**
     * Applies a staged material edit (add / remove / choose-concrete / bulk-choose) during a batched
     * {@link #applyAssignments} Save (R15.2, R15.3, R15.7), replaying it onto the estimate graph
     * through the same primitives as the direct public entry points ({@link #addMaterialLine} /
     * {@link #removeMaterialLine} / {@link #chooseConcrete} / {@link #bulkChooseConcreteForWork}).
     * The caller recomputes once after the whole batch, so this branch does not recompute per-edit.
     *
     * <ul>
     *   <li>{@code ADD_MATERIAL} — add a {@code (branch, type)} line on the edit's {@code roomQtyId}
     *       (copying the resolver range, R6.2);</li>
     *   <li>{@code REMOVE_MATERIAL} — remove that {@code (branch, type)} line (R6.2);</li>
     *   <li>{@code CHOOSE_CONCRETE} — set the concrete product on the edit's {@code materialLineId}
     *       and collapse the line (R6.3, R6.4);</li>
     *   <li>{@code BULK_CHOOSE_CONCRETE} — apply the concrete product to the edit's {@code (branch,
     *       type)} across every assigned cell of the edit's {@code workItemId} (R9.3).</li>
     * </ul>
     */
    private void applyMaterialEdit(EstimateEntity estimate, StagedEdit edit) {
        switch (edit.kind()) {
            case ADD_MATERIAL -> {
                EstimateLineRoomQtyEntity roomQty = resolveRoomQtyInEstimate(edit.roomQtyId(), estimate);
                doAddMaterialLine(roomQty, edit.branch(), edit.typeId());
            }
            case REMOVE_MATERIAL -> {
                EstimateLineRoomQtyEntity roomQty = resolveRoomQtyInEstimate(edit.roomQtyId(), estimate);
                doRemoveMaterialLine(roomQty, edit.branch(), edit.typeId());
            }
            case CHOOSE_CONCRETE -> {
                EstimateLineRoomMaterialEntity material = resolveMaterialLine(edit.materialLineId());
                assertMaterialInEstimate(material, estimate);
                applyChooseConcrete(material, edit.materialId());
            }
            case BULK_CHOOSE_CONCRETE -> {
                EstimateLineEntity line = findLine(estimate, edit.workItemId());
                if (line != null) {
                    for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                        EstimateLineRoomMaterialEntity material =
                                findMaterialLine(roomQty, edit.branch(), edit.typeId());
                        if (material != null) {
                            applyChooseConcrete(material, edit.materialId());
                        }
                    }
                }
            }
            default -> {
                // not a material edit -> nothing to replay here
            }
        }
    }

    // --- resolution helpers -----------------------------------------------------------------

    /** Loads the project's estimate and asserts it is still DRAFT (R7). */
    private EstimateEntity resolveDraftEstimate(Long projectId) {
        EstimateEntity estimate = estimateDao.findByProjectId(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));
        draftGateGuard.assertDraft(estimate);
        return estimate;
    }

    private WorkItemEntity resolveWorkItem(Long workItemId) {
        WorkItemEntity workItem = workItemId == null ? null
                : workItemDao.findById(workItemId).orElse(null);
        if (workItem == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "workItemId", workItemId);
        }
        return workItem;
    }

    private RoomEntity resolveRoom(Long roomId) {
        RoomEntity room = roomId == null ? null : roomDao.findById(roomId).orElse(null);
        if (room == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "roomId", roomId);
        }
        return room;
    }

    /** Resolves an optional package by code; {@code null}/blank code ⇒ no package context (R3.4). */
    private OfferPackageEntity resolvePackageOrNull(String packageCode) {
        if (packageCode == null || packageCode.isBlank()) {
            return null;
        }
        return offerPackageDao.findByCode(packageCode).orElse(null);
    }

    /** Enforces the cross-project rule: the room's project must equal the estimate's project (R3.6). */
    private void assertRoomInProject(RoomEntity room, Long projectId) {
        if (room.getProject() == null || !projectId.equals(room.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "roomId", room.getId());
        }
    }

    /** Guards that a material line resolved by id actually belongs to the target estimate. */
    private void assertMaterialInEstimate(EstimateLineRoomMaterialEntity material, EstimateEntity estimate) {
        EstimateLineRoomQtyEntity roomQty = material.getRoomQty();
        EstimateLineEntity line = roomQty != null ? roomQty.getLine() : null;
        EstimateEntity owning = line != null ? line.getEstimate() : null;
        if (owning == null || !owning.getId().equals(estimate.getId())) {
            throw new ForemenApiException(
                    HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "materialLineId", material.getId());
        }
    }

    /** The estimate's line for {@code workItemId}, or {@code null} when the work is unassigned. */
    private EstimateLineEntity findLine(EstimateEntity estimate, Long workItemId) {
        if (workItemId == null) {
            return null;
        }
        for (EstimateLineEntity line : estimate.getLines()) {
            if (line.getWorkItem() != null && workItemId.equals(line.getWorkItem().getId())) {
                return line;
            }
        }
        return null;
    }

    /** The line's room-qty for {@code roomId}, or {@code null} when the cell is unassigned. */
    private EstimateLineRoomQtyEntity findRoomQty(EstimateLineEntity line, Long roomId) {
        if (roomId == null) {
            return null;
        }
        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            if (roomQty.getRoom() != null && roomId.equals(roomQty.getRoom().getId())) {
                return roomQty;
            }
        }
        return null;
    }

    // =====================================================================================
    // Staged-edit contract (the batched Save payload, design §B4 / R15)
    // =====================================================================================

    /** The kind of a {@link StagedEdit} in a batched Save (mirrors the frontend staged action kinds). */
    public enum EditKind {
        ASSIGN,
        UNASSIGN,
        ADD_MATERIAL,
        REMOVE_MATERIAL,
        CHOOSE_CONCRETE,
        BULK_CHOOSE_CONCRETE,
        APPLY_PACKAGE,
        RECOMPUTE_FINISHING
    }

    /**
     * One staged matrix edit in a batched {@code Save} (design §B4). A single flat record covers every
     * {@link EditKind}; only the fields relevant to a given kind are populated (the rest are
     * {@code null}). Cell edits carry {@code workItemId}/{@code roomId}; material edits carry
     * {@code roomQtyId}/{@code branch}/{@code typeId} (and {@code materialLineId}/{@code materialId}
     * for choose-concrete); apply/recompute-originated edits carry {@code packageCode} and the
     * per-cell / per-line target they resolved to at calculate time.
     *
     * @param kind           the edit kind
     * @param workItemId     target work (ASSIGN / UNASSIGN / APPLY_PACKAGE / bulk choose)
     * @param roomId         target room (ASSIGN / UNASSIGN / APPLY_PACKAGE)
     * @param roomQtyId      target cell for a material edit (ADD/REMOVE material)
     * @param branch         material branch for a material edit
     * @param typeId         material type id for a material edit
     * @param materialLineId target material line (REMOVE / CHOOSE_CONCRETE / RECOMPUTE_FINISHING)
     * @param materialId     chosen concrete product (CHOOSE_CONCRETE / BULK_CHOOSE_CONCRETE)
     * @param packageCode    active package code (ASSIGN / APPLY_PACKAGE / RECOMPUTE_FINISHING)
     */
    public record StagedEdit(
            EditKind kind,
            Long workItemId,
            Long roomId,
            Long roomQtyId,
            ConsumptionBranch branch,
            Long typeId,
            Long materialLineId,
            Long materialId,
            String packageCode) {

        /** Convenience factory for an assign cell edit. */
        public static StagedEdit assign(Long workItemId, Long roomId, String packageCode) {
            return new StagedEdit(EditKind.ASSIGN, workItemId, roomId, null, null, null, null, null, packageCode);
        }

        /** Convenience factory for an unassign cell edit. */
        public static StagedEdit unassign(Long workItemId, Long roomId) {
            return new StagedEdit(EditKind.UNASSIGN, workItemId, roomId, null, null, null, null, null, null);
        }
    }
}
