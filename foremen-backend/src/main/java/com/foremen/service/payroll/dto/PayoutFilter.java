package com.foremen.service.payroll.dto;

import java.time.LocalDate;

import com.foremen.dao.model.PayoutStatus;

/**
 * FOR-07-06 (design §WorkerPayoutService) — the optional narrowing of the company-wide payout list.
 * Every {@code null} field imposes no constraint; {@code from} / {@code to} bound the payout's
 * {@code payoutDate} (inclusive).
 *
 * @param workerId the worker whose payouts to list
 * @param from     inclusive lower bound on {@code payoutDate}
 * @param to       inclusive upper bound on {@code payoutDate}
 * @param status   {@code PLANNED} / {@code PAID}
 */
public record PayoutFilter(Long workerId, LocalDate from, LocalDate to, PayoutStatus status) {
}
