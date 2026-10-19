package com.foremen.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Plans how a single confirmed incoming amount settles against a schedule's open tranches
 * (Requirement 5.1–5.5, 5.7).
 *
 * <p>Pure and persistence-free: no repositories, no clock. Given the open tranches already ordered
 * nearest-open-first by {@code seqNo} and the amount to apply, it returns an {@link AllocationPlan}
 * describing the per-tranche settlements and any final surplus — it writes nothing. The applying
 * layer ({@code ReconciliationService}) turns the plan into persisted {@code TrancheAllocation}
 * rows, bumps each tranche's {@code paidGross}, and adds {@code leftoverCredit} to the schedule's
 * {@code unallocatedCredit} (Requirements 5.4, 5.5, 5.7).
 *
 * <h2>Cascade</h2>
 * The amount settles against the first open tranche's {@link OpenTranche#remaining remaining}:
 * <ul>
 *   <li>{@code amount < remaining} — a partial settlement; the tranche stays open and the cascade
 *       ends (Requirement 5.2).</li>
 *   <li>{@code amount == remaining} — the tranche is settled exactly (Requirement 5.3).</li>
 *   <li>{@code amount > remaining} — the tranche is settled and the surplus carries forward to the
 *       next open tranche in {@code seqNo} order, repeating until the amount is exhausted or no open
 *       tranche remains (Requirement 5.4).</li>
 * </ul>
 * Any surplus left after the last open tranche is settled is held as {@code leftoverCredit} — no
 * phantom tranche is fabricated (Requirement 5.5).
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li><b>Conservation:</b> {@code Σ applied + leftoverCredit == amount}.</li>
 *   <li><b>No over-settlement:</b> every {@code applied} is {@code ≤} that tranche's
 *       {@code remaining}.</li>
 *   <li>Every emitted {@link Allocation} carries a strictly-positive {@code applied}.</li>
 * </ul>
 *
 * <p>Monetary values are normalized to 2 decimals with {@link RoundingMode#HALF_UP}, matching the
 * pricing stack and the {@link TrancheGenerator}.
 */
@Component
public class ReconciliationEngine {

    private static final int SCALE = 2;

    /**
     * Plan the settlement cascade (Requirements 5.1–5.5, 5.7).
     *
     * @param openBySeqNo the open (not-{@code PAID}) tranches, ordered nearest-open-first by
     *                    {@code seqNo}; a {@code null} list is treated as empty
     * @param amount      the confirmed amount to apply (non-null, non-negative); the fee is excluded
     *                    upstream and never reaches the planner (Requirement 7.4)
     * @return the plan: the per-tranche allocations in cascade order and any final surplus as
     *         {@code leftoverCredit}; conservation {@code Σ applied + leftoverCredit == amount}
     *         always holds
     * @throws IllegalArgumentException when {@code amount} is null or negative, or an open tranche's
     *                                  remaining amount is null or non-positive
     */
    public AllocationPlan plan(List<OpenTranche> openBySeqNo, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-null and non-negative");
        }

        BigDecimal remainingAmount = scale(amount);
        List<Allocation> allocations = new ArrayList<>();

        if (openBySeqNo != null) {
            for (OpenTranche tranche : openBySeqNo) {
                if (remainingAmount.signum() == 0) {
                    break;
                }
                BigDecimal trancheRemaining = trancheRemaining(tranche);

                // Apply the lesser of what's left of the amount and what the tranche still owes;
                // this guarantees applied ≤ remaining (no over-settlement) and applied > 0.
                BigDecimal applied = remainingAmount.min(trancheRemaining);
                allocations.add(new Allocation(tranche.seqNo(), applied));
                remainingAmount = remainingAmount.subtract(applied);
            }
        }

        // Whatever survives the cascade is the held credit — no phantom tranche (R5.5).
        return new AllocationPlan(allocations, remainingAmount);
    }

    private static BigDecimal trancheRemaining(OpenTranche tranche) {
        BigDecimal remaining = tranche == null ? null : tranche.remaining();
        if (remaining == null || remaining.signum() <= 0) {
            throw new IllegalArgumentException("open tranche remaining must be positive");
        }
        return scale(remaining);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
