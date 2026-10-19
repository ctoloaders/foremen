package com.foremen.service.payment;

import java.math.BigDecimal;

/**
 * A single planned settlement produced by {@link ReconciliationEngine#plan}: how much of the
 * incoming amount is applied to one tranche, keyed by the tranche's {@code seqNo}.
 *
 * <p>Pure, persistence-free value. The applying layer ({@code ReconciliationService}) turns each
 * {@code Allocation} into a persisted {@code TrancheAllocation} row and bumps the tranche's
 * {@code paidGross} (Requirements 5.4, 5.7). {@code applied} is always strictly positive — the
 * planner never emits a zero-amount allocation.
 *
 * @param trancheSeqNo the target tranche's 1-based position (its ordering key) (Requirement 5.4)
 * @param applied      the amount applied to that tranche, strictly positive, never exceeding the
 *                     tranche's remaining amount (Requirements 5.2, 5.3, 5.4)
 */
public record Allocation(int trancheSeqNo, BigDecimal applied) {
}
