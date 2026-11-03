package com.foremen.service.payroll;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.payroll.PeriodResolver.QuickSelect;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.time.api.Dates;

/**
 * Property-based test for the pure {@link PeriodResolver} quick-select date resolver (FOR-06-11
 * task 2.6, reshaped by FOR-07-06 task 5.2: worker-wide, {@code SINCE_PROJECT_START} removed).
 *
 * <p>Property 6: PeriodResolver — for any reference date:
 * <ul>
 *   <li><b>CURRENT_MONTH</b> / <b>PREVIOUS_MONTH</b> resolve to the respective calendar-month
 *       bounds;</li>
 *   <li><b>SINCE_LAST_PAYOUT</b> resolves from the worker's latest payout {@code periodTo} (else the
 *       earliest unpaid line's {@code performedFrom}, else today) through {@code today}, never
 *       inverted;</li>
 *   <li><b>ADVANCE</b> resolves forward-looking so {@code periodTo} is strictly after the reference
 *       date.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 5.1, 5.4</b>
 */
// Feature: FOR-06-11-payroll, Property 6: PeriodResolver
@Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
class PeriodResolverPropertyTest {

    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void currentMonthResolvesToCalendarMonthBounds(@ForAll("dates") LocalDate today,
                                                   @ForAll("nullableDates") LocalDate lastPayoutPeriodTo,
                                                   @ForAll("nullableDates") LocalDate earliestUnpaid) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.CURRENT_MONTH, today, lastPayoutPeriodTo, earliestUnpaid);

        assertThat(range.from()).isEqualTo(today.with(TemporalAdjusters.firstDayOfMonth()));
        assertThat(range.to()).isEqualTo(today.with(TemporalAdjusters.lastDayOfMonth()));
        assertThat(range.isValid()).isTrue();
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void previousMonthResolvesToPriorCalendarMonthBounds(@ForAll("dates") LocalDate today,
                                                         @ForAll("nullableDates") LocalDate lastPayoutPeriodTo,
                                                         @ForAll("nullableDates") LocalDate earliestUnpaid) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.PREVIOUS_MONTH, today, lastPayoutPeriodTo, earliestUnpaid);

        LocalDate prev = today.minusMonths(1);
        assertThat(range.from()).isEqualTo(prev.with(TemporalAdjusters.firstDayOfMonth()));
        assertThat(range.to()).isEqualTo(prev.with(TemporalAdjusters.lastDayOfMonth()));
        assertThat(range.to()).isBefore(today.with(TemporalAdjusters.firstDayOfMonth()));
        assertThat(range.isValid()).isTrue();
    }

    /**
     * SINCE_LAST_PAYOUT (worker-wide, FOR-07-06): from the latest payout {@code periodTo}, else the
     * earliest unpaid line's {@code performedFrom}, else today — clamped to today so the range is
     * never inverted — through {@code today}.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void sinceLastPayoutResolvesWorkerWideWithFallbacks(@ForAll("dates") LocalDate today,
                                                        @ForAll("nullableDates") LocalDate lastPayoutPeriodTo,
                                                        @ForAll("nullableDates") LocalDate earliestUnpaid) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.SINCE_LAST_PAYOUT, today, lastPayoutPeriodTo, earliestUnpaid);

        LocalDate expected = lastPayoutPeriodTo != null ? lastPayoutPeriodTo
                : earliestUnpaid != null ? earliestUnpaid : today;
        if (expected.isAfter(today)) {
            expected = today;
        }
        assertThat(range.from()).isEqualTo(expected);
        assertThat(range.to()).isEqualTo(today);
        assertThat(range.isValid()).isTrue();
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-11-payroll, Property 6: PeriodResolver")
    void advanceResolvesForwardLookingSoPeriodToIsAfterToday(@ForAll("dates") LocalDate today,
                                                             @ForAll("nullableDates") LocalDate lastPayoutPeriodTo,
                                                             @ForAll("nullableDates") LocalDate earliestUnpaid) {
        DateRange range = PeriodResolver.resolve(
                QuickSelect.ADVANCE, today, lastPayoutPeriodTo, earliestUnpaid);

        assertThat(range.to()).isAfter(today);
        assertThat(range.from()).isEqualTo(today);
        assertThat(range.isValid()).isTrue();
    }

    /** FOR-07-06: the project-bound SINCE_PROJECT_START option no longer exists. */
    @Example
    void sinceProjectStartIsRemoved() {
        assertThat(Arrays.stream(QuickSelect.values()).map(Enum::name))
                .containsExactly("CURRENT_MONTH", "PREVIOUS_MONTH", "SINCE_LAST_PAYOUT", "ADVANCE");
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<LocalDate> dates() {
        return Dates.dates().between(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31));
    }

    @Provide
    Arbitrary<LocalDate> nullableDates() {
        return Arbitraries.oneOf(
                Arbitraries.just((LocalDate) null),
                Dates.dates().between(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31)));
    }
}
