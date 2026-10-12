package com.foremen.service.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based unit tests for the FOR-05-10b half-day grid helpers of {@link ScheduleCalculator}:
 * the {@code startDay} / {@code durationDays} / {@code finishDay} conversions from half-day counts,
 * the half-day {@code Finish_Day} offset, and the half-day {@link ScheduleCalculator#validateBar}
 * with its 7300 horizon (design §"Half-day grid").
 *
 * <p>These pin down the exact conversion formulas and the four corners of the accepted region so a
 * regression in the arithmetic is caught by a concrete example (the property test
 * {@link ScheduleHalfDayPropertyTest} covers the universal rules).
 *
 * <p><b>Validates: Requirements 3.3, 3.4, 3.5</b>
 */
class ScheduleCalculatorHalfDayTest {

    private static final int MAX_HALF_DAY = ScheduleCalculator.MAX_HALF_DAY; // 7300

    @Test
    @DisplayName("MAX_HALF_DAY is 7300 (= 2 * 3650-day horizon)")
    void maxHalfDayIsTwiceMaxDay() {
        assertThat(MAX_HALF_DAY).isEqualTo(7300);
        assertThat(MAX_HALF_DAY).isEqualTo(2 * ScheduleCalculator.MAX_DAY);
    }

    @Nested
    @DisplayName("startDay / durationDays / finishDay conversions (R3.3)")
    class Conversions {

        @Test
        @DisplayName("startDay = startHalfDays / 2.0")
        void startDayFromHalfDays() {
            assertThat(ScheduleCalculator.startDayFromHalfDays(1)).isEqualTo(0.5);
            assertThat(ScheduleCalculator.startDayFromHalfDays(2)).isEqualTo(1.0);
            assertThat(ScheduleCalculator.startDayFromHalfDays(3)).isEqualTo(1.5);
            assertThat(ScheduleCalculator.startDayFromHalfDays(7300)).isEqualTo(3650.0);
        }

        @Test
        @DisplayName("durationDays = durationHalfDays / 2.0")
        void durationDaysFromHalfDays() {
            assertThat(ScheduleCalculator.durationDaysFromHalfDays(1)).isEqualTo(0.5);
            assertThat(ScheduleCalculator.durationDaysFromHalfDays(2)).isEqualTo(1.0);
            assertThat(ScheduleCalculator.durationDaysFromHalfDays(5)).isEqualTo(2.5);
        }

        @Test
        @DisplayName("finishDay = (startHalfDays + durationHalfDays - 1) / 2.0")
        void finishDayFromHalfDays() {
            // A single half-day starting at half-day 1 finishes at day 0.5.
            assertThat(ScheduleCalculator.finishDayFromHalfDays(1, 1)).isEqualTo(0.5);
            // Start half-day 1, duration 2 half-days -> finish offset 2 -> day 1.0.
            assertThat(ScheduleCalculator.finishDayFromHalfDays(1, 2)).isEqualTo(1.0);
            // Start half-day 3 (day 1.5), duration 4 half-days (2.0 days) -> finish offset 6 -> 3.0.
            assertThat(ScheduleCalculator.finishDayFromHalfDays(3, 4)).isEqualTo(3.0);
            // The matched design example: startDay 1.0, duration 2.5 -> finish 3.0.
            // startHalfDays = 2, durationHalfDays = 5 -> offset 6 -> day 3.0.
            assertThat(ScheduleCalculator.finishDayFromHalfDays(2, 5)).isEqualTo(3.0);
        }

        @Test
        @DisplayName("finishHalfDay = startHalfDays + durationHalfDays - 1 (half-day offset)")
        void finishHalfDay() {
            assertThat(ScheduleCalculator.finishHalfDay(1, 1)).isEqualTo(1);
            assertThat(ScheduleCalculator.finishHalfDay(2, 5)).isEqualTo(6);
            assertThat(ScheduleCalculator.finishHalfDay(1, MAX_HALF_DAY)).isEqualTo(MAX_HALF_DAY);
        }

        @Test
        @DisplayName("every conversion yields a 0.5-multiple of a day")
        void conversionsAreHalfDayMultiples() {
            for (int halfDays = 1; halfDays <= 20; halfDays++) {
                double startDay = ScheduleCalculator.startDayFromHalfDays(halfDays);
                double durationDays = ScheduleCalculator.durationDaysFromHalfDays(halfDays);
                assertThat(startDay * 2.0).isEqualTo(Math.rint(startDay * 2.0));
                assertThat(durationDays * 2.0).isEqualTo(Math.rint(durationDays * 2.0));
            }
        }
    }

    @Nested
    @DisplayName("validateBar(startHalfDays, durationHalfDays) (R3.4, R3.5)")
    class ValidateBar {

        @Test
        @DisplayName("both null clears the bar and is accepted")
        void bothNullAccepted() {
            assertThat(ScheduleCalculator.validateBar((Integer) null, (Integer) null)).isTrue();
        }

        @Test
        @DisplayName("a half-null pair is always rejected")
        void halfNullRejected() {
            assertThat(ScheduleCalculator.validateBar(5, (Integer) null)).isFalse();
            assertThat(ScheduleCalculator.validateBar((Integer) null, 5)).isFalse();
        }

        @Test
        @DisplayName("the smallest valid bar (1, 1) is accepted")
        void smallestBarAccepted() {
            assertThat(ScheduleCalculator.validateBar(1, 1)).isTrue();
        }

        @Test
        @DisplayName("a bar finishing exactly on the 7300 horizon is accepted")
        void finishOnHorizonAccepted() {
            assertThat(ScheduleCalculator.validateBar(1, MAX_HALF_DAY)).isTrue();
            assertThat(ScheduleCalculator.validateBar(MAX_HALF_DAY, 1)).isTrue();
            assertThat(ScheduleCalculator.validateBar(MAX_HALF_DAY / 2 + 1, MAX_HALF_DAY / 2))
                    .isTrue();
        }

        @Test
        @DisplayName("a bar finishing one half-day beyond 7300 is rejected")
        void finishBeyondHorizonRejected() {
            assertThat(ScheduleCalculator.validateBar(2, MAX_HALF_DAY)).isFalse();
            assertThat(ScheduleCalculator.validateBar(MAX_HALF_DAY, 2)).isFalse();
            assertThat(ScheduleCalculator.validateBar(MAX_HALF_DAY + 1, 1)).isFalse();
        }

        @Test
        @DisplayName("a value below 1 is rejected")
        void belowLowerBoundRejected() {
            assertThat(ScheduleCalculator.validateBar(0, 1)).isFalse();
            assertThat(ScheduleCalculator.validateBar(1, 0)).isFalse();
            assertThat(ScheduleCalculator.validateBar(-1, 1)).isFalse();
            assertThat(ScheduleCalculator.validateBar(1, -1)).isFalse();
        }

        @Test
        @DisplayName("the half-day horizon is twice the whole-day horizon, so a 3651-start is valid")
        void halfDayHorizonExceedsWholeDayHorizon() {
            // 3651 half-days is day 1825.5 — well within the 7300 half-day horizon, even though it is
            // beyond the whole-day MAX_DAY of 3650. This proves validateBar uses the half-day limit.
            assertThat(ScheduleCalculator.validateBar(ScheduleCalculator.MAX_DAY + 1, 1)).isTrue();
        }
    }
}
