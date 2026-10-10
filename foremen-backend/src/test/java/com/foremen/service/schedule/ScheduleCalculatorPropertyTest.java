package com.foremen.service.schedule;

import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.schedule.ScheduleCalculator.BarLayout;
import com.foremen.service.schedule.ScheduleRowDerivation.RowSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the auto-create arithmetic of {@link ScheduleCalculator} —
 * {@link ScheduleCalculator#duration(BigDecimal, BigDecimal, int)} and
 * {@link ScheduleCalculator#autoLayout(java.util.Collection, BigDecimal, int)} (FOR-05-10,
 * Requirement 8; design §"ScheduleCalculator", Properties 2, 3, 4).
 *
 * <p>The calculator is a pure function — no persistence, no Spring context, no clock — so these
 * properties are cheap to run over 100+ iterations.
 *
 * <ul>
 *   <li><b>Property 2 (capacity):</b> for every row with {@code value v > 0}, {@code rate r > 0},
 *       {@code crew c >= 1}, the computed duration {@code d} satisfies
 *       {@code (d - 1) * r * c < v <= d * r * c}.</li>
 *   <li><b>Property 3 (zero value):</b> for {@code v = 0}, {@code d = 1}.</li>
 *   <li><b>Property 4 (contiguity + finish + determinism):</b> {@code autoLayout} lays bars out
 *       from day 1 with no gaps — the first bar starts at day 1 and each following bar starts at the
 *       previous bar's {@code Finish_Day + 1}; the schedule finish equals {@code Σ durationDays};
 *       and identical inputs produce identical bars.</li>
 * </ul>
 *
 * <p>Generators keep {@code rate}, {@code crew}, and {@code value} bounded so that the laid-out
 * finish day stays well within {@link ScheduleCalculator#MAX_DAY}, avoiding the
 * {@code error.schedule.too.long} path (that path is a separate concern of task 3.5 / the service
 * tests).
 *
 * <p>Feature: FOR-05-10-project-gantt, Properties 2-4: Auto-create
 *
 * <p><b>Validates: Requirements 8.2, 8.3, 8.4, 8.8</b>
 */
@Tag("Feature: FOR-05-10-project-gantt, Properties 2-4: Auto-create")
class ScheduleCalculatorPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 2: capacity inequality for a positive value (R8.2, R8.4)
    //   (d - 1) * rate * crew < value <= d * rate * crew
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10-project-gantt, Properties 2-4: Auto-create")
    void capacityInequalityForPositiveValue(
            @ForAll("positiveValues") BigDecimal value,
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 50) int crew) {

        int d = ScheduleCalculator.duration(value, rate, crew);

        // d is always at least one day.
        assertThat(d).isGreaterThanOrEqualTo(1);

        BigDecimal capacityPerDay = rate.multiply(BigDecimal.valueOf(crew));
        BigDecimal upperBound = capacityPerDay.multiply(BigDecimal.valueOf(d));
        BigDecimal lowerBound = capacityPerDay.multiply(BigDecimal.valueOf(d - 1L));

        // value <= d * rate * crew  (d days of capacity is enough)
        assertThat(value).isLessThanOrEqualTo(upperBound);
        // (d - 1) * rate * crew < value  (d - 1 days of capacity is NOT enough; d is minimal)
        assertThat(value).isGreaterThan(lowerBound);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3: a zero (or null) value yields a one-day duration (R8.2)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-10-project-gantt, Properties 2-4: Auto-create")
    void zeroValueYieldsOneDay(
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 50) int crew) {

        assertThat(ScheduleCalculator.duration(BigDecimal.ZERO, rate, crew)).isEqualTo(1);
        assertThat(ScheduleCalculator.duration(new BigDecimal("0.00"), rate, crew)).isEqualTo(1);
        // A null Category_Value is treated as zero, so it too floors to one day.
        assertThat(ScheduleCalculator.duration(null, rate, crew)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4a: autoLayout is contiguous from day 1 with no gaps, and finish = Σ durationDays
    // (R8.3)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10-project-gantt, Properties 2-4: Auto-create")
    void autoLayoutIsContiguousFromDayOne(
            @ForAll("boundedRows") List<RowSource> rows,
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 50) int crew) {

        List<BarLayout> bars = ScheduleCalculator.autoLayout(rows, rate, crew);

        // One bar per row, in the same order.
        assertThat(bars).hasSameSizeAs(rows);

        int expectedStart = 1;
        int sumOfDurations = 0;
        for (int i = 0; i < bars.size(); i++) {
            BarLayout bar = bars.get(i);

            // The bar belongs to its row's category, in input order.
            assertThat(bar.workCategoryId()).isEqualTo(rows.get(i).category().getId());

            // Each duration matches the row's independently computed Computed_Duration (>= 1).
            int expectedDuration =
                    ScheduleCalculator.duration(rows.get(i).categoryValue(), rate, crew);
            assertThat(bar.durationDays()).isEqualTo(expectedDuration);
            assertThat(bar.durationDays()).isGreaterThanOrEqualTo(1);

            // Contiguity: row 1 starts at day 1; each next starts at the previous finish + 1.
            assertThat(bar.startDay()).isEqualTo(expectedStart);

            int finish = ScheduleCalculator.finishDay(bar.startDay(), bar.durationDays());
            expectedStart = finish + 1;
            sumOfDurations += bar.durationDays();
        }

        // The schedule finish equals the sum of all durations (contiguous, from day 1, no gaps).
        Integer scheduleFinish = ScheduleCalculator.scheduleFinish(bars);
        if (bars.isEmpty()) {
            assertThat(scheduleFinish).isNull();
        } else {
            assertThat(scheduleFinish).isEqualTo(sumOfDurations);
            // ...and finish stays inside the hard length limit for these bounded generators.
            assertThat(scheduleFinish).isLessThanOrEqualTo(ScheduleCalculator.MAX_DAY);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: autoLayout is deterministic — identical inputs produce identical bars (R8.8)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10-project-gantt, Properties 2-4: Auto-create")
    void autoLayoutIsDeterministic(
            @ForAll("boundedRows") List<RowSource> rows,
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 50) int crew) {

        List<BarLayout> first = ScheduleCalculator.autoLayout(rows, rate, crew);
        List<BarLayout> second = ScheduleCalculator.autoLayout(rows, rate, crew);

        // BarLayout is a record, so element-wise equality is value equality.
        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Strictly positive net category values with a two-decimal scale, bounded so a laid-out row
     * stays short enough that even many rows keep the schedule finish well inside
     * {@link ScheduleCalculator#MAX_DAY}.
     */
    @Provide
    Arbitrary<BigDecimal> positiveValues() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000000.00"))
                .ofScale(2)
                .filter(v -> v.signum() > 0);
    }

    /**
     * Strictly positive {@code Daily_Output_Rate}s per worker per day, around the configured default
     * of 2000, with a two-decimal scale. Kept away from very small rates so a bounded value never
     * produces an unreasonably long single bar.
     */
    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("100.00"), new BigDecimal("10000.00"))
                .ofScale(2)
                .filter(r -> r.signum() > 0);
    }

    /**
     * A list of 0..30 rows with distinct category ids and bounded (possibly zero) category values.
     * The combination of bounded values, a rate of at least 100, and at most 30 rows keeps the
     * total laid-out finish day far below {@link ScheduleCalculator#MAX_DAY}, so {@code autoLayout}
     * never takes the {@code error.schedule.too.long} path here.
     */
    @Provide
    Arbitrary<List<RowSource>> boundedRows() {
        Arbitrary<BigDecimal> rowValues = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("50000.00"))
                .ofScale(2);
        return rowValues.list().ofMinSize(0).ofMaxSize(30).map(values -> {
            List<RowSource> rows = new ArrayList<>(values.size());
            long id = 1L;
            for (BigDecimal value : values) {
                rows.add(buildRow(id, value));
                id++;
            }
            return rows;
        });
    }

    /**
     * Builds a minimal {@link RowSource} carrying only the two fields the calculator reads: the
     * category (for its id) and the {@code categoryValue}. {@code lineCount} and {@code lines} are
     * irrelevant to {@link ScheduleCalculator} and left at harmless defaults.
     */
    private static RowSource buildRow(long categoryId, BigDecimal categoryValue) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(categoryId);
        category.setCode("CAT-" + categoryId);
        category.setOrderNo((int) categoryId);
        category.setNameRU("Категория " + categoryId);
        category.setNamePL("Kategoria " + categoryId);
        category.setActive(true);
        return new RowSource(category, 1, categoryValue, List.of());
    }
}
