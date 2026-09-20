package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * A single entry in {@link WorkItemDtoModel#packagePivot()} — the THREE FOR-04-19 prices for one
 * {@code (work item, offer package)}, keyed by {@code offerPackage.id} in the pivot map.
 *
 * <p>Mirrors the internal {@code WorkCatalogAggregationResolver.PackageCell} but lives in the
 * controller-model layer so the read DTOs stay decoupled from the pricing internals (as
 * {@link MoneyRangeDto} does for {@code MaterialRangeResolver.MoneyRange}).
 *
 * @param offerPackageId the numeric id of the offer package this cell is for (the pivot key; stable
 *                       across {@code code} renames, used by the frontend to build the per-package
 *                       pivot column)
 * @param labourPrice    the FOR-04-12b effective labour net price for this {@code (work, package)},
 *                       or {@code null} when the work item is unpriced in this package
 * @param construction   the construction-branch material money range (never {@code null};
 *                       {@code 0..0} when the branch has no consumption)
 * @param finishing      the finishing-branch material money range (never {@code null};
 *                       {@code 0..0} when the branch has no consumption)
 */
public record WorkCatalogPackageCellDto(Long offerPackageId,
                                        BigDecimal labourPrice,
                                        MoneyRangeDto construction,
                                        MoneyRangeDto finishing) {}
