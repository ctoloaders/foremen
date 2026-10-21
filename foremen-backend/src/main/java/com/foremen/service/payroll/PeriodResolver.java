package com.foremen.service.payroll;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * FOR-06-11 (design §PeriodResolver) — the pure, stateless quick-select date resolver for the payout
 * create dialog. It maps each {@link QuickSelect} option to a {@code [from, to]} {@link DateRange}
 * given a reference {@code today}, the project's start date, and the worker's latest payout
 * {@code periodTo} (Requirements 5.1, 5.2, 5.4).
 *
 * <p>All members are {@code static} and side-effect free — the resolver reads the four supplied dates
 * and computes; it touches no persistence and no clock, so the date math is directly jqwik-testable
 * without a Spring context (mirrors the FOR-06-10
 * {@link com.foremen.service.workercost.WorkerCostDerivation} pure-helper shape).
 *
 * <p>The resolved interval is only a <strong>pre-fill</strong>: the issuer may still adjust it
 * manually (Requirement 5.2). This class never persists the result.
 */
public final class PeriodResolver {

    private PeriodResolver() {
        // pure static helper, never instantiated
    }

    /**
     * The five period quick-select options offered by the create dialog (Requirement 5.1).
     *
     * <ul>
     *   <li>{@link #CURRENT_MONTH} / {@link #PREVIOUS_MONTH} — the respective calendar-month bounds;
     *   <li>{@link #SINCE_LAST_PAYOUT} — from the worker's latest payout {@code periodTo} (or the
     *       project start when the worker has no prior payout) through {@code today};
     *   <li>{@link #SINCE_PROJECT_START} — from the project start through {@code today};
     *   <li>{@link #ADVANCE} — a forward-looking interval whose {@code periodTo} is strictly after
     *       {@code today}, so the derived {@code isAdvance} (periodTo &gt; creation date) holds
     *       (Requirement 5.4).
     * </ul>
     */
    public enum QuickSelect {
        CURRENT_MONTH,
        PREVIOUS_MONTH,
        SINCE_LAST_PAYOUT,
        SINCE_PROJECT_START,
        ADVANCE
    }

    /**
     * Resolve a quick-select option to its {@code [from, to]} interval (Requirement 5.1).
     *
     * <ul>
     *   <li><b>CURRENT_MONTH</b> — {@code [firstDayOfMonth(today), lastDayOfMonth(today)]}.</li>
     *   <li><b>PREVIOUS_MONTH</b> — the full calendar month immediately before {@code today}'s month:
     *       {@code [firstDayOfPrevMonth, lastDayOfPrevMonth]}.</li>
     *   <li><b>SINCE_LAST_PAYOUT</b> — {@code [lastPayoutPeriodTo, today]} when a prior payout exists,
     *       else {@code [projectStart, today]} (Requirement 5.1).</li>
     *   <li><b>SINCE_PROJECT_START</b> — {@code [projectStart, today]}.</li>
     *   <li><b>ADVANCE</b> — a forward-looking interval {@code [today, lastDayOfMonth(today)]} whose
     *       {@code to} is strictly after {@code today}; WHEN {@code today} is already the last day of
     *       its month the end rolls to the last day of the next month so {@code periodTo} is still
     *       strictly after {@code today} and {@code isAdvance} holds (Requirement 5.4).</li>
     * </ul>
     *
     * @param option               the chosen quick-select; never {@code null}
     * @param today                the reference date (the payout's creation date); never {@code null}
     * @param projectStart         the project's start date; never {@code null}
     * @param lastPayoutPeriodTo   the worker's latest payout {@code periodTo}, or {@code null} when the
     *                             worker has no prior payout on the project
     * @return the resolved inclusive {@code [from, to]} interval
     */
    public static DateRange resolve(QuickSelect option,
                                    LocalDate today,
                                    LocalDate projectStart,
                                    LocalDate lastPayoutPeriodTo) {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(projectStart, "projectStart");

        return switch (option) {
            case CURRENT_MONTH -> new DateRange(
                    today.with(TemporalAdjusters.firstDayOfMonth()),
                    today.with(TemporalAdjusters.lastDayOfMonth()));

            case PREVIOUS_MONTH -> {
                LocalDate prev = today.minusMonths(1);
                yield new DateRange(
                        prev.with(TemporalAdjusters.firstDayOfMonth()),
                        prev.with(TemporalAdjusters.lastDayOfMonth()));
            }

            case SINCE_LAST_PAYOUT -> new DateRange(
                    lastPayoutPeriodTo != null ? lastPayoutPeriodTo : projectStart,
                    today);

            case SINCE_PROJECT_START -> new DateRange(projectStart, today);

            case ADVANCE -> {
                LocalDate endOfMonth = today.with(TemporalAdjusters.lastDayOfMonth());
                // A forward-looking interval: periodTo MUST be strictly after today so the derived
                // isAdvance (periodTo > creation date) holds (R5.4). When today is already the last
                // day of its month, roll the end into the next month.
                LocalDate periodTo = endOfMonth.isAfter(today)
                        ? endOfMonth
                        : today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
                yield new DateRange(today, periodTo);
            }
        };
    }
}
