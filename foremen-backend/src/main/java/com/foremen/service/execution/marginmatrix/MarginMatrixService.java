package com.foremen.service.execution.marginmatrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProcurementLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.EstimateService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.ApprovedReport;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.Bundle;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.PlanLine;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.WorkReportServiceModel;
import com.foremen.service.model.mapper.WorkReportServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-06 (design §B2, R1, R2, R6, R8) — the margin-matrix read model service. It owns the single
 * read {@link #getMatrix(Long)} that joins the FOR-05 estimate (plan side) with the FOR-06-01 approved
 * work reports (labour fact), resolves each report worker's tier, loads the active {@code WorkerType}
 * tiers and the project's reconciled procurement lines, and delegates the whole plan × fact fold to the
 * pure {@link MarginMatrixAssembler}.
 *
 * <p>It is a pure read-only projection over existing read models — it <strong>reads</strong>, and
 * changes none:
 * <ul>
 *   <li><b>plan side</b> — the FOR-05 estimate lines, resolved through
 *       {@link EstimateService#getOrCreateEntityForProject(Long)} so a pre-estimate project returns an
 *       empty structure, never a {@code 404} (R1.1), mirroring {@code MarginsListService};</li>
 *   <li><b>labour-fact side</b> — the project's {@code APPROVED} work reports
 *       ({@link WorkReportDao#findApprovedByProjectWithWorkItemAndWorker(Long)}), each costed at its own
 *       worker's tier (R1.3, R2.4);</li>
 *   <li><b>material + indirect-fact side</b> — the project's reconciled procurement lines
 *       ({@link ProcurementLineDao#findByProjectIdWithUnplannedCategory(Long)}), folded into the
 *       linked-material cost and the separate indirect-by-category sum (R3).</li>
 * </ul>
 *
 * <p><b>Worker-tier resolution (R1.4, R2.5).</b> The project's {@code ProjectMember} rows are batched
 * once into a {@code userId → ProjectMember} map (no N+1); each report worker's tier is its member's
 * {@code workerType} when the member exists, is ACTIVE, and the type is ACTIVE — otherwise it falls
 * back to the base tier with the {@code baseTierFallback} flag set.
 *
 * <p><b>Project scope (R6.3).</b> Following the FOR-03-04a single-contract shape (mirrors
 * {@code WorkReportService} / {@code MaterialsMatrixService}), it implements exactly one CRUD
 * contract — {@link ProjectScopedService} <strong>over the work report</strong> — with the single
 * mandatory per-entity override {@link #getProjectIdPath()} &rarr; {@code "project.id"}, so any LIST
 * read flows through the inherited project filter and never returns another project's data. The
 * bespoke {@link #getMatrix(Long)} read takes the {@code projectId} directly and is guarded at the
 * controller (task 3.1) by {@code WORK_REPORTS}/READ.
 *
 * <p><b>Transaction.</b> Runs in a read-write transaction because the plan side's get-or-create may
 * INSERT the estimate row on a first read (mirroring {@code MarginsListService.getMargins}).
 *
 * <p><b>No notifications (R8.1).</b> The matrix is a read-only analytics surface — it changes no
 * domain state and emits no user-facing notifications.
 */
@Service
public class MarginMatrixService
        implements ProjectScopedService<WorkReportServiceModel, WorkReportServiceExtendedModel,
        WorkReportEntity, Long> {

    /** The localized name of the uncategorized indirect-cost bucket (R3.2), ru/pl by request locale. */
    private static final String UNCATEGORIZED_RU = "Без категории";
    private static final String UNCATEGORIZED_PL = "Bez kategorii";

    private final WorkReportDao workReportDao;
    private final WorkReportServiceMapper serviceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    private final EstimateService estimateService;
    private final EstimateLineDao estimateLineDao;
    private final ProjectMemberDao projectMemberDao;
    private final WorkerTypeDao workerTypeDao;
    private final ProcurementLineDao procurementLineDao;
    private final MarginMatrixAssembler assembler;

    public MarginMatrixService(WorkReportDao workReportDao,
                               WorkReportServiceMapper serviceMapper,
                               ProjectAccessCache projectAccessCache,
                               AuditLogDao auditLogDao,
                               EntityManager entityManager,
                               EstimateService estimateService,
                               EstimateLineDao estimateLineDao,
                               ProjectMemberDao projectMemberDao,
                               WorkerTypeDao workerTypeDao,
                               ProcurementLineDao procurementLineDao,
                               MarginMatrixAssembler assembler) {
        this.workReportDao = workReportDao;
        this.serviceMapper = serviceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.estimateService = estimateService;
        this.estimateLineDao = estimateLineDao;
        this.projectMemberDao = projectMemberDao;
        this.workerTypeDao = workerTypeDao;
        this.procurementLineDao = procurementLineDao;
        this.assembler = assembler;
    }

    // --- CRUD plumbing (inherited via ProjectScopedService) ------------------------------------

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

    // --- ProjectScopedService overrides --------------------------------------------------------

    /**
     * The single mandatory per-entity override (entity-creation-rules step 4, R6.3): a work report
     * resolves its owning project through {@code project}, so any LIST read is auto-filtered to the
     * caller's accessible projects — identical to {@code WorkReportService}.
     */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Margin-matrix read model (design §B2) -------------------------------------------------

    /**
     * Assemble the margin-matrix read model for {@code projectId} (design §B2, R1, R2, R3):
     * <ol>
     *   <li>resolve the FOR-05 estimate lines through the get-or-create path (empty for a pre-estimate
     *       project, never {@code 404}, R1.1) and map them to {@link PlanLine}s;</li>
     *   <li>load the project's {@code APPROVED} work reports and, batching the project's
     *       {@code ProjectMember} rows once, resolve each report worker's tier (ACTIVE member +
     *       ACTIVE type, else base-tier fallback, R1.4/R2.5) into {@link ApprovedReport}s;</li>
     *   <li>load the active {@code WorkerType} tiers to resolve the base tier (R2.2);</li>
     *   <li>load the project's reconciled procurement lines and map them to the assembler's
     *       {@code ProcurementLine} inputs (linked vs indirect, R3.1/R3.2);</li>
     *   <li>delegate the plan × fact fold to the pure {@link MarginMatrixAssembler}.</li>
     * </ol>
     * Emits no notifications (R8.1).
     *
     * @param projectId the owning project whose margin matrix to assemble
     * @return the assembled {@link MarginMatrixDto}; never {@code null}
     */
    @Transactional
    public MarginMatrixDto getMatrix(Long projectId) {
        boolean ru = isRussianLocale();

        // Ensure the estimate exists (pre-estimate project ⇒ empty structure, not 404, R1.1).
        EstimateEntity estimate = estimateService.getOrCreateEntityForProject(projectId);
        Long estimateId = estimate == null ? null : estimate.getId();

        WorkerTypeTier baseTier = resolveBaseTier();
        List<PlanLine> planLines = planLines(projectId, ru);
        List<ApprovedReport> approvedReports = approvedReports(projectId, baseTier, ru);
        List<MarginMatrixInputs.ProcurementLine> procurementLines = procurementLines(projectId, ru);

        Bundle bundle = new Bundle(
                projectId,
                planLines,
                approvedReports,
                procurementLines,
                baseTier,
                ru ? UNCATEGORIZED_RU : UNCATEGORIZED_PL);
        // estimateId is intentionally unused by the assembler; the get-or-create above is the only
        // reason this read is transactional (the first read may INSERT the estimate row).
        assert estimateId == null || estimateId > 0;
        return assembler.assemble(bundle);
    }

    // --- Plan side (FOR-05 estimate lines) -----------------------------------------------------

    /**
     * The plan-side rows: the project's estimate lines mapped to {@link PlanLine}s, each carrying the
     * localized work name / category (ru → pl → code), the category order key (the work category's
     * {@code orderNo}, preserving the estimate's category ordering, R4.1), the unit code, and the
     * frozen {@code unitPrice} / {@code quantity} / {@code valueNet} (R1.1, R1.2). A pre-estimate
     * project yields an empty list.
     */
    private List<PlanLine> planLines(Long projectId, boolean ru) {
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        List<PlanLine> rows = new ArrayList<>(lines.size());
        for (EstimateLineEntity line : lines) {
            if (line == null) {
                continue;
            }
            WorkItemEntity workItem = line.getWorkItem();
            WorkCategoryEntity category = workItem == null ? null : workItem.getWorkCategory();
            MeasurementUnitEntity unit = line.getUnit();
            rows.add(new PlanLine(
                    workItem == null ? null : workItem.getId(),
                    workItem == null ? null : workItemName(workItem, ru),
                    category == null ? null : category.getId(),
                    category == null ? null : workCategoryName(category, ru),
                    category == null ? null : category.getOrderNo(),
                    unit == null ? null : unit.getCode(),
                    line.getUnitPrice(),
                    line.getQuantity(),
                    line.getValueNet()));
        }
        return rows;
    }

    // --- Labour-fact side (FOR-06-01 approved reports) + worker-tier resolution ----------------

    /**
     * The labour-fact side: the project's {@code APPROVED} reports mapped to {@link ApprovedReport}s,
     * each carrying its resolved worker tier (R1.3, R1.4, R2.4, R2.5). The project's
     * {@code ProjectMember} rows are batched once into a {@code userId → member} map (no N+1); a
     * report worker's tier is its member's ACTIVE {@code workerType} when resolvable, else the base
     * tier with {@code baseTierFallback = true}.
     */
    private List<ApprovedReport> approvedReports(Long projectId, WorkerTypeTier baseTier, boolean ru) {
        List<WorkReportEntity> reports =
                workReportDao.findApprovedByProjectWithWorkItemAndWorker(projectId);
        if (reports.isEmpty()) {
            return List.of();
        }
        Map<Long, ProjectMemberEntity> membersByUserId = membersByUserId(projectId);

        List<ApprovedReport> rows = new ArrayList<>(reports.size());
        for (WorkReportEntity report : reports) {
            if (report == null) {
                continue;
            }
            UserEntity worker = report.getWorker();
            Long workerId = worker == null ? null : worker.getId();
            WorkItemEntity workItem = report.getWorkItem();

            ResolvedTier resolved = resolveWorkerTier(
                    workerId == null ? null : membersByUserId.get(workerId), baseTier, ru);

            rows.add(new ApprovedReport(
                    report.getId(),
                    workItem == null ? null : workItem.getId(),
                    workerId,
                    worker == null ? null : worker.getName(),
                    resolved.workerTypeId(),
                    resolved.workerTypeName(),
                    resolved.baseTierFallback(),
                    resolved.tier(),
                    report.getClientUnitPrice(),
                    report.getTotalQuantity(),
                    report.getRevenueValue()));
        }
        return rows;
    }

    /**
     * Batch the project's {@code ProjectMember} rows into a {@code userId → member} map so each
     * report's worker tier resolves without an N+1 (R2.5). A member with no {@code user} is skipped;
     * on the (schema-forbidden) duplicate {@code (user, project)} the first wins.
     */
    private Map<Long, ProjectMemberEntity> membersByUserId(Long projectId) {
        Map<Long, ProjectMemberEntity> byUserId = new HashMap<>();
        for (ProjectMemberEntity member : projectMemberDao.findByProjectId(projectId)) {
            if (member == null || member.getUser() == null) {
                continue;
            }
            byUserId.putIfAbsent(member.getUser().getId(), member);
        }
        return byUserId;
    }

    /**
     * Resolve a report worker's tier from its project membership (R1.4, R2.5): the member's
     * {@code workerType} drives the tier only when the member is ACTIVE and the type is ACTIVE —
     * otherwise (no membership, inactive membership, null type, inactive type) it falls back to the
     * base tier with {@code baseTierFallback = true} and a {@code null} worker-type id (the fallback
     * name is the base tier's localized name when available, else {@code null}).
     */
    private ResolvedTier resolveWorkerTier(ProjectMemberEntity member, WorkerTypeTier baseTier,
                                           boolean ru) {
        WorkerTypeEntity type = member == null ? null : member.getWorkerType();
        boolean memberActive = member != null && member.getAssignmentStatus() == AssignmentStatus.ACTIVE;
        if (type != null && type.isActive() && memberActive) {
            WorkerTypeTier tier = type.isBase()
                    ? WorkerTypeTier.base(type.getTierPct())
                    : WorkerTypeTier.uplift(type.getTierPct());
            return new ResolvedTier(type.getId(), workerTypeName(type, ru), false, tier);
        }
        // Base-tier fallback: Uncategorized / inactive / missing worker (R2.5).
        return new ResolvedTier(null, baseTierName(ru), true, baseTier);
    }

    // --- Material + indirect-fact side (FOR-06-03/04 reconciled lines) -------------------------

    /**
     * The material + indirect-fact side: the project's reconciled procurement lines mapped to the
     * assembler's {@code ProcurementLine} inputs (R3.1, R3.2). A line is <b>linked</b> when it carries
     * a non-null {@code matchedMaterialId} and a {@code matchStatus ∈ {AUTO, MANUAL_MATCHED}} — it
     * sums into {@code Linked_Material_Cost}; every other line sums into the indirect bucket by its
     * {@code unplannedCategory} (localized ru → pl → code; a null category → the uncategorized bucket).
     */
    private List<MarginMatrixInputs.ProcurementLine> procurementLines(Long projectId, boolean ru) {
        List<ProcurementLineEntity> lines =
                procurementLineDao.findByProjectIdWithUnplannedCategory(projectId);
        List<MarginMatrixInputs.ProcurementLine> rows = new ArrayList<>(lines.size());
        for (ProcurementLineEntity line : lines) {
            if (line == null) {
                continue;
            }
            boolean linked = line.getMatchedMaterialId() != null && isMatched(line.getMatchStatus());
            UnplannedExpenseCategoryEntity category = line.getUnplannedCategory();
            rows.add(new MarginMatrixInputs.ProcurementLine(
                    linked,
                    line.getLineTotal(),
                    category == null ? null : category.getId(),
                    category == null ? null : unplannedCategoryName(category, ru)));
        }
        return rows;
    }

    /** A matched (linked) line carries a material (R3.1): {@code AUTO} or {@code MANUAL_MATCHED}. */
    private static boolean isMatched(MatchStatus status) {
        return status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
    }

    // --- Worker-type tiers ---------------------------------------------------------------------

    /**
     * The base {@link WorkerTypeTier} from the active worker-type dictionary (R2.2): the active row
     * flagged {@code base}, carrying its {@code tierPct} (the offer share). {@code null} when no
     * active base tier is seeded — the assembler / {@code MarginCostService} then fall back to the
     * default base share, so the matrix stays well-formed.
     */
    private WorkerTypeTier resolveBaseTier() {
        WorkerTypeEntity base = findBaseType();
        return base == null ? null : WorkerTypeTier.base(base.getTierPct());
    }

    /** The active base worker-type row (the single {@code is_base = true} tier), or {@code null}. */
    private WorkerTypeEntity findBaseType() {
        for (WorkerTypeEntity type : workerTypeDao.findActiveOrdered()) {
            if (type != null && type.isBase()) {
                return type;
            }
        }
        return null;
    }

    /** The base tier's localized name for a fallback detail row (R2.5), or {@code null} when unseeded. */
    private String baseTierName(boolean ru) {
        WorkerTypeEntity base = findBaseType();
        return base == null ? null : workerTypeName(base, ru);
    }

    // --- Localized-name helpers (ru → pl → code) -----------------------------------------------

    private static String workItemName(WorkItemEntity workItem, boolean ru) {
        return firstNonBlank(
                ru ? workItem.getNameRU() : workItem.getNamePL(),
                ru ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    private static String workCategoryName(WorkCategoryEntity category, boolean ru) {
        return firstNonBlank(
                ru ? category.getNameRU() : category.getNamePL(),
                ru ? category.getNamePL() : category.getNameRU(),
                category.getCode());
    }

    private static String workerTypeName(WorkerTypeEntity type, boolean ru) {
        return firstNonBlank(
                ru ? type.getNameRU() : type.getNamePL(),
                ru ? type.getNamePL() : type.getNameRU(),
                type.getCode());
    }

    private static String unplannedCategoryName(UnplannedExpenseCategoryEntity category, boolean ru) {
        return firstNonBlank(
                ru ? category.getNameRU() : category.getNamePL(),
                ru ? category.getNamePL() : category.getNameRU(),
                category.getCode());
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

    /** The resolved worker tier for a report: its id/name, the base-tier-fallback flag, and the tier. */
    private record ResolvedTier(Long workerTypeId, String workerTypeName, boolean baseTierFallback,
                                WorkerTypeTier tier) {
    }
}
