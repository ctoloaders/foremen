package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
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
 * {@code APPROVED} work report's worker tier, derives the unit {@code Worker_Service_Cost} and the
 * total {@code Cost_Value} through the FOR-05-06
 * {@link com.foremen.service.estimate.margins.MarginCostService} core reused verbatim (via
 * {@link WorkerCostDerivation}), and folds the per-worker and per-work-item aggregates — the cost side
 * the FOR-06-01 {@code WorkReport} deliberately left out, consumed by FOR-06-10's read model and the
 * payroll. There is exactly one cost formula shared with the estimate side (R1.2).
 *
 * <p>It is a pure derivation over existing read models — it <strong>reads</strong> the approved
 * reports ({@code clientUnitPrice} / {@code totalQuantity}, read as-is per R1.4), the active
 * {@code WorkerType} tiers, and the worker users, and changes none. It persists no cost column,
 * mutates no report / user / tier (R2.5), and emits <strong>no</strong> notifications (R7.1).
 *
 * <p><b>Project scope (R5.3).</b> Following the FOR-03-04a single-contract shape (mirrors
 * {@code WorkReportService} / {@code MarginMatrixService}), it implements exactly one CRUD contract —
 * {@link ProjectScopedService} <strong>over the work report</strong> — with the single mandatory
 * per-entity override {@link #getProjectIdPath()} &rarr; {@code "project.id"}, so any LIST read flows
 * through the inherited project filter and never returns another project's data; the bespoke
 * {@link #costForProject(Long)} read takes the {@code projectId} directly.
 *
 * <p><b>Worker-tier resolution (FOR-07-06 R2).</b> The tier follows the <strong>worker user</strong>,
 * not the project membership: the workers present in the reports are batched once (no N+1) via
 * {@link #workerProfilesById(Collection)} into {@code userId → WorkerProfile(workerType,
 * seniorityPct)}, and each tier is resolved through the pure {@link EffectiveTier} — an active type
 * costs at {@code Base_Cost × (1 + tierUplift + seniorityPct)}; a missing / inactive type falls back
 * to the base tier (+ seniority) with {@code baseTierFallback = true} (R2.1–R2.3). With
 * {@code seniorityPct = 0} the result equals FOR-06-10 for the same tier (R2.4). The FOR-06-06 margin
 * matrix keeps its own member-based resolution and is unaffected (R2.5).
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
    private final UserDao userDao;
    private final WorkerTypeDao workerTypeDao;

    public WorkerCostService(WorkReportDao workReportDao,
                             WorkReportServiceMapper serviceMapper,
                             ProjectAccessCache projectAccessCache,
                             AuditLogDao auditLogDao,
                             EntityManager entityManager,
                             UserDao userDao,
                             WorkerTypeDao workerTypeDao) {
        this.workReportDao = workReportDao;
        this.serviceMapper = serviceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.userDao = userDao;
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
     *   <li>batch the workers present in those reports once into {@code userId → WorkerProfile}
     *       (FOR-07-06 R2.1, avoid N+1);</li>
     *   <li>for each report resolve the tier via {@link EffectiveTier} + the cost math into a
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
        Map<Long, WorkerProfile> profilesById = workerProfilesById(workerIdsOf(reports));

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

            WorkerProfile profile = workerId == null ? null : profilesById.get(workerId);
            ResolvedTier resolved = WorkerProfile.resolve(profile, baseType);
            String tierName = resolved.baseTierFallback()
                    ? baseTierName
                    : activeTypeName(profile.workerType(), ru);
            BigDecimal seniorityPct = profile == null ? BigDecimal.ZERO : profile.seniorityPct();

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
                    seniorityPct,
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
     * Per-report convenience for consumers costing inside their own loop: derive a single report's
     * total {@code Cost_Value} from a pre-resolved base tier and a pre-built
     * {@code userId → WorkerProfile} map (see {@link #workerProfilesById(Collection)}), without
     * re-querying. Reuses the exact same resolution ({@link EffectiveTier}) + cost math as
     * {@link #costForProject(Long)} (single formula, R2.5). An unavailable (null/zero) rate or unknown
     * quantity yields {@code null} (never a fabricated zero, R2.6).
     *
     * @param report       the APPROVED work report to cost; {@code null} ⇒ {@code null}
     * @param baseTier     the resolved base {@link WorkerTypeTier}; {@code null} ⇒ the
     *                     {@code MarginCostService} default base share
     * @param baseType     the active base {@code WorkerType} (the {@code is_base = true} row) used for the
     *                     fallback; {@code null} when unseeded
     * @param profilesById a pre-built {@code userId → WorkerProfile} map; {@code null} or a missing
     *                     worker ⇒ base-tier fallback
     * @return the report's total worker cost, or {@code null} when unavailable
     */
    public BigDecimal costFor(WorkReportEntity report,
                              WorkerTypeTier baseTier,
                              WorkerTypeEntity baseType,
                              Map<Long, WorkerProfile> profilesById) {
        if (report == null) {
            return null;
        }
        UserEntity worker = report.getWorker();
        Long workerId = worker == null ? null : worker.getId();
        WorkerProfile profile = workerId == null || profilesById == null ? null : profilesById.get(workerId);
        ResolvedTier resolved = WorkerProfile.resolve(profile, baseType);
        return WorkerCostDerivation.costValue(
                report.getClientUnitPrice(), resolved.tier(), baseTier, report.getTotalQuantity());
    }

    /**
     * FOR-07-06 R2.1 — batch the given worker users into a {@code userId → WorkerProfile} map in one
     * query ({@link UserDao#findWorkerProfiles(Collection)}, JOIN FETCH {@code workerType}). The
     * profile carries the user's {@code workerType} <em>as stored</em> (possibly {@code null} or
     * inactive — {@link EffectiveTier} applies the base-tier fallback) and its {@code seniorityPct}
     * ({@code null} ⇒ 0). Null ids are ignored; an empty input yields an empty map without querying.
     * An id with no user row is simply absent (⇒ base-tier fallback for the caller).
     *
     * @param workerIds the worker user ids to resolve
     * @return the {@code userId → WorkerProfile} map; never {@code null}
     */
    @Transactional(readOnly = true)
    public Map<Long, WorkerProfile> workerProfilesById(Collection<Long> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : workerIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, WorkerProfile> byId = new HashMap<>();
        for (UserEntity user : userDao.findWorkerProfiles(ids)) {
            if (user == null || user.getId() == null) {
                continue;
            }
            byId.putIfAbsent(user.getId(),
                    new WorkerProfile(user.getId(), user.getWorkerType(), user.getSeniorityPct()));
        }
        return byId;
    }

    /** The distinct, non-null worker ids present in the reports (insertion-ordered). */
    private static Set<Long> workerIdsOf(List<WorkReportEntity> reports) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WorkReportEntity report : reports) {
            if (report != null && report.getWorker() != null) {
                Long id = report.getWorker().getId();
                if (Objects.nonNull(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
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
