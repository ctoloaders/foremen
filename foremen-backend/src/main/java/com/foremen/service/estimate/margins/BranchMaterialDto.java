package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design "Read-model DTOs") — the per-branch material figures for a {@link MarginRowDto} (a
 * single work row) or a {@link MarginsDashboardDto} (project-wide): the aggregated material retail
 * total, the aggregated material cost total, and the resulting material {@link MoneyMargin}. Computed
 * <strong>independently per branch</strong> (construction, finishing) and never merged into the labour
 * margin (decision #4, R4.4). Mirrors the frontend {@code BranchMaterial}.
 *
 * <p>Rows/materials with no price/cost contribute nothing (no fabricated value); the resulting margin
 * is {@link MoneyMargin#UNAVAILABLE} when there is nothing to compare (R4.5, R5.4).
 *
 * @param retailTotal the Σ material retail net for the branch ({@code Σ quantity × retailNet})
 * @param costTotal   the Σ material self-cost for the branch ({@code Σ quantity × cost_net})
 * @param margin      the material margin for the branch ({@code retailTotal − costTotal})
 */
public record BranchMaterialDto(
        BigDecimal retailTotal,
        BigDecimal costTotal,
        MoneyMargin margin) {

    /** The zero branch figure {@code 0/0} with a zero margin — the neutral value for a totals fold. */
    public static final BranchMaterialDto ZERO =
            new BranchMaterialDto(BigDecimal.ZERO, BigDecimal.ZERO,
                    new MoneyMargin(BigDecimal.ZERO, null));
}
