package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-07-06 (task 2.3; design §Correctness properties, Property 8 "Month attribution") —
 * property-based tests for the pure {@link MonthAttribution}.
 *
 * <p>Each report line / payout / additional line falls in exactly one month (the month containing
 * its attribution date), per-month sums partition the total, and {@code monthsBetween} lists every
 * calendar month overlapping the range exactly once, ascending.
 *
 * <p><b>Validates: Requirements 10.4, 11.2</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Property 8: month attribution")
class MonthAttributionPropertyTest {

    private static final int SCALE = 2;

    record Item(LocalDate date, BigDecimal amount) {
    }

    private static Arbitrary<LocalDate> dates() {
        return Arbitraries.integers().between(0, 3 * 366)
                .map(d -> LocalDate.of(2024, 1, 1).plusDays(d));
    }

    @Provide
    Arbitrary<LocalDate> anyDate() {
        return dates();
    }

    @Provide
    Arbitrary<List<Item>> items() {
        return Combinators.combine(dates(),
                        Arbitraries.longs().between(-1_000_000, 1_000_000).map(c -> BigDecimal.valueOf(c, SCALE)))
                .as(Item::new).list().ofMaxSize(30);
    }

    @Property(tries = 300)
    void eachDateFallsInExactlyOneMonth(@ForAll("anyDate") LocalDate d) {
        YearMonth line = MonthAttribution.ofLine(d);
        assertThat(MonthAttribution.ofPayout(d)).isEqualTo(line);
        assertThat(MonthAttribution.ofAdditionalLine(d)).isEqualTo(line);
        assertThat(line.atDay(1)).isBeforeOrEqualTo(d);
        assertThat(line.atEndOfMonth()).isAfterOrEqualTo(d);
        // neighbours do not contain it
        assertThat(line.minusMonths(1).atEndOfMonth()).isBefore(d);
        assertThat(line.plusMonths(1).atDay(1)).isAfter(d);
    }

    @Property(tries = 300)
    void sumByMonthPartitionsTheItems(@ForAll("items") List<Item> items) {
        Map<YearMonth, BigDecimal> sums = MonthAttribution.sumByMonth(items,
                i -> MonthAttribution.ofPayout(i.date()), Item::amount);

        BigDecimal total = items.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal summed = sums.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isEqualByComparingTo(total);

        for (Map.Entry<YearMonth, BigDecimal> e : sums.entrySet()) {
            BigDecimal expected = items.stream()
                    .filter(i -> YearMonth.from(i.date()).equals(e.getKey()))
                    .map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(e.getValue()).isEqualByComparingTo(expected);
            assertThat(e.getValue().scale()).isEqualTo(SCALE);
        }
        assertThat(sums.keySet()).containsExactlyInAnyOrderElementsOf(
                items.stream().map(i -> YearMonth.from(i.date())).distinct().toList());
    }

    @Property(tries = 300)
    void monthsBetweenCoversEveryOverlappingMonthOnce(@ForAll("anyDate") LocalDate a, @ForAll("anyDate") LocalDate b) {
        LocalDate from = a.isBefore(b) ? a : b;
        LocalDate to = a.isBefore(b) ? b : a;
        List<YearMonth> months = MonthAttribution.monthsBetween(from, to);

        assertThat(months).doesNotHaveDuplicates().isSorted();
        assertThat(months.get(0)).isEqualTo(YearMonth.from(from));
        assertThat(months.get(months.size() - 1)).isEqualTo(YearMonth.from(to));
        for (int i = 1; i < months.size(); i++) {
            assertThat(months.get(i)).isEqualTo(months.get(i - 1).plusMonths(1));
        }
    }

    @Example
    void nullDatesAndInvertedRangeAreRejected() {
        assertThatThrownBy(() -> MonthAttribution.ofLine(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MonthAttribution.monthsBetween(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    void nullAmountCountsAsZero() {
        Map<YearMonth, BigDecimal> sums = MonthAttribution.sumByMonth(
                List.of(new Item(LocalDate.of(2025, 7, 31), null)),
                i -> MonthAttribution.ofLine(i.date()), Item::amount);
        assertThat(sums).containsExactly(Map.entry(YearMonth.of(2025, 7), BigDecimal.ZERO.setScale(SCALE)));
    }
}
