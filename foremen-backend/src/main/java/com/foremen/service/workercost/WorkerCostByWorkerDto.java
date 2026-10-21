package com.foremen.service.workercost;

import java.math.BigDecimal;

/**
 * FOR-06-10 (design §Data Models) — the per-worker aggregate of the worker-cost read model: the
 * payroll-facing basis (FOR-06-11).
 *
 * <p>{@link #totalCost()} is the sum of {@code Cost_Value} over the worker's {@code APPROVED} reports
 * whose cost is available; reports with an unavailable cost (null/zero rate) are excluded from the sum
 * (R3.2, R3.4), so they never contribute a fabricated zero.
 *
 * @param workerId    the worker's user id
 * @param workerName  the worker's localized name
 * @param reportCount the number of the worker's {@code APPROVED} reports contributing to {@code totalCost}
 * @param totalCost   Σ {@code Cost_Value} over the worker's available-cost approved reports
 */
public record WorkerCostByWorkerDto(
        Long workerId,
        String workerName,
        int reportCount,
        BigDecimal totalCost) {
}
