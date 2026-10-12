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
 * Property-based tests for the FOR-05-10b half-day grid helpers of {@link ScheduleCalculator}: the
 * {@code startDay} / {@code durationDays} / {@code finishDay} conversions and the half-day
 * {@link ScheduleCalculator#validateBar(Integer, Integer)} with its 7300 horizon (design
 * §"Half-day grid").
 *
 * <p>The helpers are pure functions — no persistence, no Spring context, no clock — so these
 * properties are cheap to run over hundreds of iterations.
 *
 * <ul>
 *   <li><b>Conversion exactness (R3.3):</b> every conversion is exactly {@code halfDays / 2.0}, is a
 *       0.5-multiple of a day, and the finish-day formula mirrors the whole-day formula on the
 *       half-day grid.</li>
 *   <li><b>Validation oracle (R3.4, R3.5):</b> over the whole integer range including nulls and
 *       boundaries, {@code validateBar} accepts a pair if and only if either both are null or both
 *       are {@code >= 1} with a half-day finish of at most 7300 — matched by an independent
 *       specification oracle.</li>
 * </ul>
 *
 * <p>Feature: FOR-05-10b-gantt-granularity, half-day grid
 *
 * <p><b>Validates: Requirements 3.3, 3.4, 3.5</b>
 */
@Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
class ScheduleHalfDayPropertyTest {

    private static final int MAX_HALF_DAY = ScheduleCalculator.MAX_HALF_DAY; // 7300

    // ------------------------------------------------------------------------------------------
    // Conversions (R3.3): startDay = h/2, durationDays = h/2, both are 0.5-multiples.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void startAndDurationAreExactlyHalfOfTheHalfDayCount(
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int halfDays) {

        double startDay = ScheduleCalculator.startDayFromHalfDays(halfDays);
        double durationDays = ScheduleCalculator.durationDaysFromHalfDays(halfDays);

        assertThat(startDay).isEqualTo(halfDays / 2.0);
        assertThat(durationDays).isEqualTo(halfDays / 2.0);

        // Each is a 0.5-multiple of a day: doubling returns an integer with no residue.
        assertThat(startDay * 2.0).isEqualTo((double) halfDays);
        assertThat(durationDays * 2.0).isEqualTo((double) halfDays);
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void finishDayMirrorsTheWholeDayFormulaOnTheHalfDayGrid(
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int startHalfDays,
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int durationHalfDays) {

        double finishDay =
                ScheduleCalculator.finishDayFromHalfDays(startHalfDays, durationHalfDays);
        int finishHalfDay = ScheduleCalculator.finishHalfDay(startHalfDays, durationHalfDays);

        // finishDay = (start + dur - 1) / 2.0, i.e. the half-day finish offset over 2.
        assertThat(finishHalfDay).isEqualTo(startHalfDays + durationHalfDays - 1);
        assertThat(finishDay).isEqualTo(finishHalfDay / 2.0);

        // A single-half-day bar's finish day equals its start day (both = startHalfDays / 2.0).
        if (durationHalfDays == 1) {
            assertThat(finishDay)
                    .isEqualTo(ScheduleCalculator.startDayFromHalfDays(startHalfDays));
        }

        // finishDay is never before startDay (durations are >= 1 half-day).
        assertThat(finishDay)
                .isGreaterThanOrEqualTo(ScheduleCalculator.startDayFromHalfDays(startHalfDays));
    }

    // ------------------------------------------------------------------------------------------
    // Validation oracle (R3.4, R3.5): over the full range including nulls and boundaries the
    // decision matches the specification exactly — accepted iff both null, or both >= 1 with a
    // half-day finish <= 7300.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 1000)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void validateBarDecisionMatchesSpecificationOverFullRange(
            @ForAll("maybeHalfDays") Integer startHalfDays,
            @ForAll("maybeHalfDays") Integer durationHalfDays) {

        boolean actual = ScheduleCalculator.validateBar(startHalfDays, durationHalfDays);
        boolean expected = specAccepts(startHalfDays, durationHalfDays);

        assertThat(actual)
                .as("validateBar(%s, %s)", startHalfDays, durationHalfDays)
                .isEqualTo(expected);
    }

    /**
     * The specification oracle, written independently of the implementation: both {@code null} is a
     * valid clear; otherwise both must be present, {@code >= 1}, with a half-day finish
     * {@code (start + dur - 1)} at most {@code 7300}. There is no upper bound on either field alone
     * — only the finish horizon constrains them (R3.4, R3.5).
     */
    private static boolean specAccepts(Integer startHalfDays, Integer durationHalfDays) {
        if (startHalfDays == null && durationHalfDays == null) {
            return true;
        }
        if (startHalfDays == null || durationHalfDays == null) {
            return false;
        }
        if (startHalfDays < 1 || durationHalfDays < 1) {
            return false;
        }
        long finish = (long) startHalfDays + durationHalfDays - 1;
        return finish <= MAX_HALF_DAY;
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void halfNullIsAlwaysRejected(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE) int value) {
        assertThat(ScheduleCalculator.validateBar(value, (Integer) null)).isFalse();
        assertThat(ScheduleCalculator.validateBar((Integer) null, value)).isFalse();
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void inHorizonPairIsAccepted(@ForAll("validHalfDayBars") int[] bar) {
        int startHalfDays = bar[0];
        int durationHalfDays = bar[1];

        assertThat(startHalfDays).isGreaterThanOrEqualTo(1);
        assertThat(durationHalfDays).isGreaterThanOrEqualTo(1);
        assertThat(startHalfDays + durationHalfDays - 1).isLessThanOrEqualTo(MAX_HALF_DAY);

        assertThat(ScheduleCalculator.validateBar(startHalfDays, durationHalfDays)).isTrue();
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void finishBeyondHorizonIsRejected(
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int startHalfDays,
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int durationHalfDays) {

        long finish = (long) startHalfDays + durationHalfDays - 1;
        if (finish <= MAX_HALF_DAY) {
            return; // not the case under test
        }
        assertThat(ScheduleCalculator.validateBar(startHalfDays, durationHalfDays)).isFalse();
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, half-day grid")
    void belowLowerBoundIsRejected(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = 0) int nonPositive,
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int inHorizon) {

        assertThat(ScheduleCalculator.validateBar(nonPositive, inHorizon)).isFalse();
        assertThat(ScheduleCalculator.validateBar(inHorizon, nonPositive)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Half-day values spanning well below 1, the whole valid range, and well above 7300, plus
     * {@code null}, so the oracle property exercises the lower bound, the finish horizon, half-null,
     * and both-null cases. Boundary values are edge-cased so jqwik reliably hits {@code 0},
     * {@code 1}, {@code 7300}, and {@code 7301}.
     */
    @Provide
    Arbitrary<Integer> maybeHalfDays() {
        return Arbitraries.integers()
                .between(-10, MAX_HALF_DAY + 10)
                .edgeCases(config -> config.add(0, 1, MAX_HALF_DAY, MAX_HALF_DAY + 1, -1))
                .injectNull(0.1);
    }

    /**
     * A well-formed, in-horizon bar: a start half-day in {@code [1, 7300]} paired with a duration in
     * {@code [1, 7300 - startHalfDays + 1]} so the half-day finish stays within {@code [1, 7300]}.
     */
    @Provide
    Arbitrary<int[]> validHalfDayBars() {
        return Arbitraries.integers().between(1, MAX_HALF_DAY).flatMap(startHalfDays -> {
            int maxDuration = MAX_HALF_DAY - startHalfDays + 1;
            return Arbitraries.integers()
                    .between(1, maxDuration)
                    .map(durationHalfDays -> new int[] {startHalfDays, durationHalfDays});
        });
    }
}
