package com.foremen.service.payroll;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.payroll.PeriodResolver.QuickSelect;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.time.api.Dates;

/**
 * Property-based test for the pure {@link PeriodResolver} quick-select date resolver (FOR-06-11,
 * task 2.6). The resolver maps each {@link QuickSelect} option to a {@code [from, to]}
 * {@link DateRange} given a reference {@code today}, the project start, and the worker's latest
 * payout {@code periodTo} — all static, side-effect free, so the date math is directly
 * jqwik-testable without any Spring/JPA context.
 *
 * <p>Property 6: PeriodResolver — for any reference date:
 * <ul>
 *   <li><b>CURRENT_MONTH</b> / <b>PREVIOUS_MONTH</b> resolve to the respective calendar-month
 *       bounds;</li>
 *   <li><b>SINCE_LAST_PAYOUT</b> resolves from the latest {@code periodTo} (else project start)
 *       through {@code today};</li>
 *   <li><b>SINCE_PROJECT_START</b> resolves from the project start through {@code today};</li>
 *   <li><b>ADVANCE</b> resolves forward-looking so {@code periodTo} is strictly after the reference
 *       date (so the derived {@code isAdvance} holds).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 5.1, 5.4</b>
 */
// Feature: FOR-06-11-payroll, Property 6: PeriodResolver
@Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
class PeriodResolverPropertyTest {

    /**
     * CURRENT_MONTH resolves to the first and last calendar day of {@code today}'s month, for any
     * project start / last-payout combination (those inputs are irrelevant to this option).
     *
     * <p><b>Validates: Requirements 5.1</b>
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void currentMonthResolvesToCalendarMonthBounds(@ForAll("dates") LocalDate today,
                                                   @ForAll("dates") LocalDate projectStart,
                                                   @ForAll("nullableDates") LocalDate lastPayoutPeriodTo) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.CURRENT_MONTH, today, projectStart, lastPayoutPeriodTo);

        assertThat(range.from()).isEqualTo(today.with(TemporalAdjusters.firstDayOfMonth()));
        assertThat(range.to()).isEqualTo(today.with(TemporalAdjusters.lastDayOfMonth()));
        // Both bounds share today's month.
        assertThat(range.from().getMonth()).isEqualTo(today.getMonth());
        assertThat(range.to().getMonth()).isEqualTo(today.getMonth());
        assertThat(range.isValid()).isTrue();
    }

    /**
     * PREVIOUS_MONTH resolves to the full calendar month immediately before {@code today}'s month.
     *
     * <p><b>Validates: Requirements 5.1</b>
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void previousMonthResolvesToPriorCalendarMonthBounds(@ForAll("dates") LocalDate today,
                                                         @ForAll("dates") LocalDate projectStart,
                                                         @ForAll("nullableDates") LocalDate lastPayoutPeriodTo) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.PREVIOUS_MONTH, today, projectStart, lastPayoutPeriodTo);

        LocalDate prev = today.minusMonths(1);
        assertThat(range.from()).isEqualTo(prev.with(TemporalAdjusters.firstDayOfMonth()));
        assertThat(range.to()).isEqualTo(prev.with(TemporalAdjusters.lastDayOfMonth()));
        assertThat(range.from().getMonth()).isEqualTo(prev.getMonth());
        assertThat(range.to().getMonth()).isEqualTo(prev.getMonth());
        // The resolved window ends strictly before today's month begins.
        assertThat(range.to()).isBefore(today.with(TemporalAdjusters.firstDayOfMonth()));
        assertThat(range.isValid()).isTrue();
    }

    /**
     * SINCE_LAST_PAYOUT resolves from the worker's latest payout {@code periodTo} through
     * {@code today} when a prior payout exists, else from the project start through {@code today}.
     *
     * <p><b>Validates: Requirements 5.1</b>
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void sinceLastPayoutResolvesFromLatestPeriodToElseProjectStart(
            @ForAll("dates") LocalDate today,
            @ForAll("dates") LocalDate projectStart,
            @ForAll("nullableDates") LocalDate lastPayoutPeriodTo) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.SINCE_LAST_PAYOUT, today, projectStart, lastPayoutPeriodTo);

        LocalDate expectedFrom = lastPayoutPeriodTo != null ? lastPayoutPeriodTo : projectStart;
        assertThat(range.from()).isEqualTo(expectedFrom);
        assertThat(range.to()).isEqualTo(today);
    }

    /**
     * SINCE_PROJECT_START resolves from the project start through {@code today}, independent of any
     * prior payout.
     *
     * <p><b>Validates: Requirements 5.1</b>
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void sinceProjectStartResolvesFromProjectStart(@ForAll("dates") LocalDate today,
                                                   @ForAll("dates") LocalDate projectStart,
                                                   @ForAll("nullableDates") LocalDate lastPayoutPeriodTo) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.SINCE_PROJECT_START, today, projectStart, lastPayoutPeriodTo);

        assertThat(range.from()).isEqualTo(projectStart);
        assertThat(range.to()).isEqualTo(today);
        // The last-payout input never influences this option.
        assertThat(range.from()).isEqualTo(projectStart);
    }

    /**
     * ADVANCE resolves to a forward-looking interval whose {@code periodTo} is strictly after the
     * reference date, so the derived {@code isAdvance} ({@code periodTo > creation date}) holds for
     * ANY reference date — including when {@code today} is already the last day of its month.
     *
     * <p><b>Validates: Requirements 5.4</b>
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void advanceResolvesForwardLookingSoPeriodToIsAfterToday(@ForAll("dates") LocalDate today,
                                                             @ForAll("dates") LocalDate projectStart,
                                                             @ForAll("nullableDates") LocalDate lastPayoutPeriodTo) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.ADVANCE, today, projectStart, lastPayoutPeriodTo);

        // The defining advance invariant: periodTo is strictly after the reference date (R5.4).
        assertThat(range.to()).isAfter(today);
        assertThat(range.from()).isEqualTo(today);
        assertThat(range.isValid()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Any calendar date within a broad window. The upper bound stays below year 9999 so the ADVANCE
     * roll-to-next-month never overflows the supported {@link LocalDate} range.
     */
    @Provide
    Arbitrary<LocalDate> dates() {
        return Dates.dates().between(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31));
    }

    /** A date, or {@code null} — exercising the "no prior payout" branch of SINCE_LAST_PAYOUT. */
    @Provide
    Arbitrary<LocalDate> nullableDates() {
        return Arbitraries.oneOf(
                Arbitraries.just((LocalDate) null),
                Dates.dates().between(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31)));
    }
}
