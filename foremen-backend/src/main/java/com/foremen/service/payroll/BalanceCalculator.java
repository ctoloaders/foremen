package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Pure monthly balance chain for the company payroll (FOR-07-06, Requirements 9.3, 9.4, 11.2;
 * design §BalanceCalculator, Property 7).
 *
 * <p>{@code b(M) = b(M−1) + earned(M) − paid(M)}, with {@code b(before first activity) = opening}.
 * Equivalently {@code b(M) = opening + Σ earned(≤ M) − Σ paid(≤ M)} — the chain is never closed or
 * reset, and months before the first requested month still count (so the first card block's
 * previous balance is the balance at the end of the month before {@code from}).
 *
 * <p>Computed on read; nothing is persisted (R9.4). Amounts are scale 2, {@link RoundingMode#HALF_UP};
 * {@code null} amounts/maps/opening count as 0. The result may be negative.
 *
 * <p>No Spring, no clock, no persistence — a deterministic function of its arguments.
 */
public final class BalanceCalculator {

    /** {@code NUMERIC(19,2)} scale, consistent with the pricing and FOR-06-10 cost stack. */
    static final int SCALE = 2;

    private BalanceCalculator() {
    }

    /**
     * Balance at the end of each requested month.
     *
     * @param opening       the worker's opening balance ({@code null} → 0)
     * @param earnedByMonth Earned per month (lines + additional lines); may contain earlier months
     * @param paidByMonth   Paid per month (payout amounts); may contain earlier months
     * @param months        the months to report ({@code null} → none); duplicates collapse
     * @return month → end-of-month balance, ascending by month
     */
    public static Map<YearMonth, BigDecimal> chain(BigDecimal opening,
                                                   Map<YearMonth, BigDecimal> earnedByMonth,
                                                   Map<YearMonth, BigDecimal> paidByMonth,
                                                   Collection<YearMonth> months) {
        Map<YearMonth, BigDecimal> result = new LinkedHashMap<>();
        if (months == null || months.isEmpty()) {
            return result;
        }
        TreeSet<YearMonth> requested = new TreeSet<>();
        for (YearMonth m : months) {
            requested.add(Objects.requireNonNull(m, "month"));
        }
        // Walk every month that carries activity or is requested, in order.
        TreeSet<YearMonth> timeline = new TreeSet<>(requested);
        if (earnedByMonth != null) {
            timeline.addAll(earnedByMonth.keySet());
        }
        if (paidByMonth != null) {
            timeline.addAll(paidByMonth.keySet());
        }
        YearMonth last = requested.last();
        BigDecimal balance = scaled(opening);
        for (YearMonth m : timeline) {
            if (m.isAfter(last)) {
                break;
            }
            balance = balance.add(amount(earnedByMonth, m)).subtract(amount(paidByMonth, m));
            if (requested.contains(m)) {
                result.put(m, balance);
            }
        }
        return result;
    }

    /** Balance at the end of {@code month} ({@code opening + Σ earned(≤ M) − Σ paid(≤ M)}). */
    public static BigDecimal balanceAt(BigDecimal opening,
                                       Map<YearMonth, BigDecimal> earnedByMonth,
                                       Map<YearMonth, BigDecimal> paidByMonth,
                                       YearMonth month) {
        Objects.requireNonNull(month, "month");
        return chain(opening, earnedByMonth, paidByMonth, List.of(month)).get(month);
    }

    private static BigDecimal amount(Map<YearMonth, BigDecimal> byMonth, YearMonth month) {
        return byMonth == null ? scaled(null) : scaled(byMonth.get(month));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE) : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
