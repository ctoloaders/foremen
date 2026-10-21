package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.WorkReportServiceModel;
import com.foremen.service.model.mapper.WorkReportServiceMapper;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-10 (design §B1, R1, R2, R3, R5, R7) — the single, reusable, project-scoped, read-only
 * <strong>worker service-cost</strong> derivation seam. Given a project, it resolves each
 * {@code APPROVED} work report's worker type (via {@code ProjectMember.workerType}, base-tier
 * fallback), derives the unit {@code Worker_Service_Cost} and the total {@code Cost_Value} through the
 * FOR-05-06 {@link com.foremen.service.estimate.margins.MarginCostService} core reused verbatim (via
 * {@link WorkerCostDerivation}), and folds the per-worker and per-work-item aggregates — the cost side
 * the FOR-06-01 {@code WorkReport} deliberately left out, consumed by FOR-06-06 (margin matrix) and
 * FOR-06-11 (payroll). There is exactly one cost formula shared with the estimate side (R1.2).
 *
 * <p>It is a pure derivation over existing read models — it <strong>reads</strong> the approved
 * reports ({@code clientUnitPrice} / {@code totalQuantity}, read as-is per R1.4), the active
 * {@code WorkerType} tiers, and the project memberships, and changes none. It persists no cost column,
 * mutates no report / membership / tier (R2.5), and emits <strong>no</strong> notifications (R7.1).
 *
 * <p><b>Project scope (R5.3).</b> Following the FOR-03-04a single-contract shape (mirrors
 * {@code WorkReportService} / {@code MarginMatrixService}), it implements exactly one CRUD contract —
 * {@link ProjectScopedService} <strong>over the work report</strong> — with the single mandatory
 * per-entity override {@link #getProjectIdPath()} &rarr; {@code "project.id"}, so any LIST read flows
 * through the inherited project filter and never returns another project's data; the bespoke
 * {@link #costForProject(Long)} read takes the {@code projectId} directly.
 *
 * <p><b>Worker-tier resolution (R2).</b> The project's {@code ProjectMember} rows are batched once
 * into a {@code userId → active workerType} map (no N+1): a member contributes its {@code workerType}
 * only when the member is ACTIVE and the type is ACTIVE — otherwise (no membership, inactive
 * membership, null type, inactive type) the worker is absent from the map and {@link WorkerCostDerivation#tierFor}
 * falls back to the base tier with {@code baseTierFallback = true} (R2.1, R2.2). This mirrors the
 * FOR-06-06 {@code MarginMatrixService} resolution exactly, so the refactor (task 4.1) is
 * penny-for-penny behaviour-preserving (R4.2).
 */
@Service
public class WorkerCostService
        implements ProjectScopedService<WorkReportServiceModel, WorkReportServiceExtendedModel,
        WorkReportEntity, Long> {

    private final WorkReportDao workReportDao;
    private final WorkReportServiceMapper serviceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    private final ProjectMemberDao projectMemberDao;
    private final WorkerTypeDao workerTypeDao;

    public WorkerCostService(WorkReportDao workReportDao,
                             WorkReportServiceMapper serviceMapper,
                             ProjectAccessCache projectAccessCache,
                             AuditLogDao auditLogDao,
                             EntityManager entityManager,
                             ProjectMemberDao projectMemberDao,
                             WorkerTypeDao workerTypeDao) {
        this.workReportDao = workReportDao;
        this.serviceMapper = serviceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectMemberDao = projectMemberDao;
        this.workerTypeDao = workerTypeDao;
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
     * The single mandatory per-entity override (entity-creation-rules step 4; R5.3): a work report
     * resolves its owning project through {@code project}, so any LIST read is auto-filtered to the
     * caller's accessible projects and never returns another project's data — identical to
     * {@code WorkReportService} / {@code MarginMatrixService}.
     */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Worker-cost read model (design §B1) ---------------------------------------------------

    /**
     * Assemble the project-scoped worker-cost read model for {@code projectId} (R1, R3):
     * <ol>
     *   <li>load the active {@code WorkerType} tiers to resolve the base tier (R2.3);</li>
     *   <li>load the project's {@code APPROVED} work reports (reusing
     *       {@link WorkReportDao#findApprovedByProjectWithWorkItemAndWorker(Long)}, R3.1/R3.3);</li>
     *   <li>batch the project's {@code ProjectMember} rows once into a {@code userId → active workerType}
     *       map (R2.1, avoid N+1);</li>
     *   <li>for each report compose {@link WorkerCostDerivation#tierFor} + the cost math into a
     *       {@link WorkerCostLineDto} ({@code workItemRate = clientUnitPrice} read as-is, R1.4);</li>
     *   <li>fold the per-worker and per-work-item aggregates over the approved reports, excluding any
     *       unavailable-cost line from every sum (R3.2, R3.4).</li>
     * </ol>
     * Read-only; emits no notifications (R7.1). A pre-report project (no approved reports) yields an
     * empty read model (empty lines + empty aggregates), never an error.
     *
     * @param projectId the owning project whose worker-cost read model to assemble
     * @return the assembled {@link WorkerCostReadModelDto}; never {@code null}
     */
    @Transactional(readOnly = true)
    public WorkerCostReadModelDto costForProject(Long projectId) {
        boolean ru = isRussianLocale();

        WorkerTypeEntity baseType = findBaseType();
        WorkerTypeTier baseTier = baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        String baseTierName = baseType == null ? null : workerTypeName(baseType, ru);

        List<WorkReportEntity> reports =
                workReportDao.findApprovedByProjectWithWorkItemAndWorker(projectId);
        if (reports.isEmpty()) {
            return new WorkerCostReadModelDto(projectId, List.of(), List.of(), List.of());
        }

        Map<Long, WorkerTypeEntity> activeTypeByWorkerId = activeTypeByWorkerId(projectId);

        List<WorkerCostLineDto> lines = new ArrayList<>(reports.size());
        WorkerAggregator byWorker = new WorkerAggregator();
        WorkItemAggregator byWorkItem = new WorkItemAggregator();

        for (WorkReportEntity report : reports) {
            if (report == null) {
                continue;
            }
            UserEntity worker = report.getWorker();
            Long workerId = worker == null ? null : worker.getId();
            String workerName = worker == null ? null : worker.getName();
            WorkItemEntity workItem = report.getWorkItem();
            Long workItemId = workItem == null ? null : workItem.getId();
            String workItemName = workItem == null ? null : workItemName(workItem, ru);

            ResolvedTier resolved =
                    WorkerCostDerivation.tierFor(workerId, activeTypeByWorkerId, baseType);
            String tierName = resolved.baseTierFallback()
                    ? baseTierName
                    : activeTypeName(activeTypeByWorkerId.get(workerId), ru);

            BigDecimal rate = report.getClientUnitPrice();   // read as-is (R1.4)
            BigDecimal quantity = report.getTotalQuantity();
            BigDecimal unitServiceCost =
                    WorkerCostDerivation.unitServiceCost(rate, resolved.tier(), baseTier);
            BigDecimal costValue =
                    WorkerCostDerivation.costValue(rate, resolved.tier(), baseTier, quantity);

            lines.add(new WorkerCostLineDto(
                    report.getId(),
                    workerId,
                    workerName,
                    workItemId,
                    workItemName,
                    resolved.workerTypeId(),
                    tierName,
                    resolved.baseTierFallback(),
                    rate,
                    quantity,
                    unitServiceCost,
                    costValue));

            // Fold the aggregates — a report whose cost is unavailable is excluded from every sum
            // (R3.4), so it never contributes a fabricated zero.
            if (costValue != null) {
                byWorker.add(workerId, workerName, costValue);
                byWorkItem.add(workItemId, workItemName, costValue);
            }
        }

        return new WorkerCostReadModelDto(projectId, lines, byWorker.toList(), byWorkItem.toList());
    }

    /**
     * Per-report convenience for FOR-06-06's assembler (task 4.1): derive a single report's total
     * {@code Cost_Value} from a pre-resolved base tier and the pre-built {@code userId → active
     * workerType} member map, so the caller derives it inside its existing loop without re-querying.
     * Reuses the exact same resolution + cost math as {@link #costForProject(Long)}, guaranteeing the
     * single cost-side formula (R4.1, R4.2). An unavailable (null/zero) rate or unknown quantity
     * yields {@code null} (never a fabricated zero, R1.5).
     *
     * @param report   the APPROVED work report to cost; {@code null} ⇒ {@code null}
     * @param baseTier the resolved base {@link WorkerTypeTier} (its share scales the rate); {@code null}
     *                 ⇒ the {@code MarginCostService} default base share
     * @param baseType the active base {@code WorkerType} (the {@code is_base = true} row) the tier
     *                 resolution uses for its fallback; {@code null} when unseeded
     * @param activeTypeByWorkerId a pre-built {@code userId → active workerType} map (an INACTIVE type
     *                 or inactive member MUST NOT be present); {@code null} ⇒ base-tier fallback
     * @return the report's total worker cost, or {@code null} when unavailable
     */
    public BigDecimal costFor(WorkReportEntity report,
                              WorkerTypeTier baseTier,
                              WorkerTypeEntity baseType,
                              Map<Long, WorkerTypeEntity> activeTypeByWorkerId) {
        if (report == null) {
            return null;
        }
        UserEntity worker = report.getWorker();
        Long workerId = worker == null ? null : worker.getId();
        ResolvedTier resolved = WorkerCostDerivation.tierFor(workerId, activeTypeByWorkerId, baseType);
        return WorkerCostDerivation.costValue(
                report.getClientUnitPrice(), resolved.tier(), baseTier, report.getTotalQuantity());
    }

    /**
     * Batch the project's {@code ProjectMember} rows into a {@code userId → active workerType} map so
     * each report's worker tier resolves without an N+1 (R2.1). A worker is present ONLY when the
     * member is ACTIVE and the referenced {@code workerType} is non-null and ACTIVE — so an inactive
     * member, a null (Uncategorized) type, or an inactive type leaves the worker absent, and
     * {@link WorkerCostDerivation#tierFor} then falls back to the base tier (R2.2), identical to the
     * FOR-06-06 {@code MarginMatrixService} resolution. On a (schema-forbidden) duplicate
     * {@code (user, project)} the first wins.
     */
    private Map<Long, WorkerTypeEntity> activeTypeByWorkerId(Long projectId) {
        Map<Long, WorkerTypeEntity> byUserId = new HashMap<>();
        for (ProjectMemberEntity member : projectMemberDao.findByProjectId(projectId)) {
            if (member == null || member.getUser() == null) {
                continue;
            }
            boolean memberActive = member.getAssignmentStatus() == AssignmentStatus.ACTIVE;
            WorkerTypeEntity type = member.getWorkerType();
            if (!memberActive || type == null || !type.isActive()) {
                continue; // inactive member / Uncategorized / inactive type ⇒ base-tier fallback (R2.2)
            }
            byUserId.putIfAbsent(member.getUser().getId(), type);
        }
        return byUserId;
    }

    /**
     * Publicly expose the batched {@code userId → active workerType} member map so FOR-06-06's
     * assembler (task 4.1) can build it once and pass it into {@link #costFor} per report in its
     * existing loop, avoiding a re-query. Same resolution contract as the private batch used by
     * {@link #costForProject(Long)}.
     *
     * @param projectId the owning project id
     * @return the {@code userId → active workerType} map for the project's ACTIVE members
     */
    public Map<Long, WorkerTypeEntity> activeWorkerTypesByWorkerId(Long projectId) {
        return activeTypeByWorkerId(projectId);
    }

    // --- Worker-type tiers ---------------------------------------------------------------------

    /** The active base worker-type row (the single {@code is_base = true} tier), or {@code null}. */
    public WorkerTypeEntity findBaseType() {
        for (WorkerTypeEntity type : workerTypeDao.findActiveOrdered()) {
            if (type != null && type.isBase()) {
                return type;
            }
        }
        return null;
    }

    // --- Aggregators ---------------------------------------------------------------------------

    /** Folds per-worker {@code Cost_Value} sums over available-cost approved reports (R3.2). */
    private static final class WorkerAggregator {
        private final Map<Long, Acc> byId = new LinkedHashMap<>();

        private void add(Long workerId, String workerName, BigDecimal cost) {
            byId.computeIfAbsent(workerId, id -> new Acc(workerName)).add(cost);
        }

        private List<WorkerCostByWorkerDto> toList() {
            List<WorkerCostByWorkerDto> out = new ArrayList<>(byId.size());
            for (Map.Entry<Long, Acc> e : byId.entrySet()) {
                Acc acc = e.getValue();
                out.add(new WorkerCostByWorkerDto(e.getKey(), acc.name, acc.count, acc.total));
            }
            return out;
        }
    }

    /** Folds per-work-item {@code Cost_Value} sums over available-cost approved reports (R3.2). */
    private static final class WorkItemAggregator {
        private final Map<Long, Acc> byId = new LinkedHashMap<>();

        private void add(Long workItemId, String workItemName, BigDecimal cost) {
            byId.computeIfAbsent(workItemId, id -> new Acc(workItemName)).add(cost);
        }

        private List<WorkerCostByWorkItemDto> toList() {
            List<WorkerCostByWorkItemDto> out = new ArrayList<>(byId.size());
            for (Map.Entry<Long, Acc> e : byId.entrySet()) {
                Acc acc = e.getValue();
                out.add(new WorkerCostByWorkItemDto(e.getKey(), acc.name, acc.count, acc.total));
            }
            return out;
        }
    }

    /** A mutable aggregate bucket: the display name, the contributing report count, and the running sum. */
    private static final class Acc {
        private final String name;
        private int count;
        private BigDecimal total = BigDecimal.ZERO;

        private Acc(String name) {
            this.name = name;
        }

        private void add(BigDecimal cost) {
            count++;
            total = total.add(cost);
        }
    }

    // --- Localized-name helpers (ru → pl → code) -----------------------------------------------

    /** The resolved active tier's localized name, or {@code null} when the worker is unmapped. */
    private static String activeTypeName(WorkerTypeEntity type, boolean ru) {
        return type == null ? null : workerTypeName(type, ru);
    }

    private static String workItemName(WorkItemEntity workItem, boolean ru) {
        return firstNonBlank(
                ru ? workItem.getNameRU() : workItem.getNamePL(),
                ru ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    private static String workerTypeName(WorkerTypeEntity type, boolean ru) {
        return firstNonBlank(
                ru ? type.getNameRU() : type.getNamePL(),
                ru ? type.getNamePL() : type.getNameRU(),
                type.getCode());
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
}
