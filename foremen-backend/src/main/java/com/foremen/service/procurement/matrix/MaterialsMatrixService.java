package com.foremen.service.procurement.matrix;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProcurementLineDao;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.PurchaseLineReconciliationService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.materials.MaterialBranchGroupDto;
import com.foremen.service.estimate.materials.MaterialRowDto;
import com.foremen.service.estimate.materials.MaterialsListDto;
import com.foremen.service.estimate.materials.MaterialsListService;
import com.foremen.service.estimate.materials.MoneyBrutto;
import com.foremen.service.model.PurchaseLineServiceModel;
import com.foremen.service.model.mapper.ProcurementLineServiceMapper;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-05 (design §B2, R1, R6, R7) — the materials-matrix read model service. It owns the single
 * read {@link #getMatrix(Long)} that joins the FOR-05-05b planned-materials list (plan side) with the
 * FOR-06-04 reconciled procurement lines (fact side) and folds them, per planned material, into the
 * money-based % выборки read model via the pure {@link MaterialsMatrixAssembler}.
 *
 * <p>It is a pure read-only projection over two existing read models — it <strong>reads</strong>, and
 * changes none:
 * <ul>
 *   <li><b>plan side</b> — {@link MaterialsListService#getMaterials(Long, boolean)} (editable=false),
 *       so a pre-estimate project resolves through the FOR-05-05b get-or-create path to an empty
 *       structure, never a {@code 404} (R1.1). The planned rows carry the reserve-adjusted effective
 *       quantity, the planned brutto, and the localized material name / branch / category / unit read
 *       verbatim from the FOR-05-05b model — the same {@code ru → pl → code}-resolved source the
 *       reused {@link PlannedMaterialCandidateProvider} projects, so the matched-material display
 *       fields of the fact side line up with the plan rows by {@code materialId} (R1.1);</li>
 *   <li><b>fact side</b> — {@link PurchaseLineReconciliationService#list(Long, Long,
 *       com.foremen.dao.model.MatchStatus, Long)}, the shipped FOR-06-04 reconciled-line read path
 *       (project-filtered, source-document fields resolved). Only lines with a non-null
 *       {@code matchedMaterialId} and {@code matchStatus ∈ {AUTO, MANUAL_MATCHED}} are kept — the
 *       assembler drops every other line, so unmatched / cleared / unplanned lines contribute to no
 *       planned-material row (R1.6).</li>
 * </ul>
 *
 * <p><b>Project scope (R6.3).</b> Following the FOR-03-04a single-contract shape (mirrors
 * {@link PurchaseLineReconciliationService}), it implements exactly one CRUD contract —
 * {@link ProjectScopedService} <strong>over the reconciled procurement line</strong> — with the single
 * mandatory per-entity override {@link #getProjectIdPath()} &rarr; {@code "document.project.id"}, so
 * any LIST read flows through the inherited project filter and never returns another project's lines.
 * The bespoke {@link #getMatrix(Long)} read takes the {@code projectId} directly and is guarded at the
 * controller (task 3.1) by {@code PURCHASES}/READ; its fact side rides the already project-scoped
 * FOR-06-04 list read.
 *
 * <p><b>No notifications (R7.1).</b> The matrix is a read-only analytics surface — it changes no
 * domain state and emits no user-facing notifications.
 */
@Service
public class MaterialsMatrixService
        implements ProjectScopedService<PurchaseLineServiceModel, PurchaseLineServiceModel,
        ProcurementLineEntity, Long> {

    private final ProcurementLineDao lineDao;
    private final ProcurementLineServiceMapper serviceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    private final MaterialsListService materialsListService;
    private final PurchaseLineReconciliationService reconciliationService;
    private final MaterialsMatrixAssembler assembler;

    public MaterialsMatrixService(ProcurementLineDao lineDao,
                                  ProcurementLineServiceMapper serviceMapper,
                                  ProjectAccessCache projectAccessCache,
                                  AuditLogDao auditLogDao,
                                  EntityManager entityManager,
                                  MaterialsListService materialsListService,
                                  PurchaseLineReconciliationService reconciliationService,
                                  MaterialsMatrixAssembler assembler) {
        this.lineDao = lineDao;
        this.serviceMapper = serviceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.materialsListService = materialsListService;
        this.reconciliationService = reconciliationService;
        this.assembler = assembler;
    }

    // --- CRUD plumbing (inherited via ProjectScopedService) ------------------------------------

    @Override
    public AdminDao<ProcurementLineEntity, Long> getDao() {
        return lineDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ProcurementLineEntity, PurchaseLineServiceModel,
            PurchaseLineServiceModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ProcurementLineEntity> getDaoModelClass() {
        return ProcurementLineEntity.class;
    }

    // --- ProjectScopedService overrides --------------------------------------------------------

    /**
     * The single mandatory per-entity override (entity-creation-rules step 4, R6.3): a procurement
     * line resolves its owning project through {@code document.project}, so any LIST read is
     * auto-filtered to the caller's accessible projects — identical to
     * {@link PurchaseLineReconciliationService}.
     */
    @Override
    public String getProjectIdPath() {
        return "document.project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Materials-matrix read model (design §B2) ----------------------------------------------

    /**
     * Assemble the materials-matrix read model for {@code projectId} (design §B2, R1): resolve the
     * FOR-05-05b planned materials (plan side, reusing {@link MaterialsListService} so a pre-estimate
     * project returns an empty structure, not a {@code 404}, R1.1), load the project's reconciled
     * lines through the FOR-06-04 read path (fact side), and delegate the plan × fact fold to the pure
     * {@link MaterialsMatrixAssembler}. The assembler keeps only matched lines
     * ({@code matchStatus ∈ {AUTO, MANUAL_MATCHED}} with a non-null {@code matchedMaterialId}), so
     * unmatched / cleared / unplanned lines contribute to no row (R1.6). Emits no notifications (R7.1).
     *
     * <p>Runs in a read-write transaction because the plan side's FOR-05-05b get-or-create may INSERT
     * the estimate row on a first read (mirroring {@link MaterialsListService#getMaterials}).
     *
     * @param projectId the owning project whose materials matrix to assemble
     * @return the assembled {@link MaterialsMatrixDto}; never {@code null}
     */
    @Transactional
    public MaterialsMatrixDto getMatrix(Long projectId) {
        List<MatrixPlannedRow> plannedRows = plannedRows(projectId);
        List<MatrixFactLine> factLines = factLines(projectId);
        return assembler.assemble(projectId, plannedRows, factLines);
    }

    // --- Plan side (FOR-05-05b materials list) --------------------------------------------------

    /**
     * The plan-side rows, read verbatim from the FOR-05-05b materials list in its stable order
     * (branch → category → first-seen). Each row's localized name / branch / category / unit, the
     * reserve-adjusted effective quantity, and the planned brutto are taken as-is (R1.1). A
     * pre-estimate project yields an empty list (the get-or-create path returns an empty structure).
     */
    private List<MatrixPlannedRow> plannedRows(Long projectId) {
        MaterialsListDto materials = materialsListService.getMaterials(projectId, false);
        List<MatrixPlannedRow> rows = new ArrayList<>();
        if (materials == null || materials.branches() == null) {
            return rows;
        }
        for (MaterialBranchGroupDto group : materials.branches()) {
            if (group == null || group.rows() == null) {
                continue;
            }
            for (MaterialRowDto row : group.rows()) {
                if (row == null || row.materialId() == null) {
                    continue;
                }
                rows.add(new MatrixPlannedRow(
                        row.materialId(),
                        row.materialName(),
                        group.branch(),
                        row.typeName(),
                        row.unit(),
                        row.effectiveTotalQty(),
                        plannedBrutto(row.rowTotalPrice())));
            }
        }
        return rows;
    }

    /**
     * The planned brutto of a row, or {@code null} when the chosen product has no net price
     * ({@link MoneyBrutto#EMPTY}) — so such a row renders {@code —} and is excluded from the money
     * totals and the % выборки denominator (R1.4, R3.3), never treated as zero-priced.
     */
    private static java.math.BigDecimal plannedBrutto(MoneyBrutto price) {
        return price == null ? null : price.brutto();
    }

    // --- Fact side (FOR-06-04 reconciled lines) -------------------------------------------------

    /**
     * The fact-side lines, read through the shipped FOR-06-04 reconciled-line read path
     * ({@link PurchaseLineReconciliationService#list}) with no additional filter, so every reconciled
     * line of the project's documents is returned (project-scoped, source-document fields resolved).
     * The assembler drops every non-matched line up front (R1.6), so the fact set is intentionally
     * not pre-filtered here — the read path is the single source of the reconciled lines.
     */
    private List<MatrixFactLine> factLines(Long projectId) {
        List<PurchaseLineServiceModel> lines =
                reconciliationService.list(projectId, null, null, null);
        List<MatrixFactLine> factLines = new ArrayList<>(lines.size());
        for (PurchaseLineServiceModel line : lines) {
            factLines.add(new MatrixFactLine(
                    line.lineId(),
                    line.matchedMaterialId(),
                    line.matchStatus(),
                    line.matchTier(),
                    line.quantity(),
                    line.unitHint(),
                    line.lineTotal(),
                    line.documentId(),
                    line.supplierName(),
                    toInstant(line.uploadedAt()),
                    line.rawName(),
                    line.manufacturerHint()));
        }
        return factLines;
    }

    /** Convert the reconciled line's upload time to an {@code Instant} (the matrix detail's type). */
    private static java.time.Instant toInstant(OffsetDateTime uploadedAt) {
        return uploadedAt == null ? null : uploadedAt.toInstant();
    }
}
