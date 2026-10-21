package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.time.api.Dates;

/**
 * Property tests for {@link AdvanceDeriver} (FOR-06-11-payroll).
 *
 * <p>{@link AdvanceDeriver#isAdvance(LocalDate, LocalDate)} is the sole, pure, persistence-free
 * classifier of whether a worker payout is an <em>advance</em>: a payout is an advance exactly when
 * its {@code periodTo} falls strictly after the payout's creation date (Requirement 5.3). The rule
 * is derived from the dates alone — never a flag the user sets — so it is property-testable in
 * isolation with no persistence or injected clock.
 *
 * <p><b>Property 5 — IsAdvanceDerivation</b>: for any {@code (periodTo, createdDate)},
 * {@code isAdvance} is {@code true} iff {@code periodTo} is strictly after {@code createdDate}.
 *
 * <p>Each property runs with ≥ 100 iterations (jqwik {@code tries}).
 *
 * <p><b>Validates: Requirements 5.3</b>
 */
@Tag("Feature: FOR-06-11-payroll, Property 5: isAdvance is exactly periodTo-after-creation")
class AdvanceDeriverPropertyTest {

    private static final int ITERATIONS = 200;

    private final AdvanceDeriver deriver = new AdvanceDeriver();

    // ==========================================================================================
    // Property 5: IsAdvanceDerivation — isAdvance ⟺ periodTo strictly after createdDate.
    // ==========================================================================================

    /**
     * Over any correlated pair of dates, {@code isAdvance} equals the biconditional
     * {@code periodTo.isAfter(createdDate)} — strictly after, so an equal pair is NOT an advance.
     * The day offset straddles the boundary so strictly-before, exactly-on, and strictly-after are
     * all hit.
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-11-payroll, Property 5: isAdvance is exactly periodTo-after-creation")
    void isAdvanceIffPeriodToStrictlyAfterCreatedDate(@ForAll("datePairs") DatePair pair) {
        boolean actual = deriver.isAdvance(pair.periodTo, pair.createdDate);

        assertThat(actual)
                .as("isAdvance(periodTo=%s, createdDate=%s)", pair.periodTo, pair.createdDate)
                .isEqualTo(pair.periodTo.isAfter(pair.createdDate));
    }

    /**
     * When {@code periodTo} is strictly after {@code createdDate} (a strictly positive forward
     * offset), the payout is always an advance — the forward-looking branch.
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-11-payroll, Property 5: isAdvance is exactly periodTo-after-creation")
    void strictlyAfterIsAlwaysAdvance(@ForAll("anyDate") LocalDate createdDate,
                                      @ForAll("positiveOffsets") long forwardDays) {
        LocalDate periodTo = createdDate.plusDays(forwardDays);

        assertThat(deriver.isAdvance(periodTo, createdDate))
                .as("periodTo=%s is %d day(s) after createdDate=%s", periodTo, forwardDays, createdDate)
                .isTrue();
    }

    /**
     * When {@code periodTo} is on or before {@code createdDate} (a non-positive offset), the payout
     * is never an advance — a regular reports-based payout. The equal case (offset 0) is the
     * strict-boundary guard.
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-11-payroll, Property 5: isAdvance is exactly periodTo-after-creation")
    void onOrBeforeIsNeverAdvance(@ForAll("anyDate") LocalDate createdDate,
                                  @ForAll("nonNegativeOffsets") long backwardDays) {
        LocalDate periodTo = createdDate.minusDays(backwardDays);

        assertThat(deriver.isAdvance(periodTo, createdDate))
                .as("periodTo=%s is %d day(s) on/before createdDate=%s", periodTo, backwardDays, createdDate)
                .isFalse();
    }

    /**
     * Same date both sides is never an advance — the derivation is <em>strictly</em> after, so the
     * equal boundary classifies as a regular payout (determinism across repeated calls too).
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-11-payroll, Property 5: isAdvance is exactly periodTo-after-creation")
    void equalDatesAreNeverAdvanceAndDeterministic(@ForAll("anyDate") LocalDate date) {
        boolean first = deriver.isAdvance(date, date);
        boolean second = deriver.isAdvance(date, date);

        assertThat(first).as("equal dates (%s) are not an advance", date).isFalse();
        assertThat(second).as("derivation is deterministic").isEqualTo(first);
    }

    // ==========================================================================================
    // Generators
    // ==========================================================================================

    /** Correlated pair of dates holder so both equal and ordered draws are exercised. */
    private static final class DatePair {
        final LocalDate periodTo;
        final LocalDate createdDate;

        DatePair(LocalDate periodTo, LocalDate createdDate) {
            this.periodTo = periodTo;
            this.createdDate = createdDate;
        }

        @Override
        public String toString() {
            return "DatePair{periodTo=" + periodTo + ", createdDate=" + createdDate + "}";
        }
    }

    /**
     * Correlated date pairs: a base creation date plus a signed day offset in {@code [-30, +30]} so
     * {@code periodTo} lands strictly before, exactly on, and strictly after {@code createdDate} —
     * covering both sides of the biconditional and the strict boundary.
     */
    @Provide
    Arbitrary<DatePair> datePairs() {
        return Combinators.combine(anyDate(), signedOffsets())
                .as((createdDate, offset) -> new DatePair(createdDate.plusDays(offset), createdDate));
    }

    @Provide
    Arbitrary<LocalDate> anyDate() {
        return Dates.dates().between(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));
    }

    /** Signed day offsets straddling the boundary (before / on / after). */
    @Provide
    Arbitrary<Long> signedOffsets() {
        return Arbitraries.longs().between(-30, 30);
    }

    /** Strictly positive forward offsets (periodTo strictly after createdDate). */
    @Provide
    Arbitrary<Long> positiveOffsets() {
        return Arbitraries.longs().between(1, 3650);
    }

    /** Non-negative backward offsets (periodTo on or before createdDate; 0 is the equal boundary). */
    @Provide
    Arbitrary<Long> nonNegativeOffsets() {
        return Arbitraries.longs().between(0, 3650);
    }
}
