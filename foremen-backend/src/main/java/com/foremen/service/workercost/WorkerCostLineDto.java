package com.foremen.service.workercost;

import java.math.BigDecimal;

/**
 * FOR-06-10 (design §Data Models) — the per-report line of the worker-cost read model: the cost-side
 * companion to FOR-06-01's revenue-side {@code clientUnitPrice} / {@code revenueValue}.
 *
 * <p>One line per {@code APPROVED} {@code WorkReport} on a project. The figures are a pure derivation
 * over existing read models (no cost column is persisted on {@code WorkReport}): the resolved worker
 * type (via {@code ProjectMember.workerType} with a base-tier fallback), the project work-item rate
 * read as-is from {@code clientUnitPrice} (R1.4), the reported quantity, the unit
 * {@code Worker_Service_Cost}, and the total {@code Cost_Value = unitServiceCost × totalQuantity}.
 *
 * <p>An unavailable (null/zero) rate propagates to a {@code null} {@link #unitServiceCost()} and a
 * {@code null} {@link #costValue()} — never a fabricated zero (R1.5); consumers render that as a
 * localized {@code —}.
 *
 * @param reportId         the backing {@code WorkReport} id
 * @param workerId         the reported worker's user id
 * @param workerName       the worker's localized name ({@code ru → pl → code} fallback)
 * @param workItemId       the reported work item id
 * @param workItemName     the work item's localized name
 * @param workerTypeId     the resolved {@code WorkerType} id
 * @param workerTypeName   the resolved {@code WorkerType} localized name
 * @param baseTierFallback {@code true} when the base tier was used via fallback (Uncategorized /
 *                         missing / inactive), {@code false} for an explicit active membership tier (R2.4)
 * @param workItemRate     the project work-item rate ({@code = clientUnitPrice}, read as-is, R1.4)
 * @param totalQuantity    the FOR-06-01 derived reported quantity
 * @param unitServiceCost  the unit {@code Worker_Service_Cost}; {@code null} ⇒ unavailable (R1.5)
 * @param costValue        {@code unitServiceCost × totalQuantity}; {@code null} ⇒ unavailable (R1.3, R1.5)
 */
public record WorkerCostLineDto(
        Long reportId,
        Long workerId,
        String workerName,
        Long workItemId,
        String workItemName,
        Long workerTypeId,
        String workerTypeName,
        boolean baseTierFallback,
        BigDecimal workItemRate,
        BigDecimal totalQuantity,
        BigDecimal unitServiceCost,
        BigDecimal costValue) {
}
