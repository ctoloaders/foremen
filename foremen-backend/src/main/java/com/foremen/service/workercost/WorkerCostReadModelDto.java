package com.foremen.service.workercost;

import java.util.List;

/**
 * FOR-06-10 (design §Data Models) — the project-scoped worker-cost read model returned by the
 * {@code WorkerCostService}: the per-report cost lines plus the two aggregate projections.
 *
 * <p>FOR-06-06 (margin matrix) needs {@link #byWorkItem()} (and the per-line detail for its drawer);
 * FOR-06-11 (payroll) needs {@link #byWorker()} (and the per-line detail for payout statements). All
 * figures are derived over the project's {@code APPROVED} reports only (R3.1, R3.3); a report whose
 * cost is unavailable appears in {@link #lines()} marked unavailable but is excluded from both
 * aggregates (R3.4).
 *
 * @param projectId  the owning project id
 * @param lines      one entry per {@code APPROVED} report (R3.1)
 * @param byWorker   the payroll-facing per-worker aggregate
 * @param byWorkItem the margin-facing per-work-item aggregate
 */
public record WorkerCostReadModelDto(
        Long projectId,
        List<WorkerCostLineDto> lines,
        List<WorkerCostByWorkerDto> byWorker,
        List<WorkerCostByWorkItemDto> byWorkItem) {
}
