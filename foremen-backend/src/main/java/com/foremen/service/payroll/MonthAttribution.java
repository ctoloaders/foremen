package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Pure month attribution for the company payroll read model (FOR-07-06, Glossary "Month
 * attribution", Requirements 10.4, 11.2; design §MonthAttribution, Property 8).
 *
 * <ul>
 *   <li>a report line belongs to the month of its report's {@code performedTo};</li>
 *   <li>a payout belongs to the month of its {@code payoutDate};</li>
 *   <li>an additional payroll line belongs to its {@code month} (always the 1st of a month, but any
 *       day maps to its month).</li>
 * </ul>
 *
 * <p>Every item maps to <b>exactly one</b> {@link YearMonth}; a {@code null} date is a programming
 * error (all three columns are {@code NOT NULL}) and is rejected. Sums are scale 2,
 * {@link RoundingMode#HALF_UP}; {@code null} amounts count as 0 (unavailable costs are excluded).
 *
 * <p>No Spring, no clock, no persistence — a deterministic function of its arguments.
 */
public final class MonthAttribution {

    /** {@code NUMERIC(19,2)} scale, consistent with the pricing and FOR-06-10 cost stack. */
    static final int SCALE = 2;

    private MonthAttribution() {
    }

    /** Month of a report line: {@code YearMonth.from(report.performedTo)}. */
    public static YearMonth ofLine(LocalDate performedTo) {
        return YearMonth.from(Objects.requireNonNull(performedTo, "performedTo"));
    }

    /** Month of a payout: {@code YearMonth.from(payoutDate)}. */
    public static YearMonth ofPayout(LocalDate payoutDate) {
        return YearMonth.from(Objects.requireNonNull(payoutDate, "payoutDate"));
    }

    /** Month of an additional payroll line: {@code YearMonth.from(month)}. */
    public static YearMonth ofAdditionalLine(LocalDate month) {
        return YearMonth.from(Objects.requireNonNull(month, "month"));
    }

    /**
     * Every calendar month overlapping {@code [from, to]}, ascending (R11.2 month blocks).
     *
     * @throws IllegalArgumentException when {@code to < from}
     */
    public static List<YearMonth> monthsBetween(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            months.add(m);
        }
        return months;
    }

    /**
     * Sums {@code amountOf} per attributed month. Each item contributes to exactly one month.
     *
     * @param items   the items ({@code null} → empty)
     * @param monthOf the attribution (e.g. {@code r -> ofLine(r.performedTo())})
     * @param amountOf the amount ({@code null} → 0)
     * @return month → sum, sorted by month
     */
    public static <T> Map<YearMonth, BigDecimal> sumByMonth(Collection<T> items,
                                                           Function<T, YearMonth> monthOf,
                                                           Function<T, BigDecimal> amountOf) {
        Map<YearMonth, BigDecimal> sums = new TreeMap<>();
        if (items == null) {
            return sums;
        }
        for (T item : items) {
            YearMonth month = Objects.requireNonNull(monthOf.apply(item), "month");
            sums.merge(month, scaled(amountOf.apply(item)), BigDecimal::add);
        }
        return sums;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE) : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
