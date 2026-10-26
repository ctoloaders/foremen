package com.foremen.service.companyschedule;

import com.foremen.service.schedule.ScheduleCalculator;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.time.api.Dates;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the absolute-date projection helpers of
 * {@link CrossProjectScheduleProjector} —
 * {@link CrossProjectScheduleProjector#workerBarDates(LocalDate, int, int)} and
 * {@link CrossProjectScheduleProjector#foremanSpanDates(LocalDate, LocalDate)}
 * (FOR-07-01-cross-project-schedule-core; design §"Absolute dates", Properties 5 and 6).
 *
 * <p>Both helpers are pure functions — no persistence, no Spring context, no clock — so these
 * properties are cheap to run over 100+ iterations, mirroring {@code ScheduleCalculatorPropertyTest}.
 *
 * <ul>
 *   <li><b>Property 5 (worker-bar dates round-trip the calculator):</b> for any anchored project and
 *       worker bar, {@code workerBarDates} equals
 *       {@code anchor.plusDays(calendarDayOfHalfDay(startHalfDays) - 1)} /
 *       {@code anchor.plusDays(calendarDayOfHalfDay(startHalfDays + durationHalfDays - 1) - 1)};
 *       an unanchored (null-anchor) bar yields both dates null.</li>
 *   <li><b>Property 6 (foreman span datable only when both dates present):</b> for any foreman span,
 *       {@code foremanSpanDates} equals {@code [start, end]} when both are present, and
 *       {@code [null, null]} whenever either is missing.</li>
 * </ul>
 *
 * <p>Feature: FOR-07-01-cross-project-schedule-core, Properties 5-6: Date projection
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 4.4</b>
 */
@Tag("Feature: FOR-07-01-cross-project-schedule-core, Properties 5-6: Date projection")
class CrossProjectScheduleProjectorDatePropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 5: Absolute-date projection round-trips the calculator (R4.1, R4.3)
    //   startDate = anchor.plusDays(calendarDayOfHalfDay(startHalfDays) - 1)
    //   endDate   = anchor.plusDays(calendarDayOfHalfDay(startHalfDays + durationHalfDays - 1) - 1)
    //   unanchored (null anchor) => both null
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 5: Absolute-date projection round-trips the calculator")
    void workerBarDatesRoundTripTheCalculatorForAnchoredProject(
            @ForAll("anchors") LocalDate anchor,
            @ForAll("startHalfDays") int startHalfDays,
            @ForAll("durationHalfDays") int durationHalfDays) {

        LocalDate[] dates =
                CrossProjectScheduleProjector.workerBarDates(anchor, startHalfDays, durationHalfDays);

        // The helper exactly re-derives the calendar day from the half-day grid and offsets it
        // from the anchor (where day 1 is the anchor itself).
        int expectedStartDay = ScheduleCalculator.calendarDayOfHalfDay(startHalfDays);
        int expectedFinishDay = ScheduleCalculator.calendarDayOfHalfDay(
                ScheduleCalculator.finishHalfDay(startHalfDays, durationHalfDays));
        LocalDate expectedStart = anchor.plusDays(expectedStartDay - 1L);
        LocalDate expectedEnd = anchor.plusDays(expectedFinishDay - 1L);

        assertThat(dates).hasSize(2);
        assertThat(dates[0]).isEqualTo(expectedStart);
        assertThat(dates[1]).isEqualTo(expectedEnd);

        // An anchored bar is always datable, and (since half-day fields are >= 1) the finish is never
        // before the start.
        assertThat(dates[0]).isNotNull();
        assertThat(dates[1]).isNotNull();
        assertThat(dates[1]).isAfterOrEqualTo(dates[0]);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 5: Absolute-date projection round-trips the calculator")
    void workerBarDatesAreNullForUnanchoredProject(
            @ForAll("startHalfDays") int startHalfDays,
            @ForAll("durationHalfDays") int durationHalfDays) {

        // A null anchor marks an Unanchored_Project: the worker bar is an Undatable_Bar (R4.3).
        LocalDate[] dates =
                CrossProjectScheduleProjector.workerBarDates(null, startHalfDays, durationHalfDays);

        assertThat(dates).hasSize(2);
        assertThat(dates[0]).isNull();
        assertThat(dates[1]).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Property 6: Foreman span datable only when both project dates present (R4.2, R4.4)
    //   both present  => [start, end]
    //   either missing => [null, null]
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 6: Foreman span datable only when both project dates present")
    void foremanSpanDatableWhenBothDatesPresent(
            @ForAll("anchors") LocalDate start,
            @ForAll @IntRange(min = 0, max = 3650) int spanDays) {

        // An end on or after the start keeps the span a well-formed interval; the helper takes the
        // project's own calendar dates directly with no half-day conversion (R4.2).
        LocalDate end = start.plusDays(spanDays);

        LocalDate[] dates = CrossProjectScheduleProjector.foremanSpanDates(start, end);

        assertThat(dates).hasSize(2);
        assertThat(dates[0]).isEqualTo(start);
        assertThat(dates[1]).isEqualTo(end);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 6: Foreman span datable only when both project dates present")
    void foremanSpanUndatableWhenEitherDateMissing(
            @ForAll("anchors") LocalDate presentDate,
            @ForAll("missingDatePair") boolean[] missing) {

        // missing[0] => start is null, missing[1] => end is null. At least one is null by generator.
        LocalDate start = missing[0] ? null : presentDate;
        LocalDate end = missing[1] ? null : presentDate;

        LocalDate[] dates = CrossProjectScheduleProjector.foremanSpanDates(start, end);

        // A span is datable only when BOTH project dates are present; any missing bound makes it an
        // Undatable_Bar with both dates null (R4.4).
        assertThat(dates).hasSize(2);
        assertThat(dates[0]).isNull();
        assertThat(dates[1]).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Anchor / project dates across a wide but finite calendar range, so offsetting by up to the
     * schedule horizon never leaves the supported {@link LocalDate} range.
     */
    @Provide
    Arbitrary<LocalDate> anchors() {
        return Dates.dates().between(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));
    }

    /**
     * A bar's 1-based start half-day. Bounded well inside {@link ScheduleCalculator#MAX_HALF_DAY} so
     * {@code startHalfDays + durationHalfDays - 1} stays within the horizon and the projected dates
     * stay in range.
     */
    @Provide
    Arbitrary<Integer> startHalfDays() {
        return Arbitraries.integers().between(1, ScheduleCalculator.MAX_DAY);
    }

    /** A bar's half-day duration ({@code >= 1}), bounded to keep the finish within the horizon. */
    @Provide
    Arbitrary<Integer> durationHalfDays() {
        return Arbitraries.integers().between(1, ScheduleCalculator.MAX_DAY);
    }

    /**
     * A {@code (startMissing, endMissing)} flag pair in which at least one flag is {@code true}, so
     * the generated foreman span is always missing one or both project dates.
     */
    @Provide
    Arbitrary<boolean[]> missingDatePair() {
        return Arbitraries.of(
                new boolean[] {true, false},
                new boolean[] {false, true},
                new boolean[] {true, true});
    }
}
