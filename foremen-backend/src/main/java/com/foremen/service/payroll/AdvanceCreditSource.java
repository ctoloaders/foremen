package com.foremen.service.payroll;

import java.math.BigDecimal;

/**
 * Minimal, persistence-free view of a {@code WorkerPayout} that the pure
 * {@link AdvanceCreditCalculator} needs to fold the advance-credit running balance
 * (FOR-06-11, Requirements 5.5, 5.6).
 *
 * <p>FOR-06-11 task 2.1 (this collaborator) is scheduled before task 3.1 (the
 * {@code WorkerPayoutEntity} JPA entity). To keep the calculator pure and testable in
 * isolation — and to avoid coupling it to JPA — the calculator consumes this small
 * read-only interface rather than the entity directly. Task 3.1 makes
 * {@code WorkerPayoutEntity} implement this interface so
 * {@link AdvanceCreditCalculator#remaining} can be called with a
 * {@code List<WorkerPayoutEntity>} verbatim (the design.md signature).
 *
 * <p>Only the three fields the running balance depends on are exposed:
 * <ul>
 *   <li>{@link #isAdvance()} — whether the payout is an advance (it increased the credit) or a
 *       regular payout (it drew the credit down);</li>
 *   <li>{@link #getAmount()} — the payout's cash amount (an advance contributes this to the
 *       credit);</li>
 *   <li>{@link #getAdvanceApplied()} — the credit offset a regular payout consumed.</li>
 * </ul>
 */
public interface AdvanceCreditSource {

    /**
     * @return {@code true} when this payout is an advance (periodTo after creation date), so its
     *     {@link #getAmount()} increased the worker's remaining advance credit; {@code false} for a
     *     regular reports-based payout, whose {@link #getAdvanceApplied()} decreased it.
     */
    boolean isAdvance();

    /** @return the payout cash amount; {@code null} is treated as zero by the calculator. */
    BigDecimal getAmount();

    /**
     * @return the advance credit offset applied on a regular payout
     *     ({@code min(remainingCredit, gross)}); {@code 0} for an advance; {@code null} is treated
     *     as zero by the calculator.
     */
    BigDecimal getAdvanceApplied();
}
