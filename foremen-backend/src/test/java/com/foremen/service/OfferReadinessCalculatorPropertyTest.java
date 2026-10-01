package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link OfferReadinessCalculator} — the pure function computing
 * {@code Offer_Readiness} from (a) unresolved negotiation rounds and (b) unfilled finishing
 * Placeholder slots (FOR-05-07, design §Property 23).
 *
 * <p>Readiness treats every round and every finishing Placeholder as one unit of work and returns
 * the share of resolved units as a percentage in {@code [0, 100]}. It equals {@code 100} exactly
 * iff {@code openRounds == 0 AND unfilledPlaceholders == 0}. The calculator is exercised directly as
 * a pure function — no persistence — so Property 23 is cheap to run over 100+ iterations.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 23: Offer readiness is a correct function of open
 * rounds and unfilled placeholders
 *
 * <p><b>Validates: Requirements 17.7</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 23: Offer readiness is a correct function of open rounds and unfilled placeholders")
class OfferReadinessCalculatorPropertyTest {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final OfferReadinessCalculator calculator = new OfferReadinessCalculator();

    /** A valid counts tuple where "open"/"unfilled" never exceed their totals. */
    private record Counts(int openRounds, int totalRounds, int unfilledPlaceholders, int totalPlaceholders) {
    }

    // ------------------------------------------------------------------------------------------
    // Property 23a: readiness is always within [0, 100], and readiness() agrees with the static
    // readinessPercent() core.
    // Validates: Requirement 17.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 23: Offer readiness is a correct function of open rounds and unfilled placeholders")
    void readinessIsAlwaysWithinBounds(@ForAll("validCounts") Counts c) {
        BigDecimal actual = calculator.readiness(
                c.openRounds(), c.totalRounds(), c.unfilledPlaceholders(), c.totalPlaceholders());

        assertThat(actual).isBetween(BigDecimal.ZERO, HUNDRED);
        assertThat(actual).isEqualByComparingTo(OfferReadinessCalculator.readinessPercent(
                c.openRounds(), c.totalRounds(), c.unfilledPlaceholders(), c.totalPlaceholders()));
    }

    // ------------------------------------------------------------------------------------------
    // Property 23b: readiness equals the exact pure-function oracle -- 100 * resolvedItems /
    // totalItems (or 100 when there are no items at all), rounded to 2dp HALF_UP.
    // Validates: Requirement 17.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 23: Offer readiness is a correct function of open rounds and unfilled placeholders")
    void readinessEqualsTheResolvedShareOracle(@ForAll("validCounts") Counts c) {
        BigDecimal actual = calculator.readiness(
                c.openRounds(), c.totalRounds(), c.unfilledPlaceholders(), c.totalPlaceholders());

        long totalItems = (long) c.totalRounds() + (long) c.totalPlaceholders();
        BigDecimal expected;
        if (totalItems == 0L) {
            expected = HUNDRED.setScale(2, RoundingMode.HALF_UP);
        } else {
            long resolvedItems = ((long) c.totalRounds() - c.openRounds())
                    + ((long) c.totalPlaceholders() - c.unfilledPlaceholders());
            expected = BigDecimal.valueOf(resolvedItems)
                    .divide(BigDecimal.valueOf(totalItems), MathContext.DECIMAL64)
                    .multiply(HUNDRED)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        assertThat(actual).isEqualByComparingTo(expected);
    }

    // ------------------------------------------------------------------------------------------
    // Property 23c: readiness is 100 exactly iff there are no open rounds AND no unfilled
    // placeholders. This is the core R17.7 equivalence in both directions.
    // Validates: Requirement 17.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 23: Offer readiness is a correct function of open rounds and unfilled placeholders")
    void readinessIsHundredIffNothingOutstanding(@ForAll("validCounts") Counts c) {
        BigDecimal actual = calculator.readiness(
                c.openRounds(), c.totalRounds(), c.unfilledPlaceholders(), c.totalPlaceholders());

        boolean nothingOutstanding = c.openRounds() == 0 && c.unfilledPlaceholders() == 0;
        boolean isHundred = actual.compareTo(HUNDRED) == 0;

        assertThat(isHundred)
                .as("readiness=%s is 100 iff open=0 && unfilled=0 (open=%d, unfilled=%d)",
                        actual, c.openRounds(), c.unfilledPlaceholders())
                .isEqualTo(nothingOutstanding);
    }

    // ------------------------------------------------------------------------------------------
    // Property 23d: monotonicity in resolved items -- resolving one more outstanding unit (closing a
    // round or filling a placeholder) never decreases readiness.
    // Validates: Requirement 17.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 23: Offer readiness is a correct function of open rounds and unfilled placeholders")
    void resolvingOneMoreItemNeverDecreasesReadiness(@ForAll("validCounts") Counts c) {
        BigDecimal base = calculator.readiness(
                c.openRounds(), c.totalRounds(), c.unfilledPlaceholders(), c.totalPlaceholders());

        if (c.openRounds() > 0) {
            BigDecimal afterClosingRound = calculator.readiness(
                    c.openRounds() - 1, c.totalRounds(), c.unfilledPlaceholders(), c.totalPlaceholders());
            assertThat(afterClosingRound)
                    .as("closing one open round should not decrease readiness")
                    .isGreaterThanOrEqualTo(base);
        }
        if (c.unfilledPlaceholders() > 0) {
            BigDecimal afterFillingPlaceholder = calculator.readiness(
                    c.openRounds(), c.totalRounds(), c.unfilledPlaceholders() - 1, c.totalPlaceholders());
            assertThat(afterFillingPlaceholder)
                    .as("filling one placeholder should not decrease readiness")
                    .isGreaterThanOrEqualTo(base);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Valid counts: totals in {@code [0, 50]} and, for each pair, the "open"/"unfilled" count in
     * {@code [0, total]} so the calculator's own {@code open <= total} precondition always holds.
     * Includes the all-zero (no rounds, no placeholders) and fully-resolved corners.
     */
    @Provide
    Arbitrary<Counts> validCounts() {
        Arbitrary<int[]> rounds = boundedPair();
        Arbitrary<int[]> placeholders = boundedPair();
        return Combinators.combine(rounds, placeholders)
                .as((r, p) -> new Counts(r[0], r[1], p[0], p[1]));
    }

    /** A {@code {open, total}} pair with {@code 0 <= open <= total <= 50}. */
    private Arbitrary<int[]> boundedPair() {
        return Arbitraries.integers().between(0, 50).flatMap(total ->
                Arbitraries.integers().between(0, total).map(open -> new int[] {open, total}));
    }
}
