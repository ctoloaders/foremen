package com.foremen.service.payroll.dto;

import java.util.List;

/**
 * FOR-06-11 (Requirement 11.1) — the aggregate read model of a project's payroll: every
 * {@link WorkerPayoutDto} on the project (optionally narrowed by the {@code PayrollFilter}) plus,
 * per worker, their remaining advance credit as a {@link WorkerCreditDto}.
 *
 * <p>Returned by {@code WorkerPayoutService.list} and rendered by the Payroll tab: the
 * {@link #payouts} drive the payout table, the {@link #workers} drive the per-worker
 * remaining-advance-credit indicator (Requirement 5.8).
 *
 * @param projectId the owning project id
 * @param payouts   the project's payouts (filtered per the request), in a stable order
 * @param workers   per-worker remaining advance credit (R5.8, R11.1)
 */
public record PayrollView(
        Long projectId,
        List<WorkerPayoutDto> payouts,
        List<WorkerCreditDto> workers) {
}
