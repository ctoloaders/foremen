package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-06 (design §Data Models) — a work-category group of the margin matrix (R4.1). Rows are
 * grouped by the work item's {@code WorkCategory} (localized {@code ru→pl→code}, in the estimate's
 * category order) with per-category plan/fact subtotals.
 *
 * <p>Each subtotal is the sum over the group's rows of the corresponding available figure
 * ({@link MarginRowDto#planRevenue()} etc.); unavailable ({@code null}) plan costs/margins are
 * excluded from the plan subtotals (R1.6, R3.5). Subtotal margin amounts are the arithmetic sum of the
 * rows' available margin amounts, so a subtotal equals the sum of its parts (R4.1).
 *
 * @param category         the work category's localized name
 * @param rows             the group's rows, in estimate order
 * @param planRevenue      {@code Σ row.planRevenue}
 * @param planCost         {@code Σ row.planCost} over rows with an available plan cost
 * @param planMarginAmount {@code Σ row.planMarginAmount} over rows with an available plan margin
 * @param factRevenue      {@code Σ row.factRevenue}
 * @param factCost         {@code Σ row.factCost}
 * @param factMarginAmount {@code Σ row.factMarginAmount}
 */
public record MarginCategoryGroupDto(
        String category,
        List<MarginRowDto> rows,
        BigDecimal planRevenue,
        BigDecimal planCost,
        BigDecimal planMarginAmount,
        BigDecimal factRevenue,
        BigDecimal factCost,
        BigDecimal factMarginAmount) {
}
