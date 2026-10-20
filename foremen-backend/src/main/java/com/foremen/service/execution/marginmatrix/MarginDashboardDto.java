package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-06 (design §Data Models) — the project-level overall dashboard (R3.4, R3.5): the plan-side
 * overall totals, the fact labour totals, the linked-material fold, the separate indirect-cost sum
 * (with its by-category breakdown), and the overall revenue / cost / margin.
 *
 * <p>Folds (R3.4, R3.5):
 * <pre>
 *   planRevenue        = Σ row.planRevenue   over rows with an available plan revenue
 *   planCost           = Σ row.planCost      over rows with an available plan cost (R1.6)
 *   factLabourRevenue  = Σ row.factRevenue
 *   factLabourCost     = Σ row.factCost
 *   overallRevenue     = factLabourRevenue
 *   overallCost        = factLabourCost + linkedMaterialCost + indirectCost
 *   overallMargin      = labourMargin(overallRevenue, overallCost)
 * </pre>
 * {@link #linkedMaterialCost} and {@link #indirectCost} enter only the overall cost, never a per-row
 * row (R3.1, R3.3). {@link #indirectCost} equals {@code Σ indirectByCategory.amount} (R3.2).
 *
 * @param planRevenue        {@code Σ Plan_Revenue} over available rows
 * @param planCost           {@code Σ Plan_Cost} over available rows
 * @param planMarginAmount   {@code planRevenue − planCost}
 * @param planMarginPct      {@code planMarginAmount / planRevenue}; {@code null} when 0/unavailable base
 * @param factLabourRevenue  {@code Σ Fact_Revenue}
 * @param factLabourCost     {@code Σ Fact_Cost}
 * @param linkedMaterialCost the project-level linked-material cost (R3.1)
 * @param indirectCost       the project-level indirect cost (R3.2, R3.3)
 * @param indirectByCategory the indirect cost broken down by category (+ uncategorized bucket, R3.2)
 * @param overallRevenue     {@code factLabourRevenue}
 * @param overallCost        {@code factLabourCost + linkedMaterialCost + indirectCost}
 * @param overallMarginAmount {@code overallRevenue − overallCost}
 * @param overallMarginPct   {@code overallMarginAmount / overallRevenue}; {@code null} when 0 revenue
 */
public record MarginDashboardDto(
        BigDecimal planRevenue,
        BigDecimal planCost,
        BigDecimal planMarginAmount,
        BigDecimal planMarginPct,
        BigDecimal factLabourRevenue,
        BigDecimal factLabourCost,
        BigDecimal linkedMaterialCost,
        BigDecimal indirectCost,
        List<IndirectCategoryDto> indirectByCategory,
        BigDecimal overallRevenue,
        BigDecimal overallCost,
        BigDecimal overallMarginAmount,
        BigDecimal overallMarginPct) {
}
