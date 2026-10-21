package com.foremen.service.workercost;

import java.math.BigDecimal;

/**
 * FOR-06-10 (design §Data Models) — the per-work-item aggregate of the worker-cost read model: the
 * margin-fact-facing basis (FOR-06-06).
 *
 * <p>{@link #totalCost()} is the sum of {@code Cost_Value} over the work item's {@code APPROVED}
 * reports whose cost is available; reports with an unavailable cost (null/zero rate) are excluded from
 * the sum (R3.2, R3.4), so they never contribute a fabricated zero.
 *
 * @param workItemId   the work item id
 * @param workItemName the work item's localized name
 * @param reportCount  the number of the work item's {@code APPROVED} reports contributing to {@code totalCost}
 * @param totalCost    Σ {@code Cost_Value} over the work item's available-cost approved reports
 */
public record WorkerCostByWorkItemDto(
        Long workItemId,
        String workItemName,
        int reportCount,
        BigDecimal totalCost) {
}
