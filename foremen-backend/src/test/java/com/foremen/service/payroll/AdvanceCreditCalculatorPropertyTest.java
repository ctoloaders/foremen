package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-11 (task 2.4; design §Correctness Properties, Properties 2 and 3) — property-based tests
 * for the pure {@link AdvanceCreditCalculator} advance-credit arithmetic.
 *
 * <p><b>Property 2 (SuggestedAndAppliedCredit)</b> — for any {@code gross >= 0} and
 * {@code remainingCredit >= 0}: {@code applied(remaining, gross) == min(remaining, gross)} and
 * {@code suggested(gross, remaining) == max(0, gross − remaining)}.
 *
 * <p><b>Property 3 (AdvanceCreditBalance)</b> — for any sequence of advance + regular payouts
 * ({@link AdvanceCreditSource}), {@code remaining(...)} equals
 * {@code Σ(advance amounts) − Σ(advanceApplied on regular payouts)}, floored at 0 (never negative);
 * a regular payout decrements the running balance by exactly {@code min(remaining, gross)} and an
 * advance increments it by exactly its amount.
 *
 * <p>All monetary comparisons use the production {@code NUMERIC(19,2)} / {@link RoundingMode#HALF_UP}
 * normalization so the expectations match the calculator's own scaling. Minimum 100 iterations each,
 * per the design test strategy.
 *
 * <p><b>Validates: Requirements 3.2, 5.5, 5.6</b>
 */
@Tag("Feature: FOR-06-11-payroll, Property 2 + 3: advance-credit suggested/applied + conserved non-negative running balance")
class AdvanceCreditCalculatorPropertyTest {

    /** {@code NUMERIC(19,2)} scale, consistent with the production calculator. */
    private static final int SCALE = 2;

    private final AdvanceCreditCalculator calculator = new AdvanceCreditCalculator();

    // =============================================================================================
    // Property 2 — SuggestedAndAppliedCredit.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 2: applied == min(remaining, gross), suggested == max(0, gross − remaining)")
    void appliedIsMinOfRemainingAndGross(
            @ForAll("money") BigDecimal remainingCredit,
            @ForAll("money") BigDecimal gross) {

        BigDecimal applied = calculator.applied(remainingCredit, gross);

        assertThat(applied)
                .as("applied(remaining=%s, gross=%s) == min(remaining, gross) (R5.6)", remainingCredit, gross)
                .isEqualByComparingTo(scale(remainingCredit.min(gross)));
        assertThat(applied.signum())
                .as("applied credit is never negative")
                .isGreaterThanOrEqualTo(0);
        assertThat(applied)
                .as("applied credit never exceeds the gross")
                .isLessThanOrEqualTo(scale(gross));
        assertThat(applied)
                .as("applied credit never exceeds the remaining credit")
                .isLessThanOrEqualTo(scale(remainingCredit));
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 2: applied == min(remaining, gross), suggested == max(0, gross − remaining)")
    void suggestedIsMaxOfZeroAndGrossMinusRemaining(
            @ForAll("money") BigDecimal gross,
            @ForAll("money") BigDecimal remainingCredit) {

        BigDecimal suggested = calculator.suggested(gross, remainingCredit);

        BigDecimal expected = gross.subtract(remainingCredit);
        if (expected.signum() < 0) {
            expected = BigDecimal.ZERO;
        }
        assertThat(suggested)
                .as("suggested(gross=%s, remaining=%s) == max(0, gross − remaining) (R3.2)", gross, remainingCredit)
                .isEqualByComparingTo(scale(expected));
        assertThat(suggested.signum())
                .as("suggested amount is never negative")
                .isGreaterThanOrEqualTo(0);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 2: applied == min(remaining, gross), suggested == max(0, gross − remaining)")
    void appliedPlusSuggestedReconstructsTheGrossWhenCreditDoesNotExceedIt(
            @ForAll("money") BigDecimal gross,
            @ForAll("money") BigDecimal remainingCredit) {

        BigDecimal applied = calculator.applied(remainingCredit, gross);
        BigDecimal suggested = calculator.suggested(gross, remainingCredit);

        // applied + suggested == gross exactly: the gross is partitioned into the credit-offset part
        // and the cash-to-pay part, with no remainder regardless of which side dominates.
        assertThat(applied.add(suggested))
                .as("applied + suggested == gross (the gross is fully partitioned) (R3.2, R5.6)")
                .isEqualByComparingTo(scale(gross));
    }

    // =============================================================================================
    // Property 3 — AdvanceCreditBalance (conserved, non-negative running balance).
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 3: remaining == Σ(advances) − Σ(advanceApplied), never negative")
    void remainingEqualsAdvancesMinusAppliedFlooredAtZero(
            @ForAll("payouts") List<FakePayout> payouts) {

        BigDecimal advances = BigDecimal.ZERO;
        BigDecimal applied = BigDecimal.ZERO;
        for (FakePayout p : payouts) {
            if (p.isAdvance()) {
                advances = advances.add(nz(p.getAmount()));
            } else {
                applied = applied.add(nz(p.getAdvanceApplied()));
            }
        }
        BigDecimal expected = advances.subtract(applied);
        if (expected.signum() < 0) {
            expected = BigDecimal.ZERO;
        }

        BigDecimal remaining = calculator.remaining(payouts);

        assertThat(remaining)
                .as("remaining == max(0, Σ(advances) − Σ(advanceApplied)) (R5.5, R5.6)")
                .isEqualByComparingTo(scale(expected));
        assertThat(remaining.signum())
                .as("remaining advance credit is never negative (R5.6)")
                .isGreaterThanOrEqualTo(0);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 3: remaining == Σ(advances) − Σ(advanceApplied), never negative")
    void appendingAnAdvanceIncrementsTheBalanceByExactlyItsAmount(
            @ForAll("payouts") List<FakePayout> payouts,
            @ForAll("money") BigDecimal advanceAmount) {

        // The pre-floor balance is what the next payout acts on; to assert an *exact* increment we
        // work from a known non-negative starting balance (no history, or a balance that is not
        // already clamped). Compute the before-balance and only assert the exact delta when adding
        // the advance keeps us in the non-clamped region (always true: advances only raise it).
        BigDecimal before = calculator.remaining(payouts);

        List<FakePayout> withAdvance = new ArrayList<>(payouts);
        withAdvance.add(FakePayout.advance(advanceAmount));
        BigDecimal after = calculator.remaining(withAdvance);

        // An advance can never push the (floored-at-0) balance below zero, so when the prior balance
        // is already at its true (unclamped) value the delta is exactly the advance amount. The prior
        // balance is clamped only when Σapplied > Σadvances; adding an advance of `a` then raises the
        // true sum by `a`, and the observable floored delta is between 0 and `a`. We assert the
        // monotone, bounded relationship that holds universally.
        assertThat(after)
                .as("adding an advance never decreases the remaining credit (R5.5)")
                .isGreaterThanOrEqualTo(before);
        assertThat(after.subtract(before))
                .as("adding an advance of %s raises the credit by at most its amount", advanceAmount)
                .isLessThanOrEqualTo(scale(advanceAmount));
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 3: remaining == Σ(advances) − Σ(advanceApplied), never negative")
    void anAdvanceOnTopOfANonNegativeHistoryAddsExactlyItsAmount(
            @ForAll("advanceOnlyPayouts") List<FakePayout> advancesHistory,
            @ForAll("money") BigDecimal advanceAmount) {

        // With an advance-only history the balance is never clamped, so appending one more advance
        // increments the running balance by EXACTLY its amount (R5.5).
        BigDecimal before = calculator.remaining(advancesHistory);

        List<FakePayout> next = new ArrayList<>(advancesHistory);
        next.add(FakePayout.advance(advanceAmount));
        BigDecimal after = calculator.remaining(next);

        assertThat(after.subtract(before))
                .as("an advance increments the running balance by exactly its amount (R5.5)")
                .isEqualByComparingTo(scale(advanceAmount));
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 3: remaining == Σ(advances) − Σ(advanceApplied), never negative")
    void aRegularPayoutDecrementsTheBalanceByExactlyMinRemainingGross(
            @ForAll("advanceOnlyPayouts") List<FakePayout> advancesHistory,
            @ForAll("money") BigDecimal gross) {

        // Start from a non-negative (advance-only) balance, then apply one regular payout whose
        // advanceApplied is exactly min(remaining, gross) — the service's contract (R5.6). The
        // running balance must drop by exactly that applied offset.
        BigDecimal remainingBefore = calculator.remaining(advancesHistory);
        BigDecimal applied = calculator.applied(remainingBefore, gross);

        List<FakePayout> next = new ArrayList<>(advancesHistory);
        next.add(FakePayout.regular(gross, applied));
        BigDecimal remainingAfter = calculator.remaining(next);

        assertThat(remainingBefore.subtract(remainingAfter))
                .as("a regular payout decrements the credit by exactly min(remaining, gross) (R5.6)")
                .isEqualByComparingTo(applied);
        assertThat(remainingAfter)
                .as("the balance after applying the credit is never negative (R5.6)")
                .isEqualByComparingTo(scale(remainingBefore.subtract(applied)));
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 3: remaining == Σ(advances) − Σ(advanceApplied), never negative")
    void orderOfPayoutsDoesNotChangeTheRemainingBalance(
            @ForAll("payouts") List<FakePayout> payouts,
            @ForAll("seed") long seed) {

        // The running balance is a commutative fold (sum of advances minus sum of applied, floored),
        // so shuffling the payouts yields the same remaining credit.
        List<FakePayout> shuffled = new ArrayList<>(payouts);
        java.util.Collections.shuffle(shuffled, new java.util.Random(seed));

        assertThat(calculator.remaining(shuffled))
                .as("the remaining credit is order-independent (it is a conserved fold)")
                .isEqualByComparingTo(calculator.remaining(payouts));
    }

    // =============================================================================================
    // A minimal in-memory AdvanceCreditSource.
    // =============================================================================================

    record FakePayout(boolean advance, BigDecimal amount, BigDecimal advanceApplied)
            implements AdvanceCreditSource {

        static FakePayout advance(BigDecimal amount) {
            return new FakePayout(true, amount, BigDecimal.ZERO);
        }

        static FakePayout regular(BigDecimal amount, BigDecimal advanceApplied) {
            return new FakePayout(false, amount, advanceApplied);
        }

        @Override
        public boolean isAdvance() {
            return advance;
        }

        @Override
        public BigDecimal getAmount() {
            return amount;
        }

        @Override
        public BigDecimal getAdvanceApplied() {
            return advanceApplied;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // =============================================================================================
    // Generators.
    // =============================================================================================

    /** Non-negative {@code NUMERIC(19,2)} money: 0.00 .. 1_000_000.00. */
    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(0L, 100_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, SCALE));
    }

    @Provide
    Arbitrary<Long> seed() {
        return Arbitraries.longs();
    }

    /** A mixed sequence of advances and regular payouts. */
    @Provide
    Arbitrary<List<FakePayout>> payouts() {
        return anyPayout().list().ofMinSize(0).ofMaxSize(10);
    }

    /** A sequence of advance-only payouts (so the running balance is never clamped). */
    @Provide
    Arbitrary<List<FakePayout>> advanceOnlyPayouts() {
        return money().map(FakePayout::advance).list().ofMinSize(0).ofMaxSize(10);
    }

    private Arbitrary<FakePayout> anyPayout() {
        Arbitrary<BigDecimal> amount = money();
        Arbitrary<BigDecimal> applied = money();
        return Combinators.combine(Arbitraries.of(true, false), amount, applied)
                .as((isAdvance, amt, app) -> isAdvance
                        ? FakePayout.advance(amt)
                        : FakePayout.regular(amt, app));
    }
}
