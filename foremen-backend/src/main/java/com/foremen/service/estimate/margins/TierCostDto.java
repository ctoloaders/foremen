package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design "Read-model DTOs") — one worker-type tier cost cell of a {@link MarginRowDto}: the
 * tier's identity, its computed service {@code Tier_Cost} for the row, and the labour margin at that
 * tier ({@code offer − tierCost}, kept separate from any material margin, R4.4). One
 * {@link TierCostDto} is emitted per {@link WorkerTypeRefDto}, in dictionary order (R4.3). Mirrors the
 * frontend {@code TierCost}.
 *
 * <p>{@code cost == null} means the tier cost is unavailable (a null/zero offer propagates, Property 8)
 * and the {@code labourMargin} is then {@link MoneyMargin#UNAVAILABLE}.
 *
 * @param workerTypeId   the worker type (tier) id
 * @param workerTypeName the worker type display name (localized at the read layer)
 * @param base           whether this is the base tier (its {@code Tier_Cost == Base_Cost})
 * @param cost           the computed {@code Tier_Cost} for the row, or {@code null} when unavailable
 * @param labourMargin   the labour margin at this tier ({@code offer − cost}), or unavailable
 */
public record TierCostDto(
        Long workerTypeId,
        String workerTypeName,
        boolean base,
        BigDecimal cost,
        MoneyMargin labourMargin) {
}
