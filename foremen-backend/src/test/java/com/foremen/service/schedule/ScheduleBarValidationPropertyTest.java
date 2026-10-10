package com.foremen.service.schedule;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link ScheduleCalculator#validateBar(Integer, Integer)} — the pure
 * bounds check a bar save entry must pass (FOR-05-10, Requirement 7; design §"ScheduleCalculator",
 * Property 5).
 *
 * <p>The check is a pure function — no persistence, no Spring context, no clock — so Property 5 is
 * cheap to run over 100+ iterations.
 *
 * <p>Property 5 (bar validation bounds): a bar entry {@code (startDay, durationDays)} is accepted
 * if and only if either both fields are {@code null} (clear the bar), or both are whole numbers in
 * {@code [1, 3650]} whose {@code Finish_Day = startDay + durationDays - 1} is at most 3650.
 * Everything else — a half-null pair, a value below 1 or above 3650, or an in-range pair whose
 * finish overruns 3650 — is rejected.
 *
 * <p>Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds
 *
 * <p><b>Validates: Requirements 7.2, 7.3</b>
 */
@Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
class ScheduleBarValidationPropertyTest {

    private static final int MAX_DAY = ScheduleCalculator.MAX_DAY; // 3650

    // ------------------------------------------------------------------------------------------
    // Property 5 (oracle): over the whole integer range including boundaries and nulls, the
    // decision matches the specification exactly — accepted iff both null, or both in [1, 3650]
    // with finish <= 3650 (R7.2, R7.3).
    // ------------------------------------------------------------------------------------------

    @Property(tries = 1000)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void decisionMatchesSpecificationOverFullRange(
            @ForAll("maybeDays") Integer startDay,
            @ForAll("maybeDays") Integer durationDays) {

        boolean actual = ScheduleCalculator.validateBar(startDay, durationDays);
        boolean expected = specAccepts(startDay, durationDays);

        assertThat(actual)
                .as("validateBar(%s, %s)", startDay, durationDays)
                .isEqualTo(expected);
    }

    /**
     * The specification oracle, written independently of the implementation: both {@code null} is a
     * valid clear; otherwise both must be present whole numbers in {@code [1, 3650]} whose finish
     * {@code (start + dur - 1)} is at most {@code 3650}.
     */
    private static boolean specAccepts(Integer startDay, Integer durationDays) {
        if (startDay == null && durationDays == null) {
            return true;
        }
        if (startDay == null || durationDays == null) {
            return false;
        }
        boolean inRange = startDay >= 1 && startDay <= MAX_DAY
                && durationDays >= 1 && durationDays <= MAX_DAY;
        if (!inRange) {
            return false;
        }
        long finish = (long) startDay + durationDays - 1;
        return finish <= MAX_DAY;
    }

    // ------------------------------------------------------------------------------------------
    // Property 5a: both null is always accepted (clear the bar) (R7.2)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 1)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void bothNullIsAccepted() {
        assertThat(ScheduleCalculator.validateBar(null, null)).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Property 5b: exactly one null (half-null) is always rejected (R7.3)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void halfNullIsAlwaysRejected(@ForAll @IntRange(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE) int value) {
        assertThat(ScheduleCalculator.validateBar(value, null)).isFalse();
        assertThat(ScheduleCalculator.validateBar(null, value)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Property 5c: both in [1, 3650] with finish <= 3650 is accepted (R7.2)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void inBoundsPairWithFinishInRangeIsAccepted(@ForAll("validBars") int[] bar) {
        int startDay = bar[0];
        int durationDays = bar[1];

        // Precondition on the generator: a well-formed, in-range, finish-in-range pair.
        assertThat(startDay).isBetween(1, MAX_DAY);
        assertThat(durationDays).isBetween(1, MAX_DAY);
        assertThat(startDay + durationDays - 1).isLessThanOrEqualTo(MAX_DAY);

        assertThat(ScheduleCalculator.validateBar(startDay, durationDays)).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Property 5d: an otherwise in-range pair whose finish exceeds 3650 is rejected (R7.3)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void inRangePairWithFinishBeyondLimitIsRejected(
            @ForAll @IntRange(min = 1, max = MAX_DAY) int startDay,
            @ForAll @IntRange(min = 1, max = MAX_DAY) int durationDays) {

        // Only consider pairs whose finish overruns the hard limit.
        long finish = (long) startDay + durationDays - 1;
        if (finish <= MAX_DAY) {
            return; // not the case under test
        }
        assertThat(ScheduleCalculator.validateBar(startDay, durationDays)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Property 5e: any value outside [1, 3650] is rejected regardless of the other value (R7.3)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void outOfRangeValueIsRejected(
            @ForAll("outOfRangeDays") int outOfRange,
            @ForAll @IntRange(min = 1, max = MAX_DAY) int inRange) {

        // Out of range as the start day, with an in-range duration.
        assertThat(ScheduleCalculator.validateBar(outOfRange, inRange)).isFalse();
        // Out of range as the duration, with an in-range start day.
        assertThat(ScheduleCalculator.validateBar(inRange, outOfRange)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Boundary examples: the four corners of the accepted region and their neighbours.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 1)
    @Tag("Feature: FOR-05-10-project-gantt, Property 5: Bar validation bounds")
    void boundaryCornersBehaveAsSpecified() {
        // Smallest valid bar.
        assertThat(ScheduleCalculator.validateBar(1, 1)).isTrue();
        // A bar that finishes exactly on the last day.
        assertThat(ScheduleCalculator.validateBar(1, MAX_DAY)).isTrue();
        assertThat(ScheduleCalculator.validateBar(MAX_DAY, 1)).isTrue();
        assertThat(ScheduleCalculator.validateBar(MAX_DAY / 2 + 1, MAX_DAY / 2)).isTrue();

        // Finish one day beyond the limit.
        assertThat(ScheduleCalculator.validateBar(2, MAX_DAY)).isFalse();
        assertThat(ScheduleCalculator.validateBar(MAX_DAY, 2)).isFalse();

        // Below the lower bound.
        assertThat(ScheduleCalculator.validateBar(0, 1)).isFalse();
        assertThat(ScheduleCalculator.validateBar(1, 0)).isFalse();
        assertThat(ScheduleCalculator.validateBar(-1, 1)).isFalse();
        assertThat(ScheduleCalculator.validateBar(1, -1)).isFalse();

        // Above the upper bound.
        assertThat(ScheduleCalculator.validateBar(MAX_DAY + 1, 1)).isFalse();
        assertThat(ScheduleCalculator.validateBar(1, MAX_DAY + 1)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Day values spanning well below 1, the whole valid range, and well above 3650, plus
     * {@code null}, so the oracle property exercises the lower bound, the upper bound, the finish
     * limit, half-null, and both-null cases. Boundary values are edge-cased explicitly so jqwik
     * reliably hits {@code 0}, {@code 1}, {@code 3650}, and {@code 3651}.
     */
    @Provide
    Arbitrary<Integer> maybeDays() {
        return Arbitraries.integers()
                .between(-10, MAX_DAY + 10)
                .edgeCases(config -> config.add(0, 1, MAX_DAY, MAX_DAY + 1, -1))
                .injectNull(0.1);
    }

    /**
     * A well-formed, in-range bar whose finish day stays within {@code [1, 3650]}: a start day in
     * {@code [1, 3650]} paired with a duration in {@code [1, 3650 - startDay + 1]}.
     */
    @Provide
    Arbitrary<int[]> validBars() {
        return Arbitraries.integers().between(1, MAX_DAY).flatMap(startDay -> {
            int maxDuration = MAX_DAY - startDay + 1;
            return Arbitraries.integers()
                    .between(1, maxDuration)
                    .map(durationDays -> new int[] {startDay, durationDays});
        });
    }

    /** Integers strictly outside {@code [1, 3650]}: either below 1 or above 3650. */
    @Provide
    Arbitrary<Integer> outOfRangeDays() {
        Arbitrary<Integer> belowLowerBound = Arbitraries.integers().between(Integer.MIN_VALUE, 0);
        Arbitrary<Integer> aboveUpperBound =
                Arbitraries.integers().between(MAX_DAY + 1, Integer.MAX_VALUE);
        return Arbitraries.oneOf(belowLowerBound, aboveUpperBound);
    }
}
