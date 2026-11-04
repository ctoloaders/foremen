package com.foremen.service.bank.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * Pure balance-continuity check for a bank account (FOR-07-04, Requirement 7).
 *
 * <p>Compares the provider balance obtained by the current sync run with the balance obtained by
 * the previous successful run plus the POSTED transactions booked in between:
 * {@code expected = previousBalance + Σ amount}, {@code diff = actual − expected}. A gap is
 * reported iff {@code |diff| > 0.01} in the account currency.
 *
 * <p>"Booked in window" compares {@code bookingDate} with the calendar dates of the balance
 * timestamps: exclusive of the previous date, inclusive of the new date.
 *
 * <p>The check is skipped silently (empty result) when either balance or either timestamp is
 * missing (Requirement 7.4), and for CASH accounts.
 */
public final class BalanceContinuityChecker {

    /** Tolerance in the account currency; a difference strictly above it is a gap. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private BalanceContinuityChecker() {
    }

    /** A POSTED transaction as seen by the check: its booking date and signed amount. */
    public record PostedAmount(LocalDate bookingDate, BigDecimal amount) {
    }

    /** A detected gap: {@code expected = prev + Σ}, {@code actual = new}, {@code diff = actual − expected}. */
    public record Gap(BigDecimal expected, BigDecimal actual, BigDecimal difference) {
    }

    /**
     * Checks continuity for a bank account.
     *
     * @param previousBalance balance from the previous successful run (nullable → skip)
     * @param previousAt      timestamp of the previous balance (nullable → skip)
     * @param newBalance      balance from the current run (nullable → skip)
     * @param newAt           timestamp of the new balance (nullable → skip)
     * @param posted          POSTED transactions of the account; those outside the window are ignored
     * @return the gap, or empty when balances are continuous or the check is skipped
     */
    public static Optional<Gap> check(BigDecimal previousBalance, OffsetDateTime previousAt,
                                      BigDecimal newBalance, OffsetDateTime newAt,
                                      Collection<PostedAmount> posted) {
        if (previousBalance == null || newBalance == null || previousAt == null || newAt == null) {
            return Optional.empty();
        }
        LocalDate fromExclusive = previousAt.toLocalDate();
        LocalDate toInclusive = newAt.toLocalDate();

        BigDecimal sum = BigDecimal.ZERO;
        if (posted != null) {
            for (PostedAmount p : posted) {
                if (p == null || p.amount() == null || p.bookingDate() == null) {
                    continue;
                }
                LocalDate d = p.bookingDate();
                if (d.isAfter(fromExclusive) && !d.isAfter(toInclusive)) {
                    sum = sum.add(p.amount());
                }
            }
        }
        BigDecimal expected = previousBalance.add(sum);
        BigDecimal diff = newBalance.subtract(expected);
        if (diff.abs().compareTo(TOLERANCE) > 0) {
            return Optional.of(new Gap(expected, newBalance, diff));
        }
        return Optional.empty();
    }

    /**
     * Same as {@link #check(BigDecimal, OffsetDateTime, BigDecimal, OffsetDateTime, Collection)},
     * but never reports a gap for a CASH account (Requirement 7.4).
     */
    public static Optional<Gap> check(boolean cashAccount,
                                      BigDecimal previousBalance, OffsetDateTime previousAt,
                                      BigDecimal newBalance, OffsetDateTime newAt,
                                      Collection<PostedAmount> posted) {
        if (cashAccount) {
            return Optional.empty();
        }
        return check(previousBalance, previousAt, newBalance, newAt, posted);
    }
}
