package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;

/**
 * FOR-06-06 (design §Data Models) — one contributing approved report in a {@link MarginRowDto}'s
 * per-row detail (R4.2), so the fact margin is auditable and makes clear that the row's fact cost
 * mixes worker-type tiers (R2.5, R4.4).
 *
 * <p>Each entry carries the report's own resolved worker tier — the {@code WorkerType} of the
 * report's worker resolved via {@code ProjectMember.workerType}, falling back to the base tier for an
 * Uncategorized / inactive / missing worker ({@link #baseTierFallback} {@code true}, R1.4, R2.5) — and
 * the derived tier cost {@code Tier_Cost(clientUnitPrice, tier) × reportedQuantity}. A null/zero
 * {@code clientUnitPrice} yields an unavailable ({@code null}) {@link #derivedTierCost}, excluded from
 * the row's fact-cost sum (R1.6); otherwise the row's fact cost equals the sum of the detail rows'
 * {@link #derivedTierCost} (R4.4).
 *
 * @param reportId         the approved work report id
 * @param workerId         the report's worker id
 * @param workerName       the worker's localized display name
 * @param workerTypeId     the resolved worker type id, or {@code null} for a base-tier fallback
 * @param workerTypeName   the resolved worker type's localized name (the base tier's name on fallback)
 * @param baseTierFallback whether the tier was resolved by falling back to the base tier (R2.5)
 * @param reportedQuantity the report's {@code totalQuantity}
 * @param clientUnitPrice  the report's frozen client unit price; {@code null}/zero ⇒ unavailable cost
 * @param revenueValue     the report's {@code revenueValue}
 * @param derivedTierCost  {@code Tier_Cost(clientUnitPrice, tier) × reportedQuantity}; {@code null}
 *                         when the price is unavailable (R1.6)
 */
public record MarginReportDetailDto(
        Long reportId,
        Long workerId,
        String workerName,
        Long workerTypeId,
        String workerTypeName,
        boolean baseTierFallback,
        BigDecimal reportedQuantity,
        BigDecimal clientUnitPrice,
        BigDecimal revenueValue,
        BigDecimal derivedTierCost) {
}
