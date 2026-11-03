package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-07-06 (task 2.3; design §Correctness properties, Property 7 "Balance chain") —
 * property-based tests for the pure {@link BalanceCalculator}.
 *
 * <p>For any opening balance and any per-month Earned / Paid maps (possibly with activity before the
 * requested window): {@code b(M) = b(M−1) + earned(M) − paid(M)} for consecutive months,
 * {@code b(start) = opening + Σ earned(≤ start) − Σ paid(≤ start)} (so with no earlier activity the
 * chain starts from the opening balance), and the last month's balance equals the single-month
 * {@code balanceAt} (card last block == table {@code balanceAtTo}).
 *
 * <p><b>Validates: Requirements 9.3, 9.4, 11.2</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Property 7: balance chain")
class BalanceCalculatorPropertyTest {

    private static final int SCALE = 2;
    private static final YearMonth BASE = YearMonth.of(2025, 1);

    record Scenario(BigDecimal opening, Map<YearMonth, BigDecimal> earned, Map<YearMonth, BigDecimal> paid,
                    YearMonth start, int length) {
        List<YearMonth> months() {
            List<YearMonth> out = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                out.add(start.plusMonths(i));
            }
            return out;
        }
    }

    /** A signed cent amount in [-10 000.00, 10 000.00] (additional lines may be negative). */
    private static Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(-1_000_000, 1_000_000).map(c -> BigDecimal.valueOf(c, SCALE));
    }

    private static Arbitrary<Map<YearMonth, BigDecimal>> byMonth() {
        return Arbitraries.maps(Arbitraries.integers().between(0, 23).map(BASE::plusMonths), money())
                .ofMaxSize(10).map(TreeMap::new);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(money(), byMonth(), byMonth(),
                        Arbitraries.integers().between(0, 23), Arbitraries.integers().between(1, 12))
                .as((o, e, p, s, n) -> new Scenario(o, e, p, BASE.plusMonths(s), n));
    }

    private static BigDecimal get(Map<YearMonth, BigDecimal> m, YearMonth k) {
        return m.getOrDefault(k, BigDecimal.ZERO.setScale(SCALE));
    }

    @Property(tries = 300)
    void chainSatisfiesRecurrenceAndStartsFromOpeningPlusPriorActivity(@ForAll("scenarios") Scenario s) {
        List<YearMonth> months = s.months();
        Map<YearMonth, BigDecimal> chain = BalanceCalculator.chain(s.opening(), s.earned(), s.paid(), months);

        assertThat(chain.keySet()).containsExactlyElementsOf(months);
        chain.values().forEach(v -> assertThat(v.scale()).isEqualTo(SCALE));

        // b(start) = opening + Σ earned(≤ start) − Σ paid(≤ start)
        BigDecimal expectedStart = s.opening();
        for (Map.Entry<YearMonth, BigDecimal> e : s.earned().entrySet()) {
            if (!e.getKey().isAfter(s.start())) {
                expectedStart = expectedStart.add(e.getValue());
            }
        }
        for (Map.Entry<YearMonth, BigDecimal> e : s.paid().entrySet()) {
            if (!e.getKey().isAfter(s.start())) {
                expectedStart = expectedStart.subtract(e.getValue());
            }
        }
        assertThat(chain.get(s.start())).isEqualByComparingTo(expectedStart);

        // b(M) = b(M−1) + earned(M) − paid(M)
        for (int i = 1; i < months.size(); i++) {
            YearMonth m = months.get(i);
            BigDecimal expected = chain.get(m.minusMonths(1)).add(get(s.earned(), m)).subtract(get(s.paid(), m));
            assertThat(chain.get(m)).isEqualByComparingTo(expected);
        }

        // card's last block balance == table's balanceAtTo
        YearMonth last = months.get(months.size() - 1);
        assertThat(BalanceCalculator.balanceAt(s.opening(), s.earned(), s.paid(), last))
                .isEqualByComparingTo(chain.get(last));
    }

    @Property(tries = 200)
    void withNoPriorActivityTheMonthBeforeStartEqualsOpening(@ForAll("scenarios") Scenario s) {
        YearMonth before = BASE.minusMonths(1);
        assertThat(BalanceCalculator.balanceAt(s.opening(), s.earned(), s.paid(), before))
                .isEqualByComparingTo(s.opening());
    }

    @Example
    void nullInputsCountAsZero() {
        YearMonth m = YearMonth.of(2025, 7);
        assertThat(BalanceCalculator.chain(null, null, null, List.of(m)))
                .containsExactly(Map.entry(m, BigDecimal.ZERO.setScale(SCALE)));
        assertThat(BalanceCalculator.chain(BigDecimal.ONE, null, null, null)).isEmpty();
    }

    @Example
    void workbookLikeExample() {
        YearMonth jun = YearMonth.of(2025, 6);
        YearMonth jul = YearMonth.of(2025, 7);
        Map<YearMonth, BigDecimal> chain = BalanceCalculator.chain(new BigDecimal("100"),
                Map.of(jun, new BigDecimal("5000"), jul, new BigDecimal("3000")),
                Map.of(jun, new BigDecimal("4000"), jul, new BigDecimal("4500")),
                List.of(jun, jul));
        assertThat(chain.get(jun)).isEqualByComparingTo("1100");
        assertThat(chain.get(jul)).isEqualByComparingTo("-400");
    }
}
