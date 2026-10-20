package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-06 (design §Data Models) — one work-item row of the margin matrix: the plan side folded from
 * the estimate line at the base tier and the fact side folded over the work item's approved reports,
 * each report costed at its own resolved worker tier (R1.2, R1.3, R2.3, R2.4).
 *
 * <p>Plan side (R1.2, R2.3): {@code planRevenue = EstimateLine.valueNet},
 * {@code planCost = Tier_Cost(unitPrice, base) × plannedQuantity} (via {@code MarginCostService}),
 * {@code planMargin = labourMargin(planRevenue, planCost)}. A null/zero {@code unitPrice} yields an
 * unavailable ({@code null}) {@link #planCost}/{@link #planMarginAmount}/{@link #planMarginPct} —
 * never a fabricated zero — and the row is excluded from the plan aggregate denominators (R1.6, R3.5).
 *
 * <p>Fact side (R1.3, R2.4): {@code factRevenue = Σ revenueValue},
 * {@code factCost = Σ Tier_Cost(clientUnitPrice, Worker_Tier(report)) × totalQuantity} over the work
 * item's {@code APPROVED} reports, {@code factMargin = labourMargin(factRevenue, factCost)}. A
 * zero-report row has {@code factRevenue = factCost = 0} and an unavailable
 * {@link #factMarginPct} (rendered {@code —}, R1.5). The row's {@link #factCost} equals the sum of the
 * {@link #reports}' {@code derivedTierCost} (R4.4).
 *
 * <p>{@link #underMargin} is {@code true} only when both margin percentages are available and the fact
 * percentage is materially below the plan percentage (default threshold, R4.3); it is {@code false}
 * whenever either percentage is unavailable.
 *
 * @param workItemId       the work item id
 * @param workItemName     the work item's localized name
 * @param category         the work category's localized name ({@code ru→pl→code})
 * @param unit             the work item's unit code, or {@code null}
 * @param plannedQuantity  the estimate line's planned quantity
 * @param planRevenue      {@code EstimateLine.valueNet}
 * @param planCost         the base-tier planned labour cost; {@code null} when the price is unavailable
 * @param planMarginAmount {@code planRevenue − planCost}; {@code null} when the plan cost is unavailable
 * @param planMarginPct    {@code planMarginAmount / planRevenue}; {@code null} when unavailable/zero base
 * @param factQuantity     {@code Σ report.totalQuantity} over the approved reports
 * @param factRevenue      {@code Σ report.revenueValue}
 * @param factCost         {@code Σ derivedTierCost} over the approved reports (R4.4)
 * @param factMarginAmount {@code factRevenue − factCost}
 * @param factMarginPct    {@code factMarginAmount / factRevenue}; {@code null} when the revenue is 0 (R1.5)
 * @param underMargin      whether the fact margin % is materially below the plan margin % (R4.3)
 * @param reportCount      the number of contributing approved reports
 * @param reports          the contributing approved reports' detail (R4.2)
 */
public record MarginRowDto(
        Long workItemId,
        String workItemName,
        String category,
        String unit,
        BigDecimal plannedQuantity,
        BigDecimal planRevenue,
        BigDecimal planCost,
        BigDecimal planMarginAmount,
        BigDecimal planMarginPct,
        BigDecimal factQuantity,
        BigDecimal factRevenue,
        BigDecimal factCost,
        BigDecimal factMarginAmount,
        BigDecimal factMarginPct,
        boolean underMargin,
        int reportCount,
        List<MarginReportDetailDto> reports) {
}
