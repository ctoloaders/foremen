package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

/**
 * FOR-06-11 (Requirements 11.1, 5.8) — a worker's remaining advance credit on a project, shown in
 * the Payroll tab next to their payouts.
 *
 * <p>{@link #remainingAdvanceCredit} is the running balance {@code Σ(advance amounts) −
 * Σ(advanceApplied on regular payouts)}, never negative (design §Advance derivation + credit flow);
 * it is folded by the pure {@code AdvanceCreditCalculator} over the worker's payouts, not stored.
 * {@link #workerName} is the worker's localized name ({@code ru → pl → code} fallback, Requirement
 * 8.1).
 *
 * @param workerId               the worker's user id
 * @param workerName             the worker's localized name ({@code ru → pl → code} fallback)
 * @param remainingAdvanceCredit the worker's remaining advance credit, never negative (R5.8)
 */
public record WorkerCreditDto(
        Long workerId,
        String workerName,
        BigDecimal remainingAdvanceCredit) {
}
