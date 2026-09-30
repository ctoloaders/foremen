package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design "Read-model DTOs") — one per-tier labour total of the {@link MarginsDashboardDto}:
 * across every priced work row, the total offer (revenue), the total service cost at this tier, and
 * the total labour margin (R5.2, R5.3). Rows with no offer price are excluded from these folds (no
 * fabricated value, R5.4). Mirrors the frontend dashboard per-tier entry.
 *
 * @param workerTypeId   the worker type (tier) id
 * @param workerTypeName the worker type display name (localized at the read layer)
 * @param base           whether this is the base tier
 * @param offerTotal     the Σ offer service price across priced rows (revenue for labour)
 * @param costTotal      the Σ {@code Tier_Cost} at this tier across priced rows
 * @param margin         the total labour margin at this tier ({@code offerTotal − costTotal})
 */
public record TierTotalDto(
        Long workerTypeId,
        String workerTypeName,
        boolean base,
        BigDecimal offerTotal,
        BigDecimal costTotal,
        MoneyMargin margin) {
}
