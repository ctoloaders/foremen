package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure, persistence-free advance-credit running-balance arithmetic for payroll
 * (FOR-06-11, Requirements 3.2, 5.5, 5.6).
 *
 * <p>No repositories, no clock, no persistence — every result is a deterministic function of its
 * arguments, so the correctness properties (Property 2 "suggested + applied credit", Property 3
 * "conserved, non-negative running balance") are property-testable in isolation. The service layer
 * is a thin apply-layer over this collaborator.
 *
 * <p>A worker's <b>remaining advance credit</b> on a project is a running balance:
 * {@code Σ(advance payout amounts) − Σ(advanceApplied on regular payouts)}, floored at 0 — advances
 * <b>increase</b> it, regular payouts <b>decrease</b> it by the applied offset, and it never goes
 * negative (glossary; Requirement 5.6).
 *
 * <p>All monetary values are normalized to 2 decimals with {@link RoundingMode#HALF_UP}, matching
 * the {@code NUMERIC(19,2)} pricing / FOR-06-10 cost stack. The cost itself is never re-derived
 * here; this collaborator only folds the already-computed amounts.
 */
@Component
public final class AdvanceCreditCalculator {

    /** {@code NUMERIC(19,2)} scale, consistent with the pricing and FOR-06-10 cost stack. */
    private static final int SCALE = 2;

    /**
     * Compute a worker's remaining advance credit from the full set of that worker's payouts on a
     * project (Requirements 5.5, 5.6).
     *
     * <p>The balance is {@code Σ(amount of advances) − Σ(advanceApplied of regular payouts)},
     * floored at 0 so it is never negative. An advance contributes its {@link
     * AdvanceCreditSource#getAmount() amount}; a regular payout contributes
     * {@code −}{@link AdvanceCreditSource#getAdvanceApplied() advanceApplied}. {@code null} payout
     * amounts are treated as zero.
     *
     * @param payouts the worker's payouts on the project (any order; an empty or {@code null} list
     *     yields {@code 0.00}). {@code null} elements are ignored.
     * @return the remaining advance credit, scaled to 2 decimals, {@code >= 0}
     */
    public BigDecimal remaining(List<? extends AdvanceCreditSource> payouts) {
        BigDecimal balance = BigDecimal.ZERO;
        if (payouts != null) {
            for (AdvanceCreditSource payout : payouts) {
                if (payout == null) {
                    continue;
                }
                if (payout.isAdvance()) {
                    balance = balance.add(nz(payout.getAmount()));
                } else {
                    balance = balance.subtract(nz(payout.getAdvanceApplied()));
                }
            }
        }
        if (balance.signum() < 0) {
            balance = BigDecimal.ZERO;
        }
        return scale(balance);
    }

    /**
     * The advance credit actually applied against a regular payout's gross value
     * ({@code min(remainingCredit, gross)}) — the offset recorded as the payout's
     * {@code advanceApplied} and subtracted from the running balance (Requirement 5.6).
     *
     * @param remainingCredit the worker's current remaining advance credit ({@code >= 0}; {@code
     *     null} treated as zero)
     * @param gross the regular payout's gross value, {@code Σ Cost_Value} ({@code >= 0}; {@code
     *     null} treated as zero)
     * @return {@code min(remainingCredit, gross)}, scaled to 2 decimals, never negative
     */
    public BigDecimal applied(BigDecimal remainingCredit, BigDecimal gross) {
        BigDecimal credit = nonNegative(nz(remainingCredit));
        BigDecimal basis = nonNegative(nz(gross));
        return scale(credit.min(basis));
    }

    /**
     * The suggested cash amount for a regular (non-advance) payout: the gross value less the
     * remaining advance credit, floored at 0 ({@code max(0, gross − remainingCredit)})
     * (Requirement 3.2).
     *
     * @param gross the gross value, {@code Σ Cost_Value} ({@code >= 0}; {@code null} treated as
     *     zero)
     * @param remainingCredit the worker's remaining advance credit ({@code >= 0}; {@code null}
     *     treated as zero)
     * @return {@code max(0, gross − remainingCredit)}, scaled to 2 decimals, never negative
     */
    public BigDecimal suggested(BigDecimal gross, BigDecimal remainingCredit) {
        BigDecimal basis = nonNegative(nz(gross));
        BigDecimal credit = nonNegative(nz(remainingCredit));
        BigDecimal result = basis.subtract(credit);
        return scale(nonNegative(result));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
