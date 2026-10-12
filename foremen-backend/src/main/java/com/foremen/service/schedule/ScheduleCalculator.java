package com.foremen.service.schedule;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pure arithmetic of a project's planning Gantt (FOR-05-10 Requirements 5, 7, 8). Every method is
 * static and side-effect free: no database, no Spring context, no clock. Results depend only on the
 * arguments, so each rule is directly unit- and property-testable (tasks 3.4, 3.5).
 *
 * <p>Days are 1-based calendar-day offsets from the {@code Schedule_Anchor}; day 1 is the anchor
 * itself. The Gantt treats <strong>every</strong> calendar day — weekends and Polish public
 * holidays included — as a working day, so {@link #autoLayout(Collection, BigDecimal, int)} lays
 * bars out strictly back to back with no gaps (R8.3). Weekends and holidays are a frontend visual
 * mark only.
 *
 * <p>The hard length limit is {@value #MAX_DAY}: a bar's start, its duration, and its
 * {@code Finish_Day} must all stay within {@code [1, 3650]} (R7.2, R8.7).
 *
 * <p>No method here produces, consumes, or exposes a man-days figure. The {@code Daily_Output_Rate}
 * appears only as the {@code rate} argument of {@link #duration}, {@link #suggested}, and
 * {@link #autoLayout}; it is never returned (R9.3).
 */
public final class ScheduleCalculator {

    /**
     * The maximum valid day offset and {@code Finish_Day} of any bar: a start day, a duration, and a
     * bar's finish day must each lie in {@code [1, 3650]} (R7.2, R8.7). 3650 is roughly ten years of
     * calendar days.
     */
    public static final int MAX_DAY = 3650;

    /**
     * The maximum valid half-day horizon of any bar (FOR-05-10b R3.4, D-storage): a bar's
     * {@code startHalfDays + durationHalfDays - 1} (its half-day {@code Finish_Day} offset) must not
     * exceed {@value #MAX_HALF_DAY}. This is {@code 2 * }{@link #MAX_DAY} — the same 3650-day horizon
     * expressed on the half-day grid where {@code 1 half-day = 0.5 day}.
     */
    public static final int MAX_HALF_DAY = 2 * MAX_DAY;

    private ScheduleCalculator() {
    }

    /**
     * One auto-laid-out bar: the work category it belongs to and its {@code (startDay, durationDays)}
     * period in calendar days. Produced by {@link #autoLayout(Collection, BigDecimal, int)} in the
     * same order as its input rows.
     *
     * @param workCategoryId the work category the bar plans (never {@code null})
     * @param startDay       the 1-based start day of the bar (in {@code [1, 3650]})
     * @param durationDays   the bar duration in calendar days ({@code >= 1}, finish {@code <= 3650})
     */
    public record BarLayout(Long workCategoryId, int startDay, int durationDays) {}

    /**
     * The {@code Computed_Duration} of a row (R8.2): the whole number of calendar days a crew of
     * {@code crew} workers needs to produce {@code value} at {@code rate} per worker per day, i.e.
     * {@code max(1, ceil(value / (rate * crew)))}. The ceiling division is exact {@link BigDecimal}
     * arithmetic (no intermediate rounding), so for a positive {@code value} the result satisfies the
     * capacity inequality
     * {@code (d - 1) * rate * crew < value <= d * rate * crew} (R8.4). A zero or negative
     * {@code value} yields the floor of 1 day.
     *
     * @param value the row's {@code Category_Value} (net); {@code null} is treated as zero
     * @param rate  the {@code Daily_Output_Rate} per worker per day; must be strictly positive
     * @param crew  the crew size; must be {@code >= 1}
     * @return the computed duration in calendar days, never less than 1
     * @throws IllegalArgumentException if {@code crew < 1} or {@code rate} is {@code null} or not
     *                                  strictly positive
     */
    public static int duration(BigDecimal value, BigDecimal rate, int crew) {
        if (crew < 1) {
            throw new IllegalArgumentException("crew must be >= 1 to compute a duration, was " + crew);
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be strictly positive, was " + rate);
        }
        BigDecimal effectiveValue = value == null ? BigDecimal.ZERO : value;
        if (effectiveValue.signum() <= 0) {
            return 1;
        }
        BigDecimal capacityPerDay = rate.multiply(BigDecimal.valueOf(crew));
        // Exact ceiling division: no loss of precision, so the capacity inequality (R8.4) holds.
        int ceiling = effectiveValue.divide(capacityPerDay, 0, RoundingMode.CEILING).intValueExact();
        return Math.max(1, ceiling);
    }

    /**
     * The {@code Suggested_Duration} returned to the UI so it can prefill a new bar without exposing
     * the rate or man-days (R5.4): the {@link #duration(BigDecimal, BigDecimal, int)} of the row for a
     * crew of at least one, or {@code null} when the crew is empty.
     *
     * @param value the row's {@code Category_Value}; {@code null} is treated as zero
     * @param rate  the {@code Daily_Output_Rate} per worker per day; must be strictly positive
     * @param crew  the current crew size
     * @return the suggested duration in calendar days, or {@code null} when {@code crew < 1}
     * @throws IllegalArgumentException if {@code crew >= 1} and {@code rate} is not strictly positive
     */
    public static Integer suggested(BigDecimal value, BigDecimal rate, int crew) {
        return crew >= 1 ? duration(value, rate, crew) : null;
    }

    /**
     * Lays every row out as exactly one bar, back to back in input order starting at day 1, with no
     * gaps for weekends or holidays (R8.3). The first row starts at day 1; each following row starts
     * at the previous row's {@code Finish_Day + 1}. The result is deterministic: identical inputs
     * yield identical bars (R8.8).
     *
     * @param rows the rows to lay out, already in {@code Schedule_Row} order (R4.2); each row's value
     *             is read as its {@code Category_Value}. May be empty (yields an empty layout).
     * @param rate the {@code Daily_Output_Rate} per worker per day; must be strictly positive
     * @param crew the crew size; must be {@code >= 1}
     * @return one {@link BarLayout} per row, consecutive and non-overlapping, in input order
     * @throws ForemenApiException      HTTP 409 {@code error.schedule.too.long} when the layout would
     *                                  place any {@code Finish_Day} beyond {@value #MAX_DAY} (R8.7)
     * @throws IllegalArgumentException if {@code crew < 1} or {@code rate} is not strictly positive
     */
    public static List<BarLayout> autoLayout(
            Collection<ScheduleRowDerivation.RowSource> rows, BigDecimal rate, int crew) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<BarLayout> layout = new ArrayList<>(rows.size());
        int day = 1;
        for (ScheduleRowDerivation.RowSource row : rows) {
            int durationDays = duration(row.categoryValue(), rate, crew);
            // Finish_Day = startDay + durationDays - 1; reject the whole layout if it overruns (R8.7).
            long finish = (long) day + durationDays - 1;
            if (finish > MAX_DAY) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.schedule.too.long");
            }
            layout.add(new BarLayout(row.category().getId(), day, durationDays));
            day = (int) finish + 1; // next row starts the day after this one's finish (R8.3)
        }
        return layout;
    }

    /**
     * The {@code Finish_Day} of a bar: {@code startDay + durationDays - 1}.
     *
     * @param startDay     the bar's 1-based start day
     * @param durationDays the bar's duration in calendar days
     * @return the inclusive last day the bar covers
     */
    public static int finishDay(int startDay, int durationDays) {
        return startDay + durationDays - 1;
    }

    /**
     * The schedule {@code Finish_Day}: the largest {@link #finishDay(int, int)} over all bars, or
     * {@code null} when no bar is scheduled.
     *
     * @param bars the scheduled bars; may be empty
     * @return the latest finish day, or {@code null} when {@code bars} is empty
     */
    public static Integer scheduleFinish(Collection<BarLayout> bars) {
        if (bars == null || bars.isEmpty()) {
            return null;
        }
        int max = Integer.MIN_VALUE;
        for (BarLayout bar : bars) {
            max = Math.max(max, finishDay(bar.startDay(), bar.durationDays()));
        }
        return max;
    }

    /**
     * The schedule finish date: the anchor plus {@code finishDay - 1} days (day 1 is the anchor
     * itself), or {@code null} when either the anchor or the finish day is absent (R5.1).
     *
     * @param anchor    the {@code Schedule_Anchor} ({@code Project.startDate}); may be {@code null}
     * @param finishDay the schedule {@code Finish_Day}; may be {@code null}
     * @return the finish date, or {@code null} when either argument is {@code null}
     */
    public static LocalDate finishDate(LocalDate anchor, Integer finishDay) {
        if (anchor == null || finishDay == null) {
            return null;
        }
        return anchor.plusDays(finishDay - 1L);
    }

    /**
     * Whether the schedule overruns the project end date (R5.5): {@code true} if and only if the
     * anchor, the project end date, and the finish day all exist and the resulting
     * {@link #finishDate(LocalDate, Integer)} is strictly after the end date.
     *
     * @param anchor    the {@code Schedule_Anchor}; may be {@code null}
     * @param endDate   {@code Project.endDate}; may be {@code null}
     * @param finishDay the schedule {@code Finish_Day}; may be {@code null}
     * @return {@code true} only when all three exist and the finish date is after the end date
     */
    public static boolean exceedsProjectEnd(LocalDate anchor, LocalDate endDate, Integer finishDay) {
        if (anchor == null || endDate == null || finishDay == null) {
            return false;
        }
        LocalDate finish = finishDate(anchor, finishDay);
        return finish.isAfter(endDate);
    }

    /**
     * Whether a bar entry's {@code (startDay, durationDays)} pair is a valid save (R7.2, R7.3): either
     * both {@code null} (clear the bar) or both whole numbers in {@code [1, 3650]} whose
     * {@code Finish_Day} is at most {@value #MAX_DAY}. A half-null pair, an out-of-range value, or a
     * finish beyond the limit is rejected.
     *
     * @param startDay     the entry's start day; {@code null} only when {@code durationDays} is too
     * @param durationDays the entry's duration; {@code null} only when {@code startDay} is too
     * @return {@code true} if the pair is a valid clear or a valid in-bounds bar
     * @deprecated FOR-05-10b moves bar storage to the half-day grid; new code validates with
     *             {@link #validateBar(Integer, Integer)} (half-days). This whole-day check remains
     *             only for the FOR-05-10 day-based save path until the service rewrite (task 4.2).
     */
    @Deprecated
    public static boolean validateBarDays(Integer startDay, Integer durationDays) {
        if (startDay == null && durationDays == null) {
            return true; // clear the bar
        }
        if (startDay == null || durationDays == null) {
            return false; // half-null is never valid
        }
        if (startDay < 1 || startDay > MAX_DAY || durationDays < 1 || durationDays > MAX_DAY) {
            return false;
        }
        return finishDay(startDay, durationDays) <= MAX_DAY;
    }

    // ------------------------------------------------------------------------------------------
    // Half-day grid (FOR-05-10b R3.3, R3.4, R3.5; design §"Half-day grid")
    //   Bars are stored as integer half-day counts where 1 half-day = 0.5 day. The API/UI present
    //   days as 0.5-multiples derived from those counts.
    // ------------------------------------------------------------------------------------------

    /**
     * The start day of a bar from its half-day count (FOR-05-10b R3.3): {@code startHalfDays / 2.0}.
     * A {@code startHalfDays} of 1 is day 0.5, of 2 is day 1.0, and so on — always a 0.5-multiple.
     *
     * @param startHalfDays the bar's start in half-days ({@code >= 1})
     * @return the start day as a 0.5-multiple
     */
    public static double startDayFromHalfDays(int startHalfDays) {
        return startHalfDays / 2.0;
    }

    /**
     * The duration in days of a bar from its half-day count (FOR-05-10b R3.3):
     * {@code durationHalfDays / 2.0}. Always a 0.5-multiple.
     *
     * @param durationHalfDays the bar's duration in half-days ({@code >= 1})
     * @return the duration in days as a 0.5-multiple
     */
    public static double durationDaysFromHalfDays(int durationHalfDays) {
        return durationHalfDays / 2.0;
    }

    /**
     * The inclusive finish day of a bar from its half-day counts (FOR-05-10b R3.3):
     * {@code (startHalfDays + durationHalfDays - 1) / 2.0}. Mirrors the whole-day
     * {@link #finishDay(int, int)} formula on the half-day grid, so a one-half-day bar starting at
     * half-day {@code s} finishes at day {@code s / 2.0}.
     *
     * @param startHalfDays    the bar's start in half-days ({@code >= 1})
     * @param durationHalfDays the bar's duration in half-days ({@code >= 1})
     * @return the inclusive finish day as a 0.5-multiple
     */
    public static double finishDayFromHalfDays(int startHalfDays, int durationHalfDays) {
        return (startHalfDays + durationHalfDays - 1) / 2.0;
    }

    /**
     * The half-day finish offset of a bar: {@code startHalfDays + durationHalfDays - 1}, the
     * integer half-day index of the bar's last covered half-day. This is the value the horizon rule
     * (R3.4) compares against {@value #MAX_HALF_DAY}.
     *
     * @param startHalfDays    the bar's start in half-days
     * @param durationHalfDays the bar's duration in half-days
     * @return the inclusive half-day finish offset
     */
    public static int finishHalfDay(int startHalfDays, int durationHalfDays) {
        return startHalfDays + durationHalfDays - 1;
    }

    /**
     * The calendar day a half-day finish offset lands on (FOR-05-10b R3.3): the whole-day
     * {@code ceil(finishHalfDay / 2.0)}. Half-day {@code 1} and {@code 2} both finish on calendar
     * day 1, half-day {@code 3} and {@code 4} on day 2, and so on — the first and second half of a
     * calendar day map onto that day. Used to turn the schedule's latest half-day finish into a
     * {@link LocalDate} and to compare against the project end date, both of which are whole
     * calendar days.
     *
     * @param finishHalfDay the inclusive half-day finish offset ({@code >= 1})
     * @return the whole calendar day the half-day finish falls on
     */
    public static int calendarDayOfHalfDay(int finishHalfDay) {
        return (finishHalfDay + 1) / 2; // ceil(finishHalfDay / 2) for a positive offset
    }

    /**
     * The schedule {@code Finish_Day} on the half-day grid (FOR-05-10b R3.3): the largest bar finish
     * day over all bars as a 0.5-multiple, or {@code null} when no bar is scheduled. Each argument is
     * a bar's inclusive half-day finish offset ({@code startHalfDays + durationHalfDays - 1}); the
     * result is {@code max(finishHalfDays) / 2.0}.
     *
     * @param finishHalfDays the inclusive half-day finish offsets of the scheduled bars; may be empty
     * @return the latest finish day as a 0.5-multiple, or {@code null} when the input is empty
     */
    public static Double scheduleFinishDayFromHalfDays(Collection<Integer> finishHalfDays) {
        Integer max = maxHalfDay(finishHalfDays);
        return max == null ? null : max / 2.0;
    }

    /**
     * The schedule finish date from the bars' half-day finish offsets (FOR-05-10b R3.3): the anchor
     * plus {@code calendarDayOfHalfDay(max) - 1} days, or {@code null} when either the anchor is
     * absent or no bar is scheduled. A bar finishing in the first half of a calendar day still
     * occupies that whole calendar day, so the finish date uses the {@link #calendarDayOfHalfDay(int)}
     * ceiling.
     *
     * @param anchor         the {@code Schedule_Anchor} ({@code Project.startDate}); may be {@code null}
     * @param finishHalfDays the inclusive half-day finish offsets of the scheduled bars; may be empty
     * @return the finish date, or {@code null} when there is no anchor or no bar
     */
    public static LocalDate finishDateFromHalfDays(LocalDate anchor, Collection<Integer> finishHalfDays) {
        Integer max = maxHalfDay(finishHalfDays);
        if (anchor == null || max == null) {
            return null;
        }
        return anchor.plusDays(calendarDayOfHalfDay(max) - 1L);
    }

    /**
     * Whether the half-day schedule overruns the project end date (FOR-05-10b R3.3, R5.5): {@code true}
     * iff the anchor, the project end date, and at least one bar all exist and the resulting
     * {@link #finishDateFromHalfDays(LocalDate, Collection)} is strictly after the end date.
     *
     * @param anchor         the {@code Schedule_Anchor}; may be {@code null}
     * @param endDate        {@code Project.endDate}; may be {@code null}
     * @param finishHalfDays the inclusive half-day finish offsets of the scheduled bars; may be empty
     * @return {@code true} only when all three exist and the finish date is after the end date
     */
    public static boolean exceedsProjectEndHalfDays(
            LocalDate anchor, LocalDate endDate, Collection<Integer> finishHalfDays) {
        if (anchor == null || endDate == null) {
            return false;
        }
        LocalDate finish = finishDateFromHalfDays(anchor, finishHalfDays);
        return finish != null && finish.isAfter(endDate);
    }

    /** The maximum half-day finish offset in {@code finishHalfDays}, or {@code null} when empty. */
    private static Integer maxHalfDay(Collection<Integer> finishHalfDays) {
        if (finishHalfDays == null || finishHalfDays.isEmpty()) {
            return null;
        }
        int max = Integer.MIN_VALUE;
        boolean any = false;
        for (Integer finish : finishHalfDays) {
            if (finish != null) {
                max = Math.max(max, finish);
                any = true;
            }
        }
        return any ? max : null;
    }

    /**
     * Whether a bar's {@code (startHalfDays, durationHalfDays)} pair is a valid save on the half-day
     * grid (FOR-05-10b R3.4, R3.5): either both {@code null} (clear the bar) or both present and
     * {@code >= 1} with a half-day {@code Finish_Day} of at most {@value #MAX_HALF_DAY}. A half-null
     * pair, a value below 1, or a finish beyond the horizon is rejected.
     *
     * @param startHalfDays    the entry's start in half-days; {@code null} only when
     *                         {@code durationHalfDays} is too
     * @param durationHalfDays the entry's duration in half-days; {@code null} only when
     *                         {@code startHalfDays} is too
     * @return {@code true} if the pair is a valid clear or a valid in-horizon bar
     */
    public static boolean validateBar(Integer startHalfDays, Integer durationHalfDays) {
        if (startHalfDays == null && durationHalfDays == null) {
            return true; // clear the bar
        }
        if (startHalfDays == null || durationHalfDays == null) {
            return false; // half-null is never valid
        }
        if (startHalfDays < 1 || durationHalfDays < 1) {
            return false;
        }
        return finishHalfDay(startHalfDays, durationHalfDays) <= MAX_HALF_DAY;
    }

    /**
     * Converts a day value on the 0.5 grid to its integer half-day count (FOR-05-10b R6, D-storage):
     * {@code round(day × 2)}, but only when {@code day} is an exact multiple of 0.5 — otherwise
     * {@code null} (off-grid, rejected by the caller with {@code error.schedule.bar.invalid}). A
     * {@code null} day stays {@code null} so a half-null / clear pair is preserved for the caller's
     * both-null detection. Because {@code startDay} / {@code durationDays} arrive as doubles from the
     * frontend {@code dragCascade}, a small floating-point tolerance ({@value #HALF_DAY_EPSILON}) is
     * allowed around a 0.5 multiple before a value is treated as off-grid.
     *
     * @param day a day value on the 0.5 grid, or {@code null}
     * @return the half-day count ({@code round(day × 2)}), or {@code null} when {@code day} is
     *         {@code null} or not an exact 0.5-multiple
     */
    public static Integer halfDaysFromDay(Double day) {
        if (day == null) {
            return null;
        }
        double scaled = day * 2.0;
        long rounded = Math.round(scaled);
        if (Math.abs(scaled - rounded) > HALF_DAY_EPSILON) {
            return null; // off the 0.5-day grid
        }
        return (int) rounded;
    }

    /** Floating-point tolerance for recognizing a 0.5-day-grid value coming from the frontend. */
    private static final double HALF_DAY_EPSILON = 1e-6;
}
