package com.foremen.service.estimate.materials;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.MaterialsReserveMap;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.EstimateService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.matrix.EstimateMatrixAssembler;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.model.EstimateLineRoomMaterialServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomMaterialServiceModel;
import com.foremen.service.model.mapper.EstimateLineRoomMaterialServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * The Materials tab service (FOR-05-05b, design §B3) — owns the tab's read model and the single
 * reserve-map write.
 *
 * <p>It is a {@link ProjectScopedService} keyed on {@link EstimateLineRoomMaterialEntity} — the same
 * entity {@code EstimateAssignmentService} is scoped on — so its {@link #getProjectIdPath()} resolves
 * an entity's owning project through {@code roomQty.line.estimate.project} (R8, R12.1, R12.2). The
 * bespoke read methods below take the {@code projectId} directly and are guarded at the controller
 * (task 6.1) by {@code @RequiresPermission(ESTIMATE, READ)}.
 *
 * <h2>Read model (R1.x, R2.x, R3.x, R6.x, R10.3, R12.x)</h2>
 * {@link #getMaterials(Long, boolean)} resolves the project's estimate through the shipped
 * get-or-create path (so a pre-estimate project returns an empty structure, not a {@code 404},
 * mirroring {@code EstimateAssignmentService.getMatrix}), obtains the kosztorys matrix via the shipped
 * {@link EstimateMatrixAssembler}, and delegates to the pure {@link MaterialsListAssembler}. It is
 * read-only and <b>independent of the DRAFT lifecycle</b> (R10.3) — the tab always renders regardless
 * of the estimate's writable state.
 *
 * <h2>{@code editable} lifecycle half (R10.1, R10.2)</h2>
 * {@link #isDraft(Long)} reuses {@link EstimateAssignmentService#isDraft(Long)} (the same lifecycle
 * check the kosztorys uses) so the controller can fold it with the caller's {@code ESTIMATE} UPDATE
 * grant into the {@code editable} flag.
 *
 * <h2>Reserve-map write (R4, R8.3, R9.4, R10.2, R12.5)</h2>
 * {@link #saveReserveMap(Long, MaterialsReserveRequest)} resolves the project's <b>DRAFT</b> estimate
 * and gates the write through {@link DraftGateGuard#assertDraft(EstimateEntity)} (409
 * {@code error.estimate.locked} once past DRAFT, R10.2); server-validates <b>every</b> request entry
 * (percent in {@code [0..100]}, ≤2 decimals) and rejects the <b>whole</b> write with a localized 400
 * {@code error.estimate.reserve.invalid} on any invalid/malformed entry (R9.4); prunes stale keys
 * (material ids no longer chosen in the kosztorys, R12.5); recomputes and stores each surviving
 * entry's {@code asIsQty}/{@code effectiveQty}/{@code bruttoTotal} using the <b>same computation
 * core</b> the read assembler uses (so a stored total always equals what the tab computes live);
 * persists the JSONB column; and returns the refreshed read model.
 */
@Service
public class MaterialsListService
        implements ProjectScopedService<EstimateLineRoomMaterialServiceModel,
        EstimateLineRoomMaterialServiceExtendedModel, EstimateLineRoomMaterialEntity, Long> {

    /** Message code for an out-of-range or malformed reserve entry (400, R9.4). */
    static final String RESERVE_INVALID_MESSAGE = "error.estimate.reserve.invalid";

    /** The inclusive upper bound of a reserve percent (R9.1). */
    private static final BigDecimal RESERVE_MAX_PERCENT = new BigDecimal("100");

    /** The maximum number of decimal places for a reserve percent (R9.1). */
    private static final int RESERVE_MAX_SCALE = 2;

    private final EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;
    private final EstimateLineRoomMaterialServiceMapper estimateLineRoomMaterialServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    private final EstimateService estimateService;
    private final EstimateMatrixAssembler estimateMatrixAssembler;
    private final MaterialsListAssembler materialsListAssembler;
    private final EstimateAssignmentService estimateAssignmentService;
    private final DraftGateGuard draftGateGuard;

    public MaterialsListService(
            EstimateLineRoomMaterialDao estimateLineRoomMaterialDao,
            EstimateLineRoomMaterialServiceMapper estimateLineRoomMaterialServiceMapper,
            ProjectAccessCache projectAccessCache,
            AuditLogDao auditLogDao,
            EntityManager entityManager,
            EstimateService estimateService,
            EstimateMatrixAssembler estimateMatrixAssembler,
            MaterialsListAssembler materialsListAssembler,
            EstimateAssignmentService estimateAssignmentService,
            DraftGateGuard draftGateGuard) {
        this.estimateLineRoomMaterialDao = estimateLineRoomMaterialDao;
        this.estimateLineRoomMaterialServiceMapper = estimateLineRoomMaterialServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.estimateService = estimateService;
        this.estimateMatrixAssembler = estimateMatrixAssembler;
        this.materialsListAssembler = materialsListAssembler;
        this.estimateAssignmentService = estimateAssignmentService;
        this.draftGateGuard = draftGateGuard;
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
     * {@code "roomQty.line.estimate.project.id"} — identical to {@code EstimateAssignmentService}.
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
    // Materials tab read model (design §B3)
    // =====================================================================================

    /**
     * Assembles the Materials tab read model for {@code projectId} (design §B3,
     * {@code GET /project/{projectId}/materials}). Read-only and <b>independent of DRAFT</b> (R10.3):
     * it resolves (or get-or-creates) the project's estimate — so a pre-estimate project returns an
     * empty structure instead of {@code 404} (R1.x, R12.1, R12.2) — obtains the kosztorys matrix via
     * the shipped {@link EstimateMatrixAssembler}, and delegates the projection to the pure
     * {@link MaterialsListAssembler}. Every quantity, price and the fulfilment percentage are derived
     * from that matrix; the only persisted material state (the reserve map) is read verbatim off the
     * estimate.
     *
     * <p>Because a first read may INSERT the estimate row (via the get-or-create path), this runs in a
     * read-write transaction, mirroring {@code EstimateAssignmentService.getMatrix}.
     *
     * @param projectId the owning project whose materials to render
     * @param editable  whether the reserve map may be written (project DRAFT + caller ESTIMATE UPDATE,
     *                  resolved by the controller and passed through)
     * @return the assembled {@link MaterialsListDto}
     */
    @Transactional
    public MaterialsListDto getMaterials(Long projectId, boolean editable) {
        EstimateEntity estimate = estimateService.getOrCreateEntityForProject(projectId);
        EstimateMatrixDto matrix = estimateMatrixAssembler.assemble(estimate, projectId, editable);
        MaterialsReserveMap reserveMap = estimate.getMaterialsReserveMap();
        return materialsListAssembler.assemble(matrix, vatRatePct(estimate), reserveMap, editable);
    }

    /**
     * Persists the project's reserve map from a client-expanded request and returns the refreshed read
     * model (design §B3, {@code PUT /project/{projectId}/materials/reserve}, R4.2, R4.3, R4.4, R8.3,
     * R9.4, R10.2, R12.5).
     *
     * <p>Steps, in order:
     * <ol>
     *   <li><b>DRAFT gate (R10.2).</b> Resolve the project's estimate through the shipped
     *       get-or-create path and run it through {@link DraftGateGuard#assertDraft(EstimateEntity)} —
     *       a non-DRAFT estimate rejects the write with 409 {@code error.estimate.locked}.</li>
     *   <li><b>Server validation (R9.4).</b> Validate <em>every</em> request entry (percent in
     *       {@code [0..100]}, ≤2 decimals); any invalid/malformed entry rejects the <b>whole</b> write
     *       with 400 {@code error.estimate.reserve.invalid}. A {@code null}/empty percent is
     *       <em>unset</em> (identity), not an error (R9.3).</li>
     *   <li><b>Stale-key pruning (R12.5).</b> Drop any entry whose {@code materialId} is not a concrete
     *       material chosen anywhere in the current kosztorys, so a removed/changed product never
     *       persists a dangling reserve.</li>
     *   <li><b>Recompute + store (R4.2, R4.3).</b> Recompute each surviving entry's
     *       {@code asIsQty}/{@code effectiveQty}/{@code bruttoTotal} from the live kosztorys using the
     *       same computation core the read assembler uses (the assembled {@link MaterialRowDto} carries
     *       exactly those figures), so a stored total always equals what the tab computes live.</li>
     *   <li><b>Persist + refresh.</b> Set the JSONB column on the managed estimate (flushed by the
     *       transaction), then return {@link #getMaterials(Long, boolean)} recomputed off the just-saved
     *       map.</li>
     * </ol>
     *
     * <p>There is <b>no branch-level stored value</b> — the request carries the client-expanded
     * per-material list only (the mass-apply convenience is expanded client-side, R4.4).
     *
     * @param projectId the owning project whose reserve map to persist
     * @param request   the client-expanded per-material reserve entries
     * @return the refreshed {@link MaterialsListDto} (recomputed off the persisted map)
     * @throws ForemenApiException 409 {@code error.estimate.locked} when the estimate is not DRAFT
     *                             (R10.2); 400 {@code error.estimate.reserve.invalid} when any entry is
     *                             out of range or malformed (R9.4)
     */
    @Transactional
    public MaterialsListDto saveReserveMap(Long projectId, MaterialsReserveRequest request) {
        // 1) Resolve the DRAFT estimate and gate the write on the lifecycle (R10.2).
        EstimateEntity estimate = estimateService.getOrCreateEntityForProject(projectId);
        draftGateGuard.assertDraft(estimate);

        // 2) Server-validate every entry — reject the whole write on any invalid entry (R9.4).
        List<MaterialsReserveRequest.ReserveEntry> entries =
                request == null || request.entries() == null ? List.of() : request.entries();
        for (MaterialsReserveRequest.ReserveEntry entry : entries) {
            if (entry == null || entry.materialId() == null || !isValidPercent(entry.percent())) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, RESERVE_INVALID_MESSAGE);
            }
        }

        // 3) Assemble the kosztorys once: it is both the stale-key oracle (which material ids exist)
        //    and the computation core for the recomputed totals (R12.5, R4.2, R4.3).
        boolean editable = isDraft(projectId);
        EstimateMatrixDto matrix = estimateMatrixAssembler.assemble(estimate, projectId, editable);
        BigDecimal vatRatePct = vatRatePct(estimate);

        // Collapse the request into the authoritative per-material percent (last write wins). A
        // null/empty percent is unset (identity) — dropped so it reads as "no reserve" (R9.3, R4.5).
        Map<Long, BigDecimal> requestedPercents = new LinkedHashMap<>();
        for (MaterialsReserveRequest.ReserveEntry entry : entries) {
            if (entry.percent() == null) {
                requestedPercents.remove(entry.materialId());
            } else {
                requestedPercents.put(entry.materialId(), entry.percent());
            }
        }

        // 4) Prune stale keys and recompute each surviving entry's totals off the live kosztorys, using
        //    the assembler as the single computation core (so stored totals equal the live figures).
        Map<Long, MaterialsReserveMap.ReserveEntry> pruned =
                recomputeReserveEntries(matrix, vatRatePct, requestedPercents);

        // 5) Persist the JSONB column (flushed on transaction commit) and return the refreshed model.
        estimate.setMaterialsReserveMap(new MaterialsReserveMap(pruned));
        entityManager.flush();
        return getMaterials(projectId, editable);
    }

    /**
     * Recompute the stored reserve entries off the live kosztorys, pruning stale keys (R12.5) and
     * computing each surviving material's {@code asIsQty}/{@code effectiveQty}/{@code bruttoTotal} with
     * the same core the read assembler uses (R4.2, R4.3). Assembling the read model with the requested
     * percents applied yields, on each {@link MaterialRowDto}, exactly the figures to persist — so the
     * stored totals never drift from what the tab computes live.
     */
    private Map<Long, MaterialsReserveMap.ReserveEntry> recomputeReserveEntries(
            EstimateMatrixDto matrix,
            BigDecimal vatRatePct,
            Map<Long, BigDecimal> requestedPercents) {
        MaterialsReserveMap requested = new MaterialsReserveMap(new LinkedHashMap<>(materialsFromPercents(requestedPercents)));
        MaterialsListDto model = materialsListAssembler.assemble(matrix, vatRatePct, requested, false);

        Map<Long, MaterialsReserveMap.ReserveEntry> pruned = new LinkedHashMap<>();
        for (MaterialBranchGroupDto branch : model.branches()) {
            for (MaterialRowDto row : branch.rows()) {
                BigDecimal percent = requestedPercents.get(row.materialId());
                if (percent == null) {
                    continue; // unset / not requested for this material ⇒ no stored entry (R4.5)
                }
                pruned.put(row.materialId(), new MaterialsReserveMap.ReserveEntry(
                        percent,
                        row.asIsTotalQty(),
                        row.effectiveTotalQty(),
                        row.rowTotalPrice() == null ? null : row.rowTotalPrice().brutto()));
            }
        }
        return pruned;
    }

    /** A percent-only reserve map (no totals yet) used to drive the assembler's live recompute. */
    private static Map<Long, MaterialsReserveMap.ReserveEntry> materialsFromPercents(
            Map<Long, BigDecimal> requestedPercents) {
        Map<Long, MaterialsReserveMap.ReserveEntry> byMaterialId = new LinkedHashMap<>();
        for (Map.Entry<Long, BigDecimal> entry : requestedPercents.entrySet()) {
            byMaterialId.put(entry.getKey(),
                    new MaterialsReserveMap.ReserveEntry(entry.getValue(), null, null, null));
        }
        return byMaterialId;
    }

    /**
     * Whether a requested reserve percent is acceptable (R9.1, R9.4): a {@code null}/empty percent is
     * <em>unset</em> (identity) and accepted; otherwise it must be a non-negative number in
     * {@code [0..100]} with at most {@value #RESERVE_MAX_SCALE} decimal places. Identical bounds to the
     * client zod schema.
     */
    static boolean isValidPercent(BigDecimal percent) {
        if (percent == null) {
            return true; // unset ⇒ identity, not an error (R9.3)
        }
        if (percent.signum() < 0 || percent.compareTo(RESERVE_MAX_PERCENT) > 0) {
            return false; // out of [0..100] (R9.1, R9.2)
        }
        return percent.stripTrailingZeros().scale() <= RESERVE_MAX_SCALE; // ≤2 decimals (R9.1)
    }

    /**
     * Whether the project's estimate is currently DRAFT — the lifecycle half of the {@code editable}
     * flag (R10.1, R10.2). Reuses {@link EstimateAssignmentService#isDraft(Long)} (the same check the
     * kosztorys uses); the controller folds it with the caller's {@code ESTIMATE} UPDATE grant.
     *
     * @param projectId the owning project whose estimate lifecycle to read
     * @return {@code true} iff the estimate exists and is DRAFT
     */
    @Transactional
    public boolean isDraft(Long projectId) {
        return estimateAssignmentService.isDraft(projectId);
    }

    /**
     * The estimate's VAT rate as a percentage (e.g. {@code 23.00}) for the net&rarr;brutto derivation,
     * or {@code null} when no VAT rate is set (the assembler then treats it as {@code 0}, matching
     * {@code EstimateRecomputeService}'s {@code coalesce(rate, 0)} rule, R7.2).
     */
    private static BigDecimal vatRatePct(EstimateEntity estimate) {
        VatRateEntity vatRate = estimate.getVatRate();
        return vatRate == null ? null : vatRate.getRate();
    }
}
