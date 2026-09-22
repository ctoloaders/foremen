package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * The THREE FOR-04-19 cost parts for one work item, collapsed to a package-less shape by FOR-05-04
 * (Requirement 7.1, 7.4).
 *
 * <p>Mirrors the internal {@code WorkCatalogAggregationResolver.WorkCostCell} but lives in the
 * controller-model layer so the read DTOs stay decoupled from the pricing internals (as
 * {@link MoneyRangeDto} does for {@code MaterialRangeResolver.MoneyRange}).
 *
 * @param labourPrice  the work item's single net price, or {@code null} when the work item is
 *                     unpriced
 * @param construction the construction-branch material money range (never {@code null};
 *                     {@code 0..0} when the branch has no consumption)
 * @param finishing    the finishing-branch material money range (never {@code null};
 *                     {@code 0..0} when the branch has no consumption)
 */
public record WorkCostCellDto(BigDecimal labourPrice,
                              MoneyRangeDto construction,
                              MoneyRangeDto finishing) {}
