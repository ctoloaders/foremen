package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.foremen.dao.AdminDao;
import com.foremen.dao.AssortmentPositionDao;
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
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
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
import com.foremen.service.EstimateService;
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
    private final AssortmentPositionDao assortmentPositionDao;

    private final DraftGateGuard draftGateGuard;
    private final EstimateRecomputeService estimateRecomputeService;
    private final PriceRangeResolver priceRangeResolver;
    private final FinishingPriceRangeResolver finishingPriceRangeResolver;
    private final EstimateMatrixAssembler estimateMatrixAssembler;
    private final EstimateService estimateService;

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
            AssortmentPositionDao assortmentPositionDao,
            DraftGateGuard draftGateGuard,
            EstimateRecomputeService estimateRecomputeService,
            PriceRangeResolver priceRangeResolver,
            FinishingPriceRangeResolver finishingPriceRangeResolver,
            EstimateMatrixAssembler estimateMatrixAssembler,
            EstimateService estimateService) {
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
        this.assortmentPositionDao = assortmentPositionDao;
        this.draftGateGuard = draftGateGuard;
        this.estimateRecomputeService = estimateRecomputeService;
        this.priceRangeResolver = priceRangeResolver;
        this.finishingPriceRangeResolver = finishingPriceRangeResolver;
        this.estimateMatrixAssembler = estimateMatrixAssembler;
        this.estimateService = estimateService;
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

        EstimateLineRoomQtyEntity roomQty =
                doAssign(estimate, workItem, room, offerPackage, consumptionsByWorkId(List.of(workItem)));

        // Remember the applied package when one was supplied (FOR-05-05 Wave 1b, #1); a null
        // packageCode leaves the existing value unchanged (do not clear).
        if (packageCode != null) {
            estimate.setAppliedPackageCode(packageCode);
        }

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
            // Batch-load every referenced work's consumptions ONCE for the whole replay so the
            // per-cell doAssign -> seedMaterialLines does not re-query per (work, room) (perf N+1).
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork =
                    consumptionsForStagedEdits(stagedEdits);
            for (StagedEdit edit : stagedEdits) {
                applyStagedEdit(estimate, projectId, edit, consumptionsByWork);
            }
            // Remember the applied offer package (FOR-05-05 Wave 1b, #1): the MOST-RECENT non-null
            // packageCode carried by a package-scoped staged edit (APPLY_PACKAGE / RECOMPUTE_FINISHING
            // / MERGE_PACKAGE_MATERIALS). If no staged edit carries a package code, leave the existing
            // value unchanged (do not clear — the "clear kosztorys" flow owns clearing in a later wave).
            String appliedPackageCode = latestAppliedPackageCode(stagedEdits);
            if (appliedPackageCode != null) {
                estimate.setAppliedPackageCode(appliedPackageCode);
            }
        }

        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
        return estimate;
    }

    /**
     * The last non-null {@code packageCode} carried by a package-scoped staged edit in {@code edits}
     * (FOR-05-05 Wave 1b, #1) — the package the estimate should remember as applied. Only
     * {@code APPLY_PACKAGE}, {@code RECOMPUTE_FINISHING} and {@code MERGE_PACKAGE_MATERIALS} edits
     * carry a package code (an {@code APPLY_CHEAPEST} does not); an {@code ASSIGN}'s package code
     * drives the override formula but does not represent applying a whole package, so it is ignored
     * here. Returns {@code null} when no such edit is present (the caller then leaves the estimate's
     * current value unchanged).
     */
    private static String latestAppliedPackageCode(List<StagedEdit> edits) {
        String latest = null;
        for (StagedEdit edit : edits) {
            if (edit == null || edit.kind() == null || edit.packageCode() == null) {
                continue;
            }
            switch (edit.kind()) {
                case APPLY_PACKAGE, RECOMPUTE_FINISHING, MERGE_PACKAGE_MATERIALS -> latest = edit.packageCode();
                default -> { /* ASSIGN etc. carry a packageCode only to drive the override formula. */ }
            }
        }
        return latest;
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
    @Transactional
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
    @Transactional
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
        // The full apply-package flow layers the expanded assigns AND then merges the package's
        // assortment finishing materials (FOR-05-05 Amendment A1): the merge edit runs LAST, after the
        // assigns in the same batch, so each room's finishing consumption NEED is known (point 3).
        List<StagedEdit> edits = new ArrayList<>(plan.toStagedEdits());
        edits.add(StagedEdit.mergePackageMaterials(packageCode));
        return assemblePreview(projectId, edits, editable);
    }

    /**
     * <b>Read-only (persists NOTHING).</b> The apply-package merge calculate entry (FOR-05-05
     * Amendment A1): returns the {@link CalculatedPackageMerge} plan the client stages as a single
     * {@code MERGE_PACKAGE_MATERIALS} edit (in addition to the expanded assign delta). The actual
     * merge is computed on replay against the current estimate graph (after the batch's assigns), so
     * the calculate entry simply carries the package code — mirroring the calculate/preview pattern of
     * the sibling apply/recompute cores. Persists nothing.
     *
     * @param projectId   the owning project's estimate (resolved/created for read, R1.7)
     * @param packageCode the package whose assortment finishing materials to merge
     * @return the calculated, non-persisted merge plan (a blank/unknown package plans nothing)
     */
    @Transactional
    public CalculatedPackageMerge calculatePackageMerge(Long projectId, String packageCode) {
        resolveEstimateForRead(projectId);
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);
        if (offerPackage == null) {
            return CalculatedPackageMerge.EMPTY;
        }
        return new CalculatedPackageMerge(offerPackage.getCode());
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
     * Read-only preview of the whole client staged set (design §B4/§B6,
     * {@code POST .../preview}): replays the FULL client staged set — any {@link EditKind}: cell
     * assign/unassign, material add/remove, choose-concrete/bulk-choose-concrete, and
     * apply/recompute-originated edits — onto the in-memory estimate graph exactly as the batched Save
     * would, assembles the resulting matrix, and rolls back so NOTHING is persisted (R15.6). This is
     * the read-only dry-run of {@link #saveAndAssemble} used to render the staged set live so the
     * client can show real server-computed numbers for panel material edits before Save.
     *
     * @param projectId   the owning project's estimate to preview against
     * @param stagedEdits the full ordered client staged set to replay (may be empty ⇒ current matrix)
     * @param editable    the caller's editable flag (carried onto the preview matrix)
     * @return the preview matrix (never persisted)
     */
    @Transactional
    public EstimateMatrixDto previewStagedEdits(Long projectId, List<StagedEdit> stagedEdits, boolean editable) {
        return assemblePreview(projectId, stagedEdits, editable);
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
            // Batch-load every referenced work's consumptions ONCE for the whole replay so the
            // per-cell doAssign -> seedMaterialLines does not re-query per (work, room) (perf N+1).
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork =
                    consumptionsForStagedEdits(stagedEdits);
            for (StagedEdit edit : stagedEdits) {
                applyStagedEdit(estimate, projectId, edit, consumptionsByWork);
            }
        }
        entityManager.flush(); // materialize ids/collections for a faithful assembly
        EstimateMatrixDto preview = estimateMatrixAssembler.assemble(estimate, projectId, editable);
        // Discard every preview mutation — the calculate/preview endpoints persist nothing (R15.6).
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return preview;
    }

    /**
     * Replays a single staged edit onto the estimate graph.
     *
     * <p><b>{@code APPLY_PACKAGE} has two shapes, both handled here.</b> A <i>per-cell</i>
     * {@code APPLY_PACKAGE} (roomId set) — the plan-expanded form emitted by
     * {@link CalculatedApply#toStagedEdits()} — assigns that one {@code (work, room)} cell. A
     * <i>coarse work-level</i> {@code APPLY_PACKAGE} (roomId null) — the form the work-row hammer /
     * Apply_Package button stages, meaning "apply this work to all its matching rooms" — is expanded
     * via {@link #calculateApplyWorkToRooms(Long, Long, String)} keyed on {@code (workItemId,
     * packageCode)} and each planned cell replayed, so both the coarse client form and the
     * plan-expanded form persist/preview correctly. Expanded cells carry a non-null roomId (in fact
     * they replay as {@code ASSIGN} edits), so the expansion provably terminates and never recurses
     * into itself; a coarse edit with a {@code null} workItemId is a defensive no-op (a preview must
     * be total, R15.6).
     */
    private void applyStagedEdit(
            EstimateEntity estimate, Long projectId, StagedEdit edit,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork) {
        if (edit == null || edit.kind() == null) {
            return;
        }
        switch (edit.kind()) {
            case ASSIGN -> {
                WorkItemEntity workItem = resolveWorkItem(edit.workItemId());
                RoomEntity room = resolveRoom(edit.roomId());
                assertRoomInProject(room, projectId);
                doAssign(estimate, workItem, room, resolvePackageOrNull(edit.packageCode()), consumptionsByWork);
            }
            case UNASSIGN -> {
                EstimateLineEntity line = findLine(estimate, edit.workItemId());
                if (line != null) {
                    doUnassign(estimate, line, edit.roomId());
                }
            }
            // Cell material edits and apply/recompute-originated edits share the primitives below.
            // APPLY_PACKAGE comes in two shapes, both replayed here:
            //   * per-cell (roomId set): the plan-expanded form produced by
            //     CalculatedApply#toStagedEdits — a single (work, room) assign, unchanged behaviour.
            //   * coarse work-level (roomId null): the form the work-row hammer / Apply_Package button
            //     stages, meaning "apply this work to all its matching rooms". It carries only
            //     (workItemId, packageCode) and NO room, so it is expanded here via the read-only
            //     calculate core calculateApplyWorkToRooms(workItemId, packageCode) and each planned
            //     cell replayed. The expanded edits each carry a non-null roomId, so they take the
            //     per-cell branch below (in fact CalculatedApply expands to ASSIGN edits) — this
            //     provably terminates and cannot recurse into itself. A null workItemId is a defensive
            //     no-op (a preview must be total and never throw).
            case APPLY_PACKAGE -> {
                if (edit.roomId() != null) {
                    WorkItemEntity workItem = resolveWorkItem(edit.workItemId());
                    RoomEntity room = resolveRoom(edit.roomId());
                    assertRoomInProject(room, projectId);
                    doAssign(estimate, workItem, room, resolvePackageOrNull(edit.packageCode()),
                            consumptionsByWork);
                } else if (edit.workItemId() != null) {
                    CalculatedApply plan =
                            calculateApplyWorkToRooms(projectId, edit.workItemId(), edit.packageCode());
                    // The coarse expansion targets a single work; batch-load its consumptions once for
                    // the expanded per-cell replay so seedMaterialLines is not re-queried per room.
                    Map<Long, List<WorkMaterialConsumptionEntity>> expandedConsumptions =
                            consumptionsForStagedEdits(plan.toStagedEdits());
                    for (StagedEdit cellEdit : plan.toStagedEdits()) {
                        applyStagedEdit(estimate, projectId, cellEdit, expandedConsumptions);
                    }
                }
                // else: coarse edit with no work -> defensive no-op (preview stays total, R15.6).
            }
            case RECOMPUTE_FINISHING -> recopyFinishingRange(estimate, edit);
            case MERGE_PACKAGE_MATERIALS -> mergePackageMaterials(estimate, projectId, edit.packageCode());
            case SET_QUANTITY -> applySetQuantity(estimate, edit);
            case CLEAR_QUANTITY -> applyClearQuantity(estimate, edit);
            case SET_MATERIAL_QUANTITY -> applySetMaterialQuantity(estimate, edit);
            case CLEAR_MATERIAL_QUANTITY -> applyClearMaterialQuantity(estimate, edit);
            case APPLY_CHEAPEST -> applyCheapest(estimate, edit);
            case ADD_MATERIAL, REMOVE_MATERIAL, CHOOSE_CONCRETE, BULK_CHOOSE_CONCRETE ->
                    applyMaterialEdit(estimate, edit);
        }
    }

    /**
     * Overrides the {@code (workItemId, roomId)} cell's Volume with the edit's manual {@code quantity}
     * (#7): resolves the cell by natural keys within the estimate graph (reusing
     * {@link #resolveStagedCell}); when the cell exists AND {@code quantity} is non-null and positive,
     * stores it as the room-qty {@code quantity} and flags {@code volumeOverridden = true} so a later
     * recompute/reassign will NOT recompute it back to the formula Volume. A NO-OP when the cell is
     * absent or the value is {@code null}/non-positive — a preview must be total and must never throw.
     */
    private void applySetQuantity(EstimateEntity estimate, StagedEdit edit) {
        EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
        if (roomQty == null || edit.quantity() == null || edit.quantity().signum() <= 0) {
            return; // absent cell / null / non-positive -> total no-op (#7, R15.6)
        }
        roomQty.setQuantity(edit.quantity());
        roomQty.setVolumeOverridden(true);
        estimateLineRoomQtyDao.save(roomQty);
    }

    /**
     * Clears the {@code (workItemId, roomId)} cell's manual Volume override (#7), re-deriving the
     * formula-resolved Volume: resolves the cell by natural keys; when it exists and is currently
     * overridden, clears the flag and recomputes its {@code quantity} via the same package-less
     * resolver path {@link #doAssign}/{@link #assign} use ({@link #resolveVolume} with a {@code null}
     * package — the matrix read has no active-package context, matching the assign default). A NO-OP
     * when the cell is absent or is not overridden.
     */
    private void applyClearQuantity(EstimateEntity estimate, StagedEdit edit) {
        EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
        if (roomQty == null || !roomQty.isVolumeOverridden()) {
            return; // absent cell / not overridden -> no-op (#7)
        }
        roomQty.setVolumeOverridden(false);
        EstimateLineEntity line = roomQty.getLine();
        WorkItemEntity workItem = line != null ? line.getWorkItem() : null;
        RoomEntity room = roomQty.getRoom();
        if (workItem != null && room != null) {
            roomQty.setQuantity(resolveVolume(workItem, null, room));
        }
        estimateLineRoomQtyDao.save(roomQty);
    }

    // =====================================================================================
    // Per-material-line quantity override (FOR-05-05 amendment #1)
    // =====================================================================================

    /**
     * Overrides a single material line's physical quantity with the edit's manual {@code quantity}
     * (amendment #1): resolves the target line by the natural keys {@code (workItemId, roomId, branch,
     * typeId)} within the in-memory estimate graph — exactly like {@code CHOOSE_CONCRETE} — then copies
     * {@code quantity} into {@code manualQty} and sets {@code qtyOverridden = true}. The override wins
     * over the consumption basis (#4). A NO-OP when the cell/line is absent or {@code quantity} is
     * {@code null} or negative, so a preview stays total and never throws (R15.6).
     */
    private void applySetMaterialQuantity(EstimateEntity estimate, StagedEdit edit) {
        EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
        if (roomQty == null || edit.quantity() == null || edit.quantity().signum() < 0) {
            return; // absent cell / null / negative -> total no-op (#1, R15.6)
        }
        EstimateLineRoomMaterialEntity material = findMaterialLine(roomQty, edit.branch(), edit.typeId());
        if (material == null) {
            return; // absent line -> no-op
        }
        material.setManualQty(edit.quantity());
        material.setQtyOverridden(true);
        estimateLineRoomMaterialDao.save(material);
    }

    /**
     * Clears a material line's manual quantity override (amendment #1): resolves the target line by
     * natural keys {@code (workItemId, roomId, branch, typeId)}, sets {@code qtyOverridden = false} and
     * {@code manualQty = null}, so the line reverts to its derived quantity ({@code norm × Volume} for
     * PER_UNIT, {@code norm} for PER_ROOM). A NO-OP when the cell/line is absent.
     */
    private void applyClearMaterialQuantity(EstimateEntity estimate, StagedEdit edit) {
        EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
        if (roomQty == null) {
            return;
        }
        EstimateLineRoomMaterialEntity material = findMaterialLine(roomQty, edit.branch(), edit.typeId());
        if (material == null) {
            return;
        }
        material.setQtyOverridden(false);
        material.setManualQty(null);
        estimateLineRoomMaterialDao.save(material);
    }

    // =====================================================================================
    // Apply cheapest products — fill placeholders with the min-price concrete (design #19)
    // =====================================================================================

    /**
     * Fills every in-scope PLACEHOLDER material line with the CHEAPEST concrete product of its
     * material type (#19), collapsing each filled line to that product's {@code retailNet} via the
     * existing {@link #applyChooseConcrete(EstimateLineRoomMaterialEntity, Long)}. A material line is a
     * Placeholder iff neither concrete FK is set (R6.6); a line that already has a chosen concrete
     * product is left untouched. When no active, priced product exists for a line's {@code (branch,
     * type)} the line stays a Placeholder (a no-op for that line).
     *
     * <p><b>Scope</b> (both optional): {@code workItemId == null} ⇒ every assigned cell of the
     * estimate; {@code workItemId != null && roomId == null} ⇒ every assigned cell of that work;
     * {@code workItemId != null && roomId != null} ⇒ the single {@code (work, room)} cell. Total and
     * non-throwing — an absent work / cell is a no-op — so it is safe in a preview (R15.6).
     */
    private void applyCheapest(EstimateEntity estimate, StagedEdit edit) {
        List<EstimateLineRoomQtyEntity> targets = new ArrayList<>();
        if (edit.workItemId() == null) {
            for (EstimateLineEntity line : estimate.getLines()) {
                targets.addAll(line.getRoomQtys());
            }
        } else if (edit.roomId() == null) {
            EstimateLineEntity line = findLine(estimate, edit.workItemId());
            if (line != null) {
                targets.addAll(line.getRoomQtys());
            }
        } else {
            EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
            if (roomQty != null) {
                targets.add(roomQty);
            }
        }

        for (EstimateLineRoomQtyEntity roomQty : targets) {
            for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                applyCheapestToLine(material);
            }
        }
    }

    /**
     * Fills one material line with the cheapest concrete product of its type when it is a Placeholder
     * (R6.6). Construction lines resolve the cheapest by {@code constructionType}, finishing lines by
     * {@code finishingType}; a line whose type is unset or that has no priced product for its type is
     * left as a Placeholder.
     */
    private void applyCheapestToLine(EstimateLineRoomMaterialEntity material) {
        boolean placeholder = material.getConcreteConstructionMaterial() == null
                && material.getConcreteFinishingMaterial() == null;
        if (!placeholder) {
            return; // already concrete -> leave the chosen product untouched
        }
        Long cheapestId = null;
        if (material.getBranch() == ConsumptionBranch.construction && material.getConstructionType() != null) {
            cheapestId = cheapestConstructionProductId(material.getConstructionType().getId());
        } else if (material.getBranch() == ConsumptionBranch.finishing && material.getFinishingType() != null) {
            cheapestId = cheapestFinishingProductId(material.getFinishingType().getId());
        }
        if (cheapestId != null) {
            applyChooseConcrete(material, cheapestId);
        }
        // else: no priced product for the type -> leave the line as a Placeholder (no-op).
    }

    /**
     * The id of the cheapest active, priced construction product of {@code typeId} (min by
     * {@code retailNet}), or {@code null} when the type has no such product. Reuses the same active +
     * priced batch the seeding / range resolvers load.
     */
    private Long cheapestConstructionProductId(Long typeId) {
        if (typeId == null) {
            return null;
        }
        ConstructionMaterialEntity cheapest = null;
        for (ConstructionMaterialEntity product : constructionMaterialDao.findByActiveTrueAndRetailNetNotNull()) {
            if (product.getType() == null || !typeId.equals(product.getType().getId())
                    || product.getRetailNet() == null) {
                continue;
            }
            if (cheapest == null || product.getRetailNet().compareTo(cheapest.getRetailNet()) < 0) {
                cheapest = product;
            }
        }
        return cheapest != null ? cheapest.getId() : null;
    }

    /**
     * The id of the cheapest active, priced finishing product of {@code typeId} (min by
     * {@code retailNet}), or {@code null} when the type has no such product.
     */
    private Long cheapestFinishingProductId(Long typeId) {
        if (typeId == null) {
            return null;
        }
        FinishingMaterialEntity cheapest = null;
        for (FinishingMaterialEntity product : finishingMaterialDao.findByActiveTrueAndRetailNetNotNull()) {
            if (product.getType() == null || !typeId.equals(product.getType().getId())
                    || product.getRetailNet() == null) {
                continue;
            }
            if (cheapest == null || product.getRetailNet().compareTo(cheapest.getRetailNet()) < 0) {
                cheapest = product;
            }
        }
        return cheapest != null ? cheapest.getId() : null;
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
        // Copy the work's consumption basis for (branch, type) onto the line (#4); PER_UNIT default.
        material.setConsumptionBasis(resolveConsumptionBasis(roomQty, branch, typeId));

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
     *
     * <p>A {@code null} {@code materialId} un-chooses the product (branch-agnostic): both concrete
     * FKs and {@code concreteNet} are cleared, restoring the line to an unfilled Placeholder that
     * contributes its {@code rangeMin}/{@code rangeMax} band again (R6.6/R6.4). The copied range band
     * and {@code normQty} are left untouched and the line is NOT deleted.
     */
    private void applyChooseConcrete(EstimateLineRoomMaterialEntity material, Long materialId) {
        if (materialId == null) {
            // Un-choose: clear the concrete product on both branches and the concrete price,
            // leaving the line as a Placeholder (range band + normQty preserved). Branch-agnostic.
            material.setConcreteConstructionMaterial(null);
            material.setConcreteFinishingMaterial(null);
            material.setConcreteNet(null);
            estimateLineRoomMaterialDao.save(material);
            return;
        }
        if (material.getBranch() == ConsumptionBranch.construction) {
            ConstructionMaterialEntity product = constructionMaterialDao.findById(materialId).orElse(null);
            if (product == null) {
                throw new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "materialId", materialId);
            }
            material.setConcreteConstructionMaterial(product);
            material.setConcreteFinishingMaterial(null);
            material.setConcreteNet(product.getRetailNet());
        } else if (material.getBranch() == ConsumptionBranch.finishing) {
            FinishingMaterialEntity product = finishingMaterialDao.findById(materialId).orElse(null);
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

    /**
     * The consumption BASIS the work declares for {@code (branch, type)} (#4), or
     * {@link ConsumptionBasis#PER_UNIT} when the work has no consumption for that type (an ad-hoc
     * material line added beyond the work's declared consumption is per-unit by default).
     */
    private ConsumptionBasis resolveConsumptionBasis(
            EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        EstimateLineEntity line = roomQty.getLine();
        WorkItemEntity workItem = line != null ? line.getWorkItem() : null;
        if (workItem == null) {
            return ConsumptionBasis.PER_UNIT;
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
                return consumption.getConsumptionBasis() != null
                        ? consumption.getConsumptionBasis() : ConsumptionBasis.PER_UNIT;
            }
        }
        return ConsumptionBasis.PER_UNIT;
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
            EstimateEntity estimate, WorkItemEntity workItem, RoomEntity room, OfferPackageEntity offerPackage,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork) {
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
            seedMaterialLines(workItem, roomQty, offerPackage, consumptionsByWork);
        } else if (!roomQty.isVolumeOverridden()) {
            // Re-assigning an already-assigned cell refreshes its Volume without duplicating rows —
            // BUT never overwrites a manual Volume override (FOR-05-05 #7). An overridden cell keeps
            // its stored manual quantity on reassign/recompute.
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
            WorkItemEntity workItem, EstimateLineRoomQtyEntity roomQty, OfferPackageEntity offerPackage,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork) {
        // Consumptions are batch-loaded ONCE by the replay caller (consumptionsForStagedEdits) so this
        // per-cell seed does not re-query per (work, room) — was a severe N+1 on the apply-package /
        // apply-work preview replay for large projects.
        List<WorkMaterialConsumptionEntity> consumptions =
                consumptionsByWork.getOrDefault(workItem.getId(), List.of());
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
            // Copy the consumption basis onto the frozen line so the estimate is self-contained (#4).
            material.setConsumptionBasis(consumption.getConsumptionBasis() != null
                    ? consumption.getConsumptionBasis() : ConsumptionBasis.PER_UNIT);

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
    // Package finishing-materials merge (FOR-05-05 Amendment A1) — the apply-package MERGE core
    // =====================================================================================

    /**
     * Merges {@code packageCode}'s assortment finishing materials into {@code estimate} (FOR-05-05
     * Amendment A1). Runs against the CURRENT estimate graph (so it MUST be replayed AFTER the same
     * batch's assign edits, when each room's finishing consumption NEED is known).
     *
     * <p>Steps (design "Distribution algorithm" + "Re-apply semantics"):
     * <ol>
     *   <li><b>Reset (replace-on-reapply, point 5).</b> Remove every existing package-flagged line
     *       ({@code appliedFromPackage == true}) and reset every prior "extra" finishing line back to
     *       its full consumption NEED, so re-applying (the same or a different package) recomputes
     *       cleanly. Idempotent.</li>
     *   <li><b>Per group.</b> Resolve the group's applicable rooms = the project's rooms whose
     *       {@code roomType.id} is in the group's {@code roomTypes} M:N (empty ⇒ skip the group — no
     *       fall back to all rooms).</li>
     *   <li><b>Per position (a finishing type with a per-package price row).</b> The project-wide
     *       total = the group's {@code referenceQty}. Water-fill it across the applicable rooms that
     *       CONSUME the type, smallest-need-first, capped at each room's need, discarding leftover.</li>
     *   <li><b>Split.</b> For each covered room, flag its finishing line(s) of the type as
     *       package-covered (qty = allocation, the package price band) and, where NEED exceeds the
     *       allocation, add an extra (non-package) line for the remainder.</li>
     * </ol>
     *
     * <p>Bulk-loads the package's assortment (positions + prices + group room-types) ONCE; the
     * consumption NEED is read from the already-persisted/staged finishing lines (no per-room/type
     * re-query). A blank/unknown package or a package with no priced assortment position is a total
     * no-op (a preview must stay total, R15.6). Construction lines are never touched.
     */
    private void mergePackageMaterials(EstimateEntity estimate, Long projectId, String packageCode) {
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);

        // Step 1 — reset (replace-on-reapply, point 5): remove all package-flagged finishing lines and
        // recompute the plain consumption line back to its full NEED (re-seeding a line that a prior
        // full-coverage merge had absorbed into a package line), ALWAYS — even for a blank/unknown
        // package, so "apply then apply nothing" clears cleanly and re-applying is idempotent.
        resetAndReseedFinishing(estimate);
        if (offerPackage == null) {
            return; // blank/unknown package -> nothing more to merge (reset already ran)
        }

        // Bulk-load the package's assortment ONCE: the priced positions (a finishing type + its
        // per-package band) grouped by their assortment group, and each group's room-type ids.
        Map<Long, AssortmentPositionPriceEntity> priceByType = assortmentPricesByType(offerPackage);
        if (priceByType.isEmpty()) {
            return; // the package has no priced assortment position -> nothing to merge
        }
        Map<AssortmentGroupEntity, List<AssortmentPositionEntity>> positionsByGroup =
                packagePositionsByGroup(priceByType.keySet());
        if (positionsByGroup.isEmpty()) {
            return;
        }

        List<RoomEntity> projectRooms = roomDao.findByProjectId(projectId);
        // Index the estimate's finishing lines by (roomId, finishingTypeId) so NEED and the split are
        // computed from the current graph without re-querying per room/type (a room may have several
        // cells consuming the same type — collect ALL of them).
        Map<RoomTypeKey, List<EstimateLineRoomMaterialEntity>> finishingLinesByRoomType =
                indexFinishingLinesByRoomType(estimate);

        for (Map.Entry<AssortmentGroupEntity, List<AssortmentPositionEntity>> entry : positionsByGroup.entrySet()) {
            AssortmentGroupEntity group = entry.getKey();
            List<RoomEntity> applicableRooms = applicableRoomsForGroup(group, projectRooms);
            if (applicableRooms.isEmpty()) {
                continue; // no seeded room types (or none match) -> skip the group (no fall back)
            }
            BigDecimal referenceQty = nz(group.getReferenceQty());
            for (AssortmentPositionEntity position : entry.getValue()) {
                Long typeId = position.getMaterialType() != null ? position.getMaterialType().getId() : null;
                AssortmentPositionPriceEntity price = typeId != null ? priceByType.get(typeId) : null;
                if (typeId == null || price == null) {
                    continue; // no finishing type / no price row for the package -> skip
                }
                mergePosition(typeId, referenceQty, price, applicableRooms, finishingLinesByRoomType);
            }
        }
    }

    /**
     * Reset step (point 5) — makes the estimate's finishing lines a clean, package-free base the
     * water-fill re-splits from, so applying a package is idempotent and switching packages recomputes
     * cleanly:
     * <ol>
     *   <li>Delete every package-flagged finishing line ({@code appliedFromPackage == true}).</li>
     *   <li>Reset every remaining finishing line's package-managed override
     *       ({@code qtyOverridden}/{@code manualQty}) so its quantity is the derived consumption NEED
     *       again — undoing a prior merge's "extra" reduction.</li>
     *   <li>Re-seed a full-NEED consumption line for any {@code (cell, finishing type)} the work
     *       DECLARES but that has no non-package finishing line — this restores the base line a prior
     *       FULL-coverage merge had absorbed into (and removed with) a package line, so NEED is again
     *       computable from the current graph.</li>
     * </ol>
     * Construction lines, chosen concrete products, ranges and volumes are untouched.
     */
    private void resetAndReseedFinishing(EstimateEntity estimate) {
        // 1 + 2: drop package lines and clear package-managed overrides on the surviving finishing lines.
        for (EstimateLineEntity line : estimate.getLines()) {
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                List<EstimateLineRoomMaterialEntity> toRemove = new ArrayList<>();
                for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                    if (material.getBranch() != ConsumptionBranch.finishing) {
                        continue;
                    }
                    if (material.isAppliedFromPackage()) {
                        toRemove.add(material);
                    } else if (material.isQtyOverridden()) {
                        material.setQtyOverridden(false);
                        material.setManualQty(null);
                    }
                }
                for (EstimateLineRoomMaterialEntity material : toRemove) {
                    roomQty.getMaterials().remove(material);
                    estimateLineRoomMaterialDao.delete(material);
                }
            }
        }
        entityManager.flush(); // materialize the deletes before re-seeding / re-splitting

        // 3: re-seed any declared finishing consumption that no longer has a (non-package) line.
        for (EstimateLineEntity line : estimate.getLines()) {
            WorkItemEntity work = line.getWorkItem();
            if (work == null) {
                continue;
            }
            List<WorkMaterialConsumptionEntity> consumptions =
                    workMaterialConsumptionDao.findByWorkItemIdIn(List.of(work.getId()));
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                reseedMissingFinishingLines(roomQty, consumptions);
            }
        }
        entityManager.flush();
    }

    /**
     * Re-seeds a full-NEED finishing consumption line for each finishing {@code WorkMaterialConsumption}
     * of the cell's work that currently has no non-package finishing line of that type (restoring a
     * base line a prior full-coverage merge removed). Copies the norm/basis and the package-less
     * finishing range, mirroring {@link #seedMaterialLines}. Existing lines are left untouched.
     */
    private void reseedMissingFinishingLines(
            EstimateLineRoomQtyEntity roomQty, List<WorkMaterialConsumptionEntity> consumptions) {
        Collection<FinishingMaterialEntity> finishingMaterials = null;
        for (WorkMaterialConsumptionEntity consumption : consumptions) {
            if (consumption.getBranch() != ConsumptionBranch.finishing
                    || consumption.getFinishingMaterialType() == null) {
                continue;
            }
            Long typeId = consumption.getFinishingMaterialType().getId();
            if (findMaterialLine(roomQty, ConsumptionBranch.finishing, typeId) != null) {
                continue; // a non-package finishing line already exists -> nothing to re-seed
            }
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setRoomQty(roomQty);
            material.setBranch(ConsumptionBranch.finishing);
            material.setFinishingType(consumption.getFinishingMaterialType());
            material.setNormQty(consumption.getNormQty());
            material.setConsumptionBasis(basisOf(consumption));
            if (finishingMaterials == null) {
                finishingMaterials = finishingMaterialDao.findByActiveTrueAndRetailNetNotNull();
            }
            FinishingPriceRangeResolver.PriceRange range =
                    finishingPriceRangeResolver.rangeFor(finishingMaterials, typeId, null);
            material.setRangeMin(range.min());
            material.setRangeMax(range.max());
            estimateLineRoomMaterialDao.save(material);
            roomQty.getMaterials().add(material);
        }
    }

    /**
     * Water-fills one position's project-wide {@code referenceQty} across the group's applicable rooms
     * that consume {@code typeId} (smallest-need-first, capped at need, leftover discarded), then
     * splits each covered room's finishing line(s) of the type into a package-flagged line
     * (qty = allocation) plus, where NEED exceeds the allocation, an extra (non-package) line
     * (qty = need − allocation). A room whose allocation fully covers its NEED gets ONLY the
     * package-flagged line.
     */
    private void mergePosition(
            Long typeId,
            BigDecimal referenceQty,
            AssortmentPositionPriceEntity price,
            List<RoomEntity> applicableRooms,
            Map<RoomTypeKey, List<EstimateLineRoomMaterialEntity>> finishingLinesByRoomType) {
        // Compute each applicable room's NEED for the type = Σ resolved physical quantity of its
        // finishing lines of that type (across all its cells). Rooms with NEED == 0 are skipped.
        List<RoomNeed> needs = new ArrayList<>();
        for (RoomEntity room : applicableRooms) {
            List<EstimateLineRoomMaterialEntity> lines =
                    finishingLinesByRoomType.getOrDefault(new RoomTypeKey(room.getId(), typeId), List.of());
            if (lines.isEmpty()) {
                continue;
            }
            BigDecimal need = BigDecimal.ZERO;
            for (EstimateLineRoomMaterialEntity line : lines) {
                need = need.add(resolvedNeed(line));
            }
            if (need.signum() > 0) {
                needs.add(new RoomNeed(room.getId(), need, lines));
            }
        }
        if (needs.isEmpty()) {
            return; // no applicable room consumes the type -> nothing to distribute
        }

        // Sort by NEED ascending, tie-break by roomId for determinism.
        needs.sort(Comparator
                .comparing(RoomNeed::need)
                .thenComparing(RoomNeed::roomId));

        BigDecimal remaining = nz(referenceQty);
        for (RoomNeed roomNeed : needs) {
            BigDecimal alloc = remaining.min(roomNeed.need());
            if (alloc.signum() < 0) {
                alloc = BigDecimal.ZERO;
            }
            remaining = remaining.subtract(alloc);
            splitRoomLines(roomNeed, typeId, alloc, price);
            if (remaining.signum() <= 0) {
                break; // package total exhausted -> later rooms get allocation 0 (no package line)
            }
        }
    }

    /**
     * Splits a room's finishing consumption of {@code typeId} into a package-flagged line
     * (qty = {@code alloc}, when {@code alloc > 0}) plus an extra (non-package) line
     * (qty = need − alloc, when NEED exceeds the allocation). When the room has ONE finishing cell of
     * the type (the common case) the existing line becomes the extra (carrying the uncovered
     * remainder), preserving any already-chosen concrete product, and a NEW package-flagged line is
     * added alongside it; when {@code alloc} covers the full NEED the extra is dropped so only the
     * package-flagged line remains.
     */
    private void splitRoomLines(
            RoomNeed roomNeed, Long typeId, BigDecimal alloc, AssortmentPositionPriceEntity price) {
        BigDecimal need = roomNeed.need();
        BigDecimal extra = need.subtract(alloc);
        // Use the room's first finishing line of the type as the representative (its owning room-qty
        // hosts the package + extra lines, and it carries the copied norm/basis/product to preserve).
        EstimateLineRoomMaterialEntity base = roomNeed.lines().get(0);
        EstimateLineRoomQtyEntity roomQty = base.getRoomQty();

        // Any additional cells consuming the type in the same room are collapsed into the base for the
        // package split (their NEED is already summed): remove them so the room ends with exactly the
        // package-flagged line (+ optional extra) for the type — the water-fill allocates per ROOM.
        for (int i = 1; i < roomNeed.lines().size(); i++) {
            EstimateLineRoomMaterialEntity dup = roomNeed.lines().get(i);
            dup.getRoomQty().getMaterials().remove(dup);
            estimateLineRoomMaterialDao.delete(dup);
        }

        if (extra.signum() > 0) {
            // The base line becomes the EXTRA: carry only the uncovered remainder, non-package,
            // preserving its concrete product (best-effort) and its copied range.
            base.setAppliedFromPackage(false);
            base.setQtyOverridden(true);
            base.setManualQty(extra);
            estimateLineRoomMaterialDao.save(base);
        } else {
            // Fully covered by the package: the base extra is dropped (only the package line remains).
            roomQty.getMaterials().remove(base);
            estimateLineRoomMaterialDao.delete(base);
        }
        entityManager.flush();

        if (alloc.signum() > 0) {
            addPackageLine(roomQty, typeId, alloc, price, base);
        }
    }

    /**
     * Adds a package-flagged finishing material line to {@code roomQty} for {@code typeId}: a fixed
     * per-room quantity ({@code qtyOverridden = true}, {@code manualQty = alloc}) with the position's
     * per-package price band, starting as a placeholder (no concrete product). Copies the finishing
     * type + norm/basis from {@code template} (the room's consumption line) so the line is a faithful,
     * self-contained frozen row.
     */
    private void addPackageLine(
            EstimateLineRoomQtyEntity roomQty, Long typeId, BigDecimal alloc,
            AssortmentPositionPriceEntity price, EstimateLineRoomMaterialEntity template) {
        EstimateLineRoomMaterialEntity pkg = new EstimateLineRoomMaterialEntity();
        pkg.setRoomQty(roomQty);
        pkg.setBranch(ConsumptionBranch.finishing);
        pkg.setFinishingType(resolveFinishingType(typeId));
        pkg.setNormQty(template != null ? template.getNormQty() : null);
        pkg.setConsumptionBasis(template != null && template.getConsumptionBasis() != null
                ? template.getConsumptionBasis() : ConsumptionBasis.PER_UNIT);
        pkg.setRangeMin(price.getMinPrice());
        pkg.setRangeMax(price.getMaxPrice());
        pkg.setAppliedFromPackage(true);
        // Fixed package allocation quantity — reuse the manual-override representation (#1) so the
        // resolved physical quantity is EXACTLY the allocation, independent of Volume / basis.
        pkg.setQtyOverridden(true);
        pkg.setManualQty(alloc);
        estimateLineRoomMaterialDao.save(pkg);
        roomQty.getMaterials().add(pkg);
    }

    /**
     * The resolved physical NEED of a finishing consumption line (mirrors the assembler's resolved
     * physical quantity, #1/#4): {@code manualQty} when overridden, else {@code norm} for PER_ROOM,
     * else {@code norm × Volume} for PER_UNIT. A {@code null} norm/qty/volume contributes zero.
     */
    private static BigDecimal resolvedNeed(EstimateLineRoomMaterialEntity material) {
        if (material.isQtyOverridden()) {
            return nz(material.getManualQty());
        }
        BigDecimal norm = nz(material.getNormQty());
        if (material.getConsumptionBasis() == ConsumptionBasis.PER_ROOM) {
            return norm;
        }
        EstimateLineRoomQtyEntity roomQty = material.getRoomQty();
        BigDecimal volume = roomQty != null ? nz(roomQty.getQuantity()) : BigDecimal.ZERO;
        return norm.multiply(volume);
    }

    /** Null-safe {@link BigDecimal} — a {@code null} contributes {@link BigDecimal#ZERO}. */
    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Groups the package's priced positions by their assortment group (bulk: one {@code findAll()}).
     * Only positions whose material type has a per-package price row (i.e. is in {@code pricedTypeIds})
     * are included, so a group with no priced position simply does not appear.
     */
    private Map<AssortmentGroupEntity, List<AssortmentPositionEntity>> packagePositionsByGroup(
            Set<Long> pricedTypeIds) {
        Map<AssortmentGroupEntity, List<AssortmentPositionEntity>> byGroup = new LinkedHashMap<>();
        for (AssortmentPositionEntity position : assortmentPositionDao.findAll()) {
            AssortmentGroupEntity group = position.getGroup();
            MaterialTypeEntity type = position.getMaterialType();
            if (group == null || type == null || type.getId() == null
                    || !pricedTypeIds.contains(type.getId())) {
                continue;
            }
            byGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(position);
        }
        return byGroup;
    }

    /**
     * The group's applicable project rooms = the rooms whose {@code roomType.id} is in the group's
     * {@code roomTypes} M:N (point B). An empty group room-type set ⇒ no applicable rooms (do NOT fall
     * back to all rooms — the join is explicit per Wave 1).
     */
    private static List<RoomEntity> applicableRoomsForGroup(AssortmentGroupEntity group, List<RoomEntity> rooms) {
        Set<Long> groupRoomTypeIds = new HashSet<>();
        if (group.getRoomTypes() != null) {
            for (RoomTypeEntity type : group.getRoomTypes()) {
                if (type != null && type.getId() != null) {
                    groupRoomTypeIds.add(type.getId());
                }
            }
        }
        if (groupRoomTypeIds.isEmpty()) {
            return List.of();
        }
        List<RoomEntity> applicable = new ArrayList<>();
        for (RoomEntity room : rooms) {
            RoomTypeEntity roomType = room.getRoomType();
            if (roomType != null && roomType.getId() != null && groupRoomTypeIds.contains(roomType.getId())) {
                applicable.add(room);
            }
        }
        return applicable;
    }

    /**
     * Indexes the estimate's NON-package finishing material lines by {@code (roomId, finishingTypeId)}
     * so the merge computes NEED and the split from the current graph (a room may consume a type in
     * several cells — collect them all). Package-flagged lines are excluded (they were already removed
     * by the reset step and never contribute to NEED).
     */
    private Map<RoomTypeKey, List<EstimateLineRoomMaterialEntity>> indexFinishingLinesByRoomType(
            EstimateEntity estimate) {
        Map<RoomTypeKey, List<EstimateLineRoomMaterialEntity>> index = new HashMap<>();
        for (EstimateLineEntity line : estimate.getLines()) {
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                Long roomId = roomQty.getRoom() != null ? roomQty.getRoom().getId() : null;
                if (roomId == null) {
                    continue;
                }
                for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                    if (material.getBranch() != ConsumptionBranch.finishing
                            || material.isAppliedFromPackage()
                            || material.getFinishingType() == null) {
                        continue;
                    }
                    index.computeIfAbsent(new RoomTypeKey(roomId, material.getFinishingType().getId()),
                            k -> new ArrayList<>()).add(material);
                }
            }
        }
        return index;
    }

    /** A {@code (roomId, finishingTypeId)} key for indexing a room's finishing lines of a type. */
    private record RoomTypeKey(Long roomId, Long finishingTypeId) {
    }

    /** A room's total finishing NEED for a type + the lines that produced it (for the split). */
    private record RoomNeed(Long roomId, BigDecimal need, List<EstimateLineRoomMaterialEntity> lines) {
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
    @Transactional
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
        Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork =
                consumptionsByWorkId(memberWorks);

        List<AssignmentPlan> plans = new ArrayList<>();
        for (WorkItemEntity work : memberWorks) {
            planWorkOverRooms(work, offerPackage, rooms, existing, assortmentByType, consumptionsByWork, plans);
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
    @Transactional
    public CalculatedApply calculateApplyWorkToRooms(Long projectId, Long workItemId, String packageCode) {
        EstimateEntity estimate = resolveEstimateForRead(projectId);
        WorkItemEntity work = resolveWorkItem(workItemId);
        OfferPackageEntity offerPackage = resolvePackageOrNull(packageCode);

        List<RoomEntity> rooms = roomDao.findByProjectId(projectId);
        Set<CellKey> existing = existingAssignments(estimate);
        Map<Long, AssortmentPositionPriceEntity> assortmentByType =
                offerPackage == null ? Map.of() : assortmentPricesByType(offerPackage);

        Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork =
                consumptionsByWorkId(List.of(work));

        List<AssignmentPlan> plans = new ArrayList<>();
        planWorkOverRooms(work, offerPackage, rooms, existing, assortmentByType, consumptionsByWork, plans);
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
    @Transactional
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
    /**
     * Batch-loads the declared {@link WorkMaterialConsumptionEntity} rows for every work in
     * {@code works} in a single DAO call and groups them by owning work-item id (perf: replaces the
     * per-work {@code findByWorkItemIdIn(List.of(id))} N+1 in the apply-package / apply-work loops).
     * The DAO's {@code @EntityGraph} eagerly loads {@code getWorkItem()}, so grouping by its id is safe.
     */
    private Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWorkId(List<WorkItemEntity> works) {
        List<Long> workIds = works.stream()
                .map(WorkItemEntity::getId).filter(java.util.Objects::nonNull).toList();
        return groupConsumptionsBy(workIds);
    }

    /**
     * Batch-loads (once) and groups by owning work-item id the consumptions for every work referenced
     * by a staged-edit set — the input to the {@link #applyStagedEdit} replay so the per-cell
     * {@code doAssign -> seedMaterialLines} reads from the map instead of re-querying per (work, room)
     * (perf: replaces the {@code seedMaterialLines} N+1 on the apply-package / apply-work preview
     * replay). Only {@code ASSIGN} / per-cell {@code APPLY_PACKAGE} edits actually seed material lines,
     * but collecting every referenced work id is a cheap superset and keeps the helper total.
     */
    private Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsForStagedEdits(List<StagedEdit> stagedEdits) {
        if (stagedEdits == null || stagedEdits.isEmpty()) {
            return Map.of();
        }
        List<Long> workIds = stagedEdits.stream()
                .filter(java.util.Objects::nonNull)
                .map(StagedEdit::workItemId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return groupConsumptionsBy(workIds);
    }

    /** Single DAO call + group-by-work-id for a batch of work ids (perf: avoids per-work N+1). */
    private Map<Long, List<WorkMaterialConsumptionEntity>> groupConsumptionsBy(List<Long> workIds) {
        return (workIds.isEmpty() ? List.<WorkMaterialConsumptionEntity>of()
                : workMaterialConsumptionDao.findByWorkItemIdIn(workIds))
                .stream()
                .filter(c -> c.getWorkItem() != null && c.getWorkItem().getId() != null)
                .collect(java.util.stream.Collectors.groupingBy(c -> c.getWorkItem().getId()));
    }

    private void planWorkOverRooms(
            WorkItemEntity work,
            OfferPackageEntity offerPackage,
            List<RoomEntity> rooms,
            Set<CellKey> existing,
            Map<Long, AssortmentPositionPriceEntity> assortmentByType,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWork,
            List<AssignmentPlan> out) {
        WorkVolumeFormulaEntity defaultFormula =
                workVolumeFormulaDao.findByWorkItemId(work.getId()).orElse(null);
        WorkPackageOverrideEntity override = offerPackage == null ? null
                : workPackageOverrideDao
                        .findByWorkItemIdAndOfferPackageId(work.getId(), offerPackage.getId())
                        .orElse(null);
        String unitCode = work.getUnit() != null ? work.getUnit().getCode() : null;
        // Consumptions are batch-loaded ONCE by the caller (perf: was one DAO call per work — a
        // severe N+1 across all member works during apply-package / apply-work).
        List<WorkMaterialConsumptionEntity> consumptions =
                consumptionsByWork.getOrDefault(work.getId(), List.of());
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
                        range.min(), range.max(), false, basisOf(consumption)));
            } else if (consumption.getBranch() == ConsumptionBranch.finishing
                    && consumption.getFinishingMaterialType() != null) {
                Long typeId = consumption.getFinishingMaterialType().getId();
                AssortmentPositionPriceEntity assortment = assortmentByType.get(typeId);
                if (assortment != null) {
                    // Assortment_Placeholder: carry the position's per-package band (R11.8).
                    lines.add(new MaterialLinePlan(
                            ConsumptionBranch.finishing, typeId, consumption.getNormQty(),
                            assortment.getMinPrice(), assortment.getMaxPrice(), true, basisOf(consumption)));
                } else {
                    if (finishingMaterials == null) {
                        finishingMaterials = finishingMaterialDao.findByActiveTrueAndRetailNetNotNull();
                    }
                    FinishingPriceRangeResolver.PriceRange range =
                            finishingPriceRangeResolver.rangeFor(finishingMaterials, typeId, packageId);
                    lines.add(new MaterialLinePlan(
                            ConsumptionBranch.finishing, typeId, consumption.getNormQty(),
                            range.min(), range.max(), false, basisOf(consumption)));
                }
            }
            // malformed consumption (branch/type mismatch) -> not planned
        }
        return lines;
    }

    /** The consumption's basis, defaulting to {@link ConsumptionBasis#PER_UNIT} when unset (#4). */
    private static ConsumptionBasis basisOf(WorkMaterialConsumptionEntity consumption) {
        return consumption.getConsumptionBasis() != null
                ? consumption.getConsumptionBasis() : ConsumptionBasis.PER_UNIT;
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

    /**
     * Resolves the project's estimate for a read (matrix read, {@code isDraft}, calculate/preview),
     * <b>creating a defaulted PLN/DRAFT estimate when none exists</b> via the shipped get-or-create
     * path ({@link EstimateService#getOrCreateEntityForProject(Long)}), rather than 404-ing (R1.7).
     *
     * <p>This mirrors the sibling {@code EstimateController#getOrCreateForProject} endpoint so a first
     * open of an existing project created before the estimate feature yields an empty, assignable
     * matrix instead of {@code 404 error.entity.not.found}. Because a first read may INSERT the
     * estimate row, the callers ({@link #getMatrix}, {@link #isDraft}, the {@code calculate*} cores)
     * run in a read-write transaction; a repeated read resolves the same row without creating a second
     * one (the {@code estimates.project_id} UNIQUE is the backstop).
     */
    private EstimateEntity resolveEstimateForRead(Long projectId) {
        return estimateService.getOrCreateEntityForProject(projectId);
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
     * @param consumptionBasis the copied consumption basis (#4): {@code PER_UNIT} ({@code norm ×
     *                         Volume}) or {@code PER_ROOM} ({@code norm}); {@code PER_UNIT} default
     */
    public record MaterialLinePlan(
            ConsumptionBranch branch,
            Long typeId,
            BigDecimal normQty,
            BigDecimal rangeMin,
            BigDecimal rangeMax,
            boolean assortment,
            ConsumptionBasis consumptionBasis) {
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
                    materialLineId, null, packageCode, null);
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

    /**
     * The calculated apply-package MERGE result (FOR-05-05 Amendment A1): the package code whose
     * assortment finishing materials to merge. The merge is computed on replay against the current
     * estimate graph (after the batch's assigns), so the plan carries only the package code and maps
     * to a single {@code MERGE_PACKAGE_MATERIALS} staged edit. Persists nothing — written only through
     * the batched {@link #applyAssignments} Save (R15.6).
     *
     * @param packageCode the package to merge, or {@code null} for an empty (no-op) plan
     */
    public record CalculatedPackageMerge(String packageCode) {

        /** An empty merge plan (blank/unknown package). */
        public static final CalculatedPackageMerge EMPTY = new CalculatedPackageMerge(null);

        /** {@code true} iff nothing is merged (no staged edit is produced). */
        public boolean isEmpty() {
            return packageCode == null;
        }

        /** The staged {@code MERGE_PACKAGE_MATERIALS} edit this plan maps to on the batched Save. */
        public List<StagedEdit> toStagedEdits() {
            if (packageCode == null) {
                return List.of();
            }
            return List.of(StagedEdit.mergePackageMaterials(packageCode));
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
     * <p><b>Natural-key resolution (R13.4).</b> Single-cell material edits are resolved by the natural
     * keys the client always has — {@code (workItemId, roomId, branch, typeId)} — walked over the
     * in-memory estimate graph ({@link #findLine} → {@link #findRoomQty} → {@link #findMaterialLine}),
     * NOT by a persisted {@code roomQtyId}/{@code materialLineId}. This makes a material edit replay
     * correctly against a cell that was assigned <em>earlier in the same batch</em> (so it has no
     * persisted id yet) as well as against an already-persisted cell, and matches the matrix read model
     * where a cell exposes only {@code (workItemId, roomId)} and a material line is keyed by
     * {@code (branch, typeId)} within the cell. Every branch is a NO-OP when the target cell or line is
     * absent from the current staged graph (e.g. the assign was undone) — a preview must be total and
     * must never throw (mirrors the unassign no-op-when-missing behavior).
     *
     * <ul>
     *   <li>{@code ADD_MATERIAL} — add a {@code (branch, type)} line on the cell resolved from
     *       {@code (workItemId, roomId)} (copying the resolver range, R6.2); no-op when the cell is
     *       absent;</li>
     *   <li>{@code REMOVE_MATERIAL} — remove that {@code (branch, type)} line (R6.2); when
     *       {@code roomId} is {@code null} this is a bulk remove across every assigned cell of the
     *       edit's {@code workItemId} (Work_Material_Summary bulk remove);</li>
     *   <li>{@code CHOOSE_CONCRETE} — set the concrete product on the {@code (branch, type)} line of
     *       the cell resolved from {@code (workItemId, roomId)} and collapse the line (R6.3, R6.4);
     *       no-op when the cell or line is absent;</li>
     *   <li>{@code BULK_CHOOSE_CONCRETE} — apply the concrete product to the edit's {@code (branch,
     *       type)} across every assigned cell of the edit's {@code workItemId} (R9.3).</li>
     * </ul>
     */
    private void applyMaterialEdit(EstimateEntity estimate, StagedEdit edit) {
        switch (edit.kind()) {
            case ADD_MATERIAL -> {
                EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
                if (roomQty != null) {
                    doAddMaterialLine(roomQty, edit.branch(), edit.typeId());
                }
            }
            case REMOVE_MATERIAL -> {
                if (edit.roomId() != null) {
                    // Single cell: remove the (branch, type) line from the resolved cell.
                    EstimateLineRoomQtyEntity roomQty =
                            resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
                    if (roomQty != null) {
                        doRemoveMaterialLine(roomQty, edit.branch(), edit.typeId());
                    }
                } else {
                    // Bulk (Work_Material_Summary): remove the (branch, type) line across the whole work.
                    EstimateLineEntity line = findLine(estimate, edit.workItemId());
                    if (line != null) {
                        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                            doRemoveMaterialLine(roomQty, edit.branch(), edit.typeId());
                        }
                    }
                }
            }
            case CHOOSE_CONCRETE -> {
                EstimateLineRoomQtyEntity roomQty = resolveStagedCell(estimate, edit.workItemId(), edit.roomId());
                if (roomQty != null) {
                    EstimateLineRoomMaterialEntity material =
                            findMaterialLine(roomQty, edit.branch(), edit.typeId());
                    if (material != null) {
                        applyChooseConcrete(material, edit.materialId());
                    }
                }
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

    /**
     * Resolves the {@code (workItemId, roomId)} cell within the in-memory estimate graph, or
     * {@code null} when the work is unassigned or the cell is absent. Used by the staged material-edit
     * replay so edits work against freshly-staged (unsaved) cells in the same batch as well as
     * persisted ones, and are no-ops when the cell is missing (preview must be total).
     */
    private EstimateLineRoomQtyEntity resolveStagedCell(EstimateEntity estimate, Long workItemId, Long roomId) {
        EstimateLineEntity line = findLine(estimate, workItemId);
        return line == null ? null : findRoomQty(line, roomId);
    }

    // --- resolution helpers -----------------------------------------------------------------

    /**
     * Resolves the project's estimate for a <b>write</b> and asserts it is still DRAFT (R7).
     * Get-or-creates a defaulted PLN/DRAFT estimate when none exists (R1.7) via the shipped
     * {@link EstimateService#getOrCreateEntityForProject(Long)} path, so a first {@code Save}
     * (assign / apply / material edit) on a brand-new project works instead of 404-ing; the DRAFT
     * gate then still applies to the resolved estimate.
     */
    private EstimateEntity resolveDraftEstimate(Long projectId) {
        EstimateEntity estimate = estimateService.getOrCreateEntityForProject(projectId);
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
        RECOMPUTE_FINISHING,
        /** FOR-05-05 (#7): override a {@code (work, room)} cell's Volume with a manual positive quantity. */
        SET_QUANTITY,
        /** FOR-05-05 (#7): clear a cell's manual Volume override, reverting to the formula-resolved Volume. */
        CLEAR_QUANTITY,
        /**
         * FOR-05-05 amendment #1: override a single material line's physical quantity with an explicit
         * manual value (independent of {@code norm × Volume}). The target line is resolved by natural
         * keys {@code (workItemId, roomId, branch, typeId)} like {@code CHOOSE_CONCRETE}; the manual
         * value is carried in {@code quantity}.
         */
        SET_MATERIAL_QUANTITY,
        /**
         * FOR-05-05 amendment #1: clear a material line's manual quantity override, reverting to the
         * derived quantity ({@code norm × Volume} for PER_UNIT, {@code norm} for PER_ROOM). The target
         * line is resolved by natural keys {@code (workItemId, roomId, branch, typeId)}.
         */
        CLEAR_MATERIAL_QUANTITY,
        /**
         * FOR-05-05 (#19): fill every in-scope PLACEHOLDER material line with the CHEAPEST concrete
         * product of its material type, collapsing the line to that product's {@code retailNet}. Scope
         * is optional: whole matrix ({@code workItemId == null}), one work ({@code workItemId} set,
         * {@code roomId == null}), or one cell ({@code workItemId} + {@code roomId}).
         */
        APPLY_CHEAPEST,
        /**
         * FOR-05-05 Amendment A1: merge the applied package's assortment finishing materials into the
         * estimate. The client stages ONE such edit (carrying only {@code packageCode}) when applying
         * a package, in addition to the expanded ASSIGN/APPLY_PACKAGE delta. Replayed against the
         * CURRENT estimate graph (after the same batch's assign edits have run so consumption NEED is
         * known): it first REMOVES all existing package-flagged lines and resets any prior "extra"
         * lines back to the works' full consumption need, then distributes each package position's
         * project-wide {@code referenceQty} across the group's applicable rooms via water-fill
         * (smallest-need-first, capped at need, leftover discarded), splitting each covered room's
         * finishing line into a package-flagged line (qty = allocation) plus an extra line
         * (qty = need − allocation) where consumption exceeds the allocation. Idempotent for the same
         * package; re-applying a different package recomputes cleanly.
         */
        MERGE_PACKAGE_MATERIALS
    }

    /**
     * One staged matrix edit in a batched {@code Save} (design §B4). A single flat record covers every
     * {@link EditKind}; only the fields relevant to a given kind are populated (the rest are
     * {@code null}). Cell edits carry {@code workItemId}/{@code roomId}; single-cell material edits
     * (ADD/REMOVE/CHOOSE_CONCRETE) are resolved by the natural keys {@code workItemId}/{@code roomId}/
     * {@code branch}/{@code typeId} (and {@code materialId} for choose-concrete) within the estimate
     * graph, so they replay against freshly-staged cells too; the persisted {@code roomQtyId}/
     * {@code materialLineId} fields are retained for wire-compatibility but are NOT used by the staged
     * material-edit replay. Apply/recompute-originated edits carry {@code packageCode} and the
     * per-cell / per-line target they resolved to at calculate time.
     *
     * @param kind           the edit kind
     * @param workItemId     target work (ASSIGN / UNASSIGN / APPLY_PACKAGE / material edit / bulk)
     * @param roomId         target room (ASSIGN / UNASSIGN / APPLY_PACKAGE / single-cell material edit;
     *                       {@code null} for a bulk REMOVE_MATERIAL across the whole work)
     * @param roomQtyId      persisted cell id (wire-compat only; unused by the staged material replay)
     * @param branch         material branch for a material edit
     * @param typeId         material type id for a material edit
     * @param materialLineId persisted material-line id (wire-compat / RECOMPUTE_FINISHING; unused by the
     *                       staged CHOOSE_CONCRETE replay, which resolves by (branch, typeId))
     * @param materialId     chosen concrete product (CHOOSE_CONCRETE / BULK_CHOOSE_CONCRETE)
     * @param packageCode    active package code (ASSIGN / APPLY_PACKAGE / RECOMPUTE_FINISHING)
     * @param quantity       the manual Volume for a {@code SET_QUANTITY} cell override (#7); {@code null}
     *                       for every other kind (including {@code CLEAR_QUANTITY})
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
            String packageCode,
            BigDecimal quantity) {

        /** Convenience factory for an assign cell edit. */
        public static StagedEdit assign(Long workItemId, Long roomId, String packageCode) {
            return new StagedEdit(
                    EditKind.ASSIGN, workItemId, roomId, null, null, null, null, null, packageCode, null);
        }

        /** Convenience factory for an unassign cell edit. */
        public static StagedEdit unassign(Long workItemId, Long roomId) {
            return new StagedEdit(
                    EditKind.UNASSIGN, workItemId, roomId, null, null, null, null, null, null, null);
        }

        /**
         * Convenience factory for an {@code APPLY_CHEAPEST} scoped edit (FOR-05-05 #19). Both scope
         * fields are optional: {@code (null, null)} targets the whole matrix, {@code (workItemId,
         * null)} one work's cells, {@code (workItemId, roomId)} a single cell.
         */
        public static StagedEdit applyCheapest(Long workItemId, Long roomId) {
            return new StagedEdit(
                    EditKind.APPLY_CHEAPEST, workItemId, roomId, null, null, null, null, null, null, null);
        }

        /**
         * Convenience factory for a {@code SET_MATERIAL_QUANTITY} edit (amendment #1): override the
         * physical quantity of the {@code (workItemId, roomId, branch, typeId)} material line with the
         * manual {@code quantity}.
         */
        public static StagedEdit setMaterialQuantity(
                Long workItemId, Long roomId, ConsumptionBranch branch, Long typeId, BigDecimal quantity) {
            return new StagedEdit(
                    EditKind.SET_MATERIAL_QUANTITY, workItemId, roomId, null, branch, typeId, null, null,
                    null, quantity);
        }

        /**
         * Convenience factory for a {@code CLEAR_MATERIAL_QUANTITY} edit (amendment #1): clear the
         * manual quantity override on the {@code (workItemId, roomId, branch, typeId)} material line.
         */
        public static StagedEdit clearMaterialQuantity(
                Long workItemId, Long roomId, ConsumptionBranch branch, Long typeId) {
            return new StagedEdit(
                    EditKind.CLEAR_MATERIAL_QUANTITY, workItemId, roomId, null, branch, typeId, null, null,
                    null, null);
        }

        /**
         * Convenience factory for a {@code MERGE_PACKAGE_MATERIALS} edit (FOR-05-05 Amendment A1):
         * merge {@code packageCode}'s assortment finishing materials into the estimate. Carries only
         * the package code — the merge resolves everything else from the estimate graph on replay.
         */
        public static StagedEdit mergePackageMaterials(String packageCode) {
            return new StagedEdit(
                    EditKind.MERGE_PACKAGE_MATERIALS, null, null, null, null, null, null, null,
                    packageCode, null);
        }
    }
}
