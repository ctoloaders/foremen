package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.service.estimate.margins.Margin;
import com.foremen.service.estimate.margins.MarginCostService;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.ApprovedReport;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.Bundle;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.PlanLine;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.ProcurementLine;

/**
 * FOR-06-06 (design §B1) — the pure, stateless margin-matrix projection. Builds a
 * {@link MarginMatrixDto} from the plan lines, the APPROVED work reports (each already carrying its
 * resolved {@link WorkerTypeTier}), the reconciled procurement lines, and the base tier. It performs
 * no I/O beyond reading the passed-in {@link Bundle}, so it is directly jqwik-testable without a
 * Spring context.
 *
 * <p>Every labour cost + margin comes <strong>only</strong> from the FOR-05-06
 * {@link MarginCostService} core (R2.1): the plan side at the base tier
 * ({@code Plan_Cost = Tier_Cost(unitPrice, base) × quantity},
 * {@code Plan_Margin = labourMargin(valueNet, Plan_Cost)}, R1.2/R2.3) and the fact side over each work
 * item's approved reports, each costed at its own resolved tier
 * ({@code Fact_Cost = Σ Tier_Cost(clientUnitPrice, Worker_Tier(report)) × totalQuantity},
 * {@code Fact_Revenue = Σ revenueValue}, R1.3/R2.4). There is no second cost formula.
 *
 * <p>It then groups rows by work category (localized, estimate order) with per-category plan/fact
 * subtotals (R4.1); folds {@code Linked_Material_Cost} (linked lines only, R3.1), {@code Indirect_Cost}
 * by {@code UnplannedExpenseCategory} with an uncategorized bucket (R3.2), and the {@code Overall_*} +
 * {@code Plan_Overall_*} dashboard — the indirect/linked folds enter only the overall, never a per-row
 * row (R3.3, R3.4). Each report is carried into the per-row detail with its resolved tier and the
 * {@code baseTierFallback} flag so the row fact cost equals the detail sum (R2.5, R4.2, R4.4).
 * Unavailable (null/zero) prices propagate to an unavailable cost/margin and are excluded from the
 * aggregate denominators (R1.5, R1.6).
 */
@Component
public class MarginMatrixAssembler {

    /**
     * The default under-margin threshold (R4.3): a row is flagged under-margin when its fact margin
     * percentage is at least this many fractional points below its plan margin percentage (0.05 =
     * 5 percentage points).
     */
    public static final BigDecimal UNDER_MARGIN_THRESHOLD = new BigDecimal("0.05");

    /**
     * Assemble the margin-matrix read model from the bundle, using the default under-margin threshold.
     *
     * @param bundle the pure assembler inputs
     * @return the assembled {@link MarginMatrixDto}
     */
    public MarginMatrixDto assemble(Bundle bundle) {
        return assemble(bundle, UNDER_MARGIN_THRESHOLD);
    }

    /**
     * The pure projection (R1–R4). Package-visible {@code static} so property tests exercise it
     * directly; the instance {@link #assemble(Bundle)} delegates here.
     *
     * @param bundle             the pure assembler inputs ({@code null}-safe)
     * @param underMarginThreshold the under-margin threshold (R4.3)
     * @return the assembled {@link MarginMatrixDto}
     */
    static MarginMatrixDto project(Bundle bundle, BigDecimal underMarginThreshold) {
        Long projectId = bundle == null ? null : bundle.projectId();
        List<PlanLine> planLines = bundle == null || bundle.planLines() == null
                ? List.of() : bundle.planLines();
        List<ApprovedReport> reports = bundle == null || bundle.approvedReports() == null
                ? List.of() : bundle.approvedReports();
        List<ProcurementLine> procurement = bundle == null || bundle.procurementLines() == null
                ? List.of() : bundle.procurementLines();
        WorkerTypeTier baseTier = bundle == null ? null : bundle.baseTier();
        String uncategorizedLabel = bundle == null ? null : bundle.uncategorizedLabel();
        BigDecimal threshold = underMarginThreshold == null ? UNDER_MARGIN_THRESHOLD : underMarginThreshold;

        // Index the approved reports by work item id (preserving encounter order within a work item).
        Map<Long, List<ApprovedReport>> reportsByWorkItem = new LinkedHashMap<>();
        for (ApprovedReport report : reports) {
            if (report == null || report.workItemId() == null) {
                continue;
            }
            reportsByWorkItem.computeIfAbsent(report.workItemId(), id -> new ArrayList<>()).add(report);
        }

        // Build the rows (one per plan line) in estimate order, grouping by work category.
        List<PlanLine> sorted = new ArrayList<>(planLines);
        sorted.sort(Comparator
                .comparing(MarginMatrixAssembler::categoryOrderKey,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlanLine::workItemId,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Long, GroupAccumulator> groups = new LinkedHashMap<>();
        DashboardAccumulator dashboard = new DashboardAccumulator();
        for (PlanLine line : sorted) {
            if (line == null) {
                continue;
            }
            List<ApprovedReport> rowReports =
                    reportsByWorkItem.getOrDefault(line.workItemId(), List.of());
            MarginRowDto row = assembleRow(line, rowReports, baseTier, threshold);
            Long categoryKey = line.categoryId();
            GroupAccumulator group = groups.computeIfAbsent(
                    categoryKey, id -> new GroupAccumulator(line.categoryName()));
            group.add(row);
            dashboard.addRow(row);
        }

        List<MarginCategoryGroupDto> categoryDtos = new ArrayList<>(groups.size());
        for (GroupAccumulator group : groups.values()) {
            categoryDtos.add(group.toDto());
        }

        MarginDashboardDto dashboardDto = dashboard.toDto(procurement, uncategorizedLabel);
        return new MarginMatrixDto(projectId, categoryDtos, dashboardDto);
    }

    /**
     * Assemble the default-threshold matrix via {@link #project(Bundle, BigDecimal)} (the configurable
     * overload is exposed for callers/tests that override the under-margin threshold).
     *
     * @param bundle             the pure assembler inputs
     * @param underMarginThreshold the under-margin threshold (R4.3)
     * @return the assembled {@link MarginMatrixDto}
     */
    public MarginMatrixDto assemble(Bundle bundle, BigDecimal underMarginThreshold) {
        return project(bundle, underMarginThreshold);
    }

    // ---------------------------------------------------------------------------------------------
    // Per-row fold (R1.2, R1.3, R2.3, R2.4, R4.2, R4.3, R4.4)
    // ---------------------------------------------------------------------------------------------

    /** Build one work-item row: the plan side (base tier) and the fact side (per-report tier). */
    private static MarginRowDto assembleRow(
            PlanLine line,
            List<ApprovedReport> reports,
            WorkerTypeTier baseTier,
            BigDecimal underMarginThreshold) {

        // --- plan side (base tier), R1.2 / R2.3 -------------------------------------------------
        BigDecimal planRevenue = line.valueNet();
        BigDecimal planBaseCost = MarginCostService.baseCost(line.unitPrice(), baseTier);
        BigDecimal planTierCost = MarginCostService.tierCost(planBaseCost, baseTier);
        // Plan_Cost = Tier_Cost(unitPrice, base) × quantity; null when the price is unavailable (R1.6).
        BigDecimal planCost = (planTierCost == null) ? null : planTierCost.multiply(nz(line.quantity()));
        Margin planMargin = MarginCostService.labourMargin(planRevenue, planCost);

        // --- fact side (per-report resolved tier), R1.3 / R2.4 ----------------------------------
        List<MarginReportDetailDto> details = new ArrayList<>();
        BigDecimal factRevenue = BigDecimal.ZERO;
        BigDecimal factCost = BigDecimal.ZERO;
        BigDecimal factQuantity = BigDecimal.ZERO;
        for (ApprovedReport report : reports) {
            if (report == null) {
                continue;
            }
            BigDecimal reportBaseCost = MarginCostService.baseCost(report.clientUnitPrice(), baseTier);
            BigDecimal reportTierCost = MarginCostService.tierCost(reportBaseCost, report.tier());
            // derivedTierCost = Tier_Cost(clientUnitPrice, Worker_Tier) × totalQuantity; null ⇒ excluded (R1.6).
            BigDecimal derivedTierCost =
                    (reportTierCost == null) ? null : reportTierCost.multiply(nz(report.totalQuantity()));
            factRevenue = factRevenue.add(nz(report.revenueValue()));
            factQuantity = factQuantity.add(nz(report.totalQuantity()));
            if (derivedTierCost != null) {
                factCost = factCost.add(derivedTierCost); // the row fact cost == Σ detail cost (R4.4)
            }
            details.add(new MarginReportDetailDto(
                    report.reportId(),
                    report.workerId(),
                    report.workerName(),
                    report.workerTypeId(),
                    report.workerTypeName(),
                    report.baseTierFallback(),
                    report.totalQuantity(),
                    report.clientUnitPrice(),
                    report.revenueValue(),
                    derivedTierCost));
        }
        Margin factMargin = MarginCostService.labourMargin(factRevenue, factCost);

        boolean underMargin = underMargin(planMargin.pct(), factMargin.pct(), underMarginThreshold);

        return new MarginRowDto(
                line.workItemId(),
                line.workItemName(),
                line.categoryName(),
                line.unit(),
                line.quantity(),
                planRevenue,
                planCost,
                planMargin.amount(),
                planMargin.pct(),
                factQuantity,
                factRevenue,
                factCost,
                factMargin.amount(),
                factMargin.pct(),
                underMargin,
                details.size(),
                details);
    }

    /**
     * Under-margin flag (R4.3): {@code true} only when both percentages are available and the plan
     * percentage exceeds the fact percentage by at least the threshold; {@code false} whenever either
     * percentage is unavailable.
     */
    private static boolean underMargin(BigDecimal planPct, BigDecimal factPct, BigDecimal threshold) {
        if (planPct == null || factPct == null) {
            return false;
        }
        return planPct.subtract(factPct).compareTo(threshold) >= 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Per-category group fold (R4.1)
    // ---------------------------------------------------------------------------------------------

    /** Accumulates a work category's rows and its plan/fact subtotals (R4.1). */
    private static final class GroupAccumulator {
        private final String categoryName;
        private final List<MarginRowDto> rows = new ArrayList<>();
        private BigDecimal planRevenue = BigDecimal.ZERO;
        private BigDecimal planCost = BigDecimal.ZERO;
        private BigDecimal planMarginAmount = BigDecimal.ZERO;
        private BigDecimal factRevenue = BigDecimal.ZERO;
        private BigDecimal factCost = BigDecimal.ZERO;
        private BigDecimal factMarginAmount = BigDecimal.ZERO;

        private GroupAccumulator(String categoryName) {
            this.categoryName = categoryName;
        }

        private void add(MarginRowDto row) {
            rows.add(row);
            planRevenue = planRevenue.add(nz(row.planRevenue()));
            planCost = planCost.add(nz(row.planCost()));                   // null plan cost excluded (R1.6)
            planMarginAmount = planMarginAmount.add(nz(row.planMarginAmount()));
            factRevenue = factRevenue.add(nz(row.factRevenue()));
            factCost = factCost.add(nz(row.factCost()));
            factMarginAmount = factMarginAmount.add(nz(row.factMarginAmount()));
        }

        private MarginCategoryGroupDto toDto() {
            return new MarginCategoryGroupDto(
                    categoryName, rows,
                    planRevenue, planCost, planMarginAmount,
                    factRevenue, factCost, factMarginAmount);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Project-level dashboard fold (R3)
    // ---------------------------------------------------------------------------------------------

    /**
     * Accumulates the project-level plan/fact labour totals across rows, then folds the linked-material
     * and indirect procurement costs into the overall margin (R3.4, R3.5). A row with an unavailable
     * plan cost/revenue is excluded from the plan denominators (R1.6).
     */
    private static final class DashboardAccumulator {
        private BigDecimal planRevenue = BigDecimal.ZERO;
        private BigDecimal planCost = BigDecimal.ZERO;
        private BigDecimal factLabourRevenue = BigDecimal.ZERO;
        private BigDecimal factLabourCost = BigDecimal.ZERO;

        private void addRow(MarginRowDto row) {
            if (row.planRevenue() != null) {
                planRevenue = planRevenue.add(row.planRevenue());
            }
            if (row.planCost() != null) {
                planCost = planCost.add(row.planCost());
            }
            factLabourRevenue = factLabourRevenue.add(nz(row.factRevenue()));
            factLabourCost = factLabourCost.add(nz(row.factCost()));
        }

        private MarginDashboardDto toDto(List<ProcurementLine> procurement, String uncategorizedLabel) {
            BigDecimal linkedMaterialCost = BigDecimal.ZERO;
            // Indirect by category, preserving encounter order; a null category id → uncategorized bucket.
            Map<Long, IndirectBucket> indirectBuckets = new LinkedHashMap<>();
            for (ProcurementLine line : procurement) {
                if (line == null) {
                    continue;
                }
                BigDecimal amount = nz(line.lineTotal());
                if (line.linked()) {
                    linkedMaterialCost = linkedMaterialCost.add(amount); // R3.1 — overall only
                    continue;
                }
                // R3.2 — indirect bucket by UnplannedExpenseCategory (+ uncategorized).
                Long key = line.unplannedCategoryId();
                String name = line.unplannedCategoryId() == null ? uncategorizedLabel : line.unplannedCategoryName();
                IndirectBucket bucket = indirectBuckets.computeIfAbsent(
                        key, id -> new IndirectBucket(line.unplannedCategoryId(), name));
                bucket.amount = bucket.amount.add(amount);
            }

            List<IndirectCategoryDto> indirectByCategory = new ArrayList<>(indirectBuckets.size());
            BigDecimal indirectCost = BigDecimal.ZERO;
            for (IndirectBucket bucket : indirectBuckets.values()) {
                indirectByCategory.add(new IndirectCategoryDto(bucket.categoryId, bucket.categoryName, bucket.amount));
                indirectCost = indirectCost.add(bucket.amount);
            }

            Margin planMargin = MarginCostService.labourMargin(planRevenue, planCost);

            BigDecimal overallRevenue = factLabourRevenue;
            BigDecimal overallCost = factLabourCost.add(linkedMaterialCost).add(indirectCost);
            Margin overallMargin = MarginCostService.labourMargin(overallRevenue, overallCost);

            return new MarginDashboardDto(
                    planRevenue, planCost, planMargin.amount(), planMargin.pct(),
                    factLabourRevenue, factLabourCost,
                    linkedMaterialCost,
                    indirectCost, indirectByCategory,
                    overallRevenue, overallCost,
                    overallMargin.amount(), overallMargin.pct());
        }
    }

    /** A mutable indirect-cost bucket keyed by {@code UnplannedExpenseCategory} id (null = uncategorized). */
    private static final class IndirectBucket {
        private final Long categoryId;
        private final String categoryName;
        private BigDecimal amount = BigDecimal.ZERO;

        private IndirectBucket(Long categoryId, String categoryName) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** The category sort key preserving the estimate's category ordering ({@code null} last, R4.1). */
    private static Integer categoryOrderKey(PlanLine line) {
        return line == null ? null : line.categoryOrder();
    }

    /** Null-safe BigDecimal: {@code null} contributes zero to a sum (no fabricated value). */
    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
