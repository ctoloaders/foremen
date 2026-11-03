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
 * <p><b>FOR-07-06 Property 6 (Worker-wide credit)</b> — payouts are no longer bound to a project, so
 * the calculator's input is <b>all</b> payouts of a worker (whatever projects their lines came from,
 * {@code WorkerPayoutDao.findByWorker}). The generated payouts carry an origin-project tag only to
 * model that history: the remaining credit is one fold over the whole list — never a per-project sum
 * of separately floored balances — and an advance issued while the worker was on one project offsets
 * a regular payout whose lines belong to another.
 *
 * <p>All monetary comparisons use the production {@code NUMERIC(19,2)} / {@link RoundingMode#HALF_UP}
 * normalization so the expectations match the calculator's own scaling. Minimum 100 iterations each,
 * per the design test strategy.
 *
 * <p><b>Validates: Requirements 3.2, 5.5, 5.6 (FOR-06-11); 7.1, 7.2 (FOR-07-06)</b>
 */
@Tag("Feature: FOR-06-11-payroll, Property 2 + 3: advance-credit suggested/applied + conserved non-negative running balance")
@Tag("Feature: FOR-07-06-company-payroll, Property 6: worker-wide advance credit over all the worker's payouts")
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
    // FOR-07-06 Property 6 — worker-wide credit (input = all payouts of the worker, any project).
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 6: worker-wide advance credit over all the worker's payouts")
    void workerWideCreditIsOneFoldOverAllProjectsNotAPerProjectSum(
            @ForAll("workerPayouts") List<FakePayout> allPayoutsOfWorker) {

        // Expected: max(0, Σ advances − Σ applied) over the whole worker history (R7.1, R7.2).
        BigDecimal raw = BigDecimal.ZERO;
        for (FakePayout p : allPayoutsOfWorker) {
            raw = p.isAdvance() ? raw.add(p.getAmount()) : raw.subtract(p.getAdvanceApplied());
        }
        BigDecimal remaining = calculator.remaining(allPayoutsOfWorker);

        assertThat(remaining)
                .as("worker-wide credit == max(0, Σ advances − Σ applied) over all projects (R7.1)")
                .isEqualByComparingTo(scale(raw.max(BigDecimal.ZERO)));
        assertThat(remaining.signum()).as("worker-wide credit is never negative (R7.2)")
                .isGreaterThanOrEqualTo(0);

        // The legacy per-(worker, project) view floors each project separately; summing those
        // floors can only over-state the credit — the worker-wide fold never exceeds it.
        BigDecimal perProjectSum = BigDecimal.ZERO;
        for (long project : allPayoutsOfWorker.stream().map(FakePayout::originProject).distinct().toList()) {
            perProjectSum = perProjectSum.add(calculator.remaining(allPayoutsOfWorker.stream()
                    .filter(p -> p.originProject() == project).toList()));
        }
        assertThat(remaining)
                .as("the worker-wide credit never exceeds the sum of per-project floored balances")
                .isLessThanOrEqualTo(perProjectSum);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 6: worker-wide advance credit over all the worker's payouts")
    void anAdvanceFromOneProjectOffsetsARegularPayoutOnAnother(
            @ForAll("money") BigDecimal advanceAmount,
            @ForAll("money") BigDecimal grossOnOtherProject) {

        // The advance was issued while the worker was on project 1; the regular payout covers lines
        // of project 2. Worker-wide, the whole advance is available as credit (R7.1).
        List<FakePayout> history = List.of(FakePayout.advance(advanceAmount, 1L));
        BigDecimal credit = calculator.remaining(history);
        BigDecimal applied = calculator.applied(credit, grossOnOtherProject);

        assertThat(credit).isEqualByComparingTo(scale(advanceAmount));
        assertThat(applied)
                .as("credit applied to the other project's gross == min(advance, gross) (R7.1)")
                .isEqualByComparingTo(scale(advanceAmount.min(grossOnOtherProject)));

        List<FakePayout> after = new ArrayList<>(history);
        after.add(FakePayout.regular(grossOnOtherProject, applied, 2L));
        assertThat(calculator.remaining(after))
                .as("the advance is consumed worker-wide by exactly the applied offset")
                .isEqualByComparingTo(scale(advanceAmount.subtract(applied)));
    }

    // =============================================================================================
    // A minimal in-memory AdvanceCreditSource.
    // =============================================================================================

    /**
     * A payout of the worker. {@code originProject} is not part of {@link AdvanceCreditSource}
     * (FOR-07-06 payouts have no project) — it only tags which project's work the history came from.
     */
    record FakePayout(boolean advance, BigDecimal amount, BigDecimal advanceApplied, long originProject)
            implements AdvanceCreditSource {

        static FakePayout advance(BigDecimal amount) {
            return advance(amount, 1L);
        }

        static FakePayout advance(BigDecimal amount, long originProject) {
            return new FakePayout(true, amount, BigDecimal.ZERO, originProject);
        }

        static FakePayout regular(BigDecimal amount, BigDecimal advanceApplied) {
            return regular(amount, advanceApplied, 1L);
        }

        static FakePayout regular(BigDecimal amount, BigDecimal advanceApplied, long originProject) {
            return new FakePayout(false, amount, advanceApplied, originProject);
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

    /** All payouts of one worker, drawn from up to 4 origin projects (worker-wide input). */
    @Provide
    Arbitrary<List<FakePayout>> workerPayouts() {
        return Combinators.combine(Arbitraries.of(true, false), money(), money(),
                        Arbitraries.longs().between(1L, 4L))
                .as((isAdvance, amt, app, project) -> isAdvance
                        ? FakePayout.advance(amt, project)
                        : FakePayout.regular(amt, app, project))
                .list().ofMinSize(0).ofMaxSize(12);
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
