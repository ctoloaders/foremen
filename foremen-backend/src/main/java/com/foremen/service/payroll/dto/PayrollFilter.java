package com.foremen.service.payroll.dto;

import com.foremen.dao.model.PayoutStatus;

/**
 * FOR-06-11 (Requirement 11.1) — the optional list filters for the Payroll tab: narrow the project's
 * payouts by worker and/or status. Both fields are optional; a {@code null} field imposes no
 * constraint (an all-{@code null} filter returns every payout on the project, subject to the
 * {@code ProjectScopedService} project scope).
 *
 * @param workerId narrow to a single worker's payouts, or {@code null} for all workers
 * @param status   narrow to {@code PLANNED} / {@code PAID}, or {@code null} for all statuses
 */
public record PayrollFilter(
        Long workerId,
        PayoutStatus status) {
}
