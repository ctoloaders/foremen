package com.foremen.service.payroll;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * FOR-06-11 / FOR-07-06 (design §PeriodResolver, §WorkerPayoutService) — the pure, stateless
 * quick-select date resolver for the payout create dialog. It maps each {@link QuickSelect} option to
 * a {@code [from, to]} {@link DateRange} given a reference {@code today}, the worker's latest payout
 * {@code periodTo}, and the {@code performedFrom} of the worker's earliest unpaid report line.
 *
 * <p>FOR-07-06 makes payouts worker-wide, so the project-bound {@code SINCE_PROJECT_START} option is
 * removed and {@code SINCE_LAST_PAYOUT} is resolved over <b>all</b> the worker's payouts.
 *
 * <p>All members are {@code static} and side-effect free; the resolved interval is only a
 * <strong>pre-fill</strong> the issuer may still adjust. Nothing is persisted.
 */
public final class PeriodResolver {

    private PeriodResolver() {
        // pure static helper, never instantiated
    }

    /**
     * The period quick-select options offered by the create dialog.
     *
     * <ul>
     *   <li>{@link #CURRENT_MONTH} / {@link #PREVIOUS_MONTH} — the respective calendar-month bounds;
     *   <li>{@link #SINCE_LAST_PAYOUT} — from the worker's latest payout {@code periodTo} (fallback:
     *       the earliest unpaid line's {@code performedFrom}, else {@code today}) through
     *       {@code today};
     *   <li>{@link #ADVANCE} — a forward-looking interval whose {@code periodTo} is strictly after
     *       {@code today}, so the derived {@code isAdvance} holds.
     * </ul>
     */
    public enum QuickSelect {
        CURRENT_MONTH,
        PREVIOUS_MONTH,
        SINCE_LAST_PAYOUT,
        ADVANCE
    }

    /**
     * Resolve a quick-select option to its {@code [from, to]} interval.
     *
     * @param option              the chosen quick-select; never {@code null}
     * @param today               the reference date (the payout's creation date); never {@code null}
     * @param lastPayoutPeriodTo  the worker's latest payout {@code periodTo} over all their payouts,
     *                            or {@code null} when the worker has no prior payout
     * @param earliestUnpaidFrom  the {@code performedFrom} of the worker's earliest unpaid
     *                            {@code APPROVED} line, or {@code null} when there is none
     * @return the resolved inclusive {@code [from, to]} interval
     */
    public static DateRange resolve(QuickSelect option,
                                    LocalDate today,
                                    LocalDate lastPayoutPeriodTo,
                                    LocalDate earliestUnpaidFrom) {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(today, "today");

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

            case SINCE_LAST_PAYOUT -> {
                LocalDate from = lastPayoutPeriodTo != null ? lastPayoutPeriodTo
                        : earliestUnpaidFrom != null ? earliestUnpaidFrom
                        : today;
                // Never produce an inverted range: a from after today collapses to today.
                yield new DateRange(from.isAfter(today) ? today : from, today);
            }

            case ADVANCE -> {
                LocalDate endOfMonth = today.with(TemporalAdjusters.lastDayOfMonth());
                // periodTo MUST be strictly after today so the derived isAdvance holds. When today is
                // already the last day of its month, roll the end into the next month.
                LocalDate periodTo = endOfMonth.isAfter(today)
                        ? endOfMonth
                        : today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
                yield new DateRange(today, periodTo);
            }
        };
    }
}
