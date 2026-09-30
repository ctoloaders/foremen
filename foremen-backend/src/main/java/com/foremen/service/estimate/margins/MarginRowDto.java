package com.foremen.service.estimate.margins;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-05-06 (design "Read-model DTOs") — one work row of the Margins matrix: the work's offer service
 * price against every cost tier, plus the per-branch material figures (R4.2, R4.3, R4.4). Mirrors the
 * frontend {@code MarginRow}.
 *
 * <p>Column order for a row (R4.3): {@code offerPrice} → one {@link TierCostDto} per worker type (in
 * dictionary order) → construction / finishing material figures → the labour min/avg/max profit across
 * tiers. Labour and material margins are kept separate (decision #4, R4.4): the min/avg/max fields
 * reflect the <strong>labour</strong> margin across tiers, while {@link #construction()} /
 * {@link #finishing()} carry the per-branch material margins.
 *
 * <p>{@code offerPrice == null} means the work has no offer price: {@code baseCost}, every tier cost,
 * and the profit fields are unavailable and the row is excluded from aggregate figures (R4.5).
 *
 * @param workItemId   the work item id
 * @param workName     the work item display name (localized at the read layer)
 * @param categoryId   the owning work category id (grouping key), or {@code null}
 * @param categoryName the owning work category display name, or {@code null}
 * @param offerPrice   the offer service price for the row (Σ labour), or {@code null} when unavailable
 * @param baseCost     the computed {@code Base_Cost}, or {@code null} when the offer is null/zero
 * @param tierCosts    one {@link TierCostDto} per worker type, in dictionary order (R4.3)
 * @param minProfit    the minimum labour margin across tiers (at the most expensive tier, R4.6)
 * @param avgProfit    the mean labour margin across tiers (R4.6)
 * @param maxProfit    the maximum labour margin across tiers (at the base tier, R4.6)
 * @param construction the per-branch construction material figures (retail / cost / margin, R4.4)
 * @param finishing    the per-branch finishing material figures (retail / cost / margin, R4.4)
 */
public record MarginRowDto(
        Long workItemId,
        String workName,
        Long categoryId,
        String categoryName,
        BigDecimal offerPrice,
        BigDecimal baseCost,
        List<TierCostDto> tierCosts,
        MoneyMargin minProfit,
        MoneyMargin avgProfit,
        MoneyMargin maxProfit,
        BranchMaterialDto construction,
        BranchMaterialDto finishing) {
}
