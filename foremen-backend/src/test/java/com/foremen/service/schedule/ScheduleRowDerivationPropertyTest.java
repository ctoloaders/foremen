package com.foremen.service.schedule;

import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.schedule.ScheduleRowDerivation.RowSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link ScheduleRowDerivation#deriveRows} — the pure derivation of a
 * project's {@code Schedule_Row} skeleton from its estimate lines (FOR-05-10, Requirement 4;
 * design §"ScheduleRowDerivation", Property 1).
 *
 * <p>The helper is exercised directly as a pure function — no persistence, no Spring context — so
 * Property 1 is cheap to run over 100+ iterations.
 *
 * <p>Property 1 (row derivation): for every estimate, the rows are exactly the distinct categories
 * of its lines, each appearing once, ordered by {@code (orderNo ASC NULLS LAST, id ASC)}; the row
 * line-counts sum to the number of lines; and the summed {@code categoryValue} equals the summed
 * line {@code valueNet} (a {@code null} line value counted as zero).
 *
 * <p>Feature: FOR-05-10-project-gantt, Property 1: Row derivation
 *
 * <p><b>Validates: Requirements 4.1, 4.2</b>
 */
@Tag("Feature: FOR-05-10-project-gantt, Property 1: Row derivation")
class ScheduleRowDerivationPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 1a: exactly one row per distinct category of the lines (R4.1)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-10-project-gantt, Property 1: Row derivation")
    void oneRowPerDistinctCategory(@ForAll("estimateLines") List<EstimateLineEntity> lines) {
        List<RowSource> rows = ScheduleRowDerivation.deriveRows(lines);

        List<Long> distinctCategoryIds = lines.stream()
                .map(line -> line.getWorkItem().getWorkCategory().getId())
                .distinct()
                .sorted()
                .toList();

        List<Long> rowCategoryIds = rows.stream()
                .map(row -> row.category().getId())
                .sorted()
                .toList();

        // No category appears twice as a row.
        assertThat(rowCategoryIds).doesNotHaveDuplicates();
        // The rows cover exactly the distinct categories present in the lines.
        assertThat(rowCategoryIds).isEqualTo(distinctCategoryIds);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1b: rows ordered by (orderNo ASC NULLS LAST, id ASC) (R4.2)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-10-project-gantt, Property 1: Row derivation")
    void rowsOrderedByOrderNoThenId(@ForAll("estimateLines") List<EstimateLineEntity> lines) {
        List<RowSource> rows = ScheduleRowDerivation.deriveRows(lines);

        List<WorkCategoryEntity> actualOrder = rows.stream().map(RowSource::category).toList();

        List<WorkCategoryEntity> expectedOrder = new ArrayList<>(actualOrder);
        expectedOrder.sort(
                Comparator.<WorkCategoryEntity, Integer>comparing(
                                WorkCategoryEntity::getOrderNo,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(WorkCategoryEntity::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())));

        assertThat(actualOrder).containsExactlyElementsOf(expectedOrder);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1c: row line-counts sum to the number of input lines (R4.1)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-10-project-gantt, Property 1: Row derivation")
    void lineCountsArePreserved(@ForAll("estimateLines") List<EstimateLineEntity> lines) {
        List<RowSource> rows = ScheduleRowDerivation.deriveRows(lines);

        int totalLineCount = rows.stream().mapToInt(RowSource::lineCount).sum();
        int totalLinesInRows = rows.stream().mapToInt(row -> row.lines().size()).sum();

        assertThat(totalLineCount).isEqualTo(lines.size());
        assertThat(totalLinesInRows).isEqualTo(lines.size());
    }

    // ------------------------------------------------------------------------------------------
    // Property 1d: Σ categoryValue = Σ line.valueNet, null line value counted as zero (R4.1)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-10-project-gantt, Property 1: Row derivation")
    void valueSumsArePreserved(@ForAll("estimateLines") List<EstimateLineEntity> lines) {
        List<RowSource> rows = ScheduleRowDerivation.deriveRows(lines);

        BigDecimal expectedTotal = lines.stream()
                .map(line -> line.getValueNet() == null ? BigDecimal.ZERO : line.getValueNet())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal actualTotal = rows.stream()
                .map(RowSource::categoryValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(actualTotal).isEqualByComparingTo(expectedTotal);

        // Each row's categoryValue equals the sum of its own lines' values (null -> 0),
        // so the per-row aggregate is faithful, not just the grand total.
        for (RowSource row : rows) {
            BigDecimal expectedRowValue = row.lines().stream()
                    .map(line -> line.getValueNet() == null ? BigDecimal.ZERO : line.getValueNet())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(row.categoryValue()).isEqualByComparingTo(expectedRowValue);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Builds an arbitrary estimate-line graph: a small pool of distinct work categories, then a
     * list of lines each attached (via its own work item) to one of those categories. Each line
     * carries a possibly-null {@code valueNet}. The category pool uses unique ids (so JPA entity
     * equality groups them correctly) with arbitrary — possibly duplicate, possibly null —
     * {@code orderNo}s to exercise the {@code NULLS LAST} ordering and the id tie-break.
     */
    @Provide
    Arbitrary<List<EstimateLineEntity>> estimateLines() {
        return categoryPool().flatMap(categories -> {
            Arbitrary<EstimateLineEntity> line = Combinators.combine(
                            Arbitraries.integers().between(0, categories.size() - 1),
                            lineValues(),
                            Arbitraries.integers().between(1, 500).injectNull(0.2))
                    .as((categoryIndex, value, lineNo) ->
                            buildLine(categories.get(categoryIndex), value, lineNo));
            return line.list().ofMinSize(0).ofMaxSize(40);
        });
    }

    /**
     * A pool of 1..6 distinct categories with unique ids and arbitrary (possibly duplicate,
     * possibly null) order numbers.
     */
    @Provide
    Arbitrary<List<WorkCategoryEntity>> categoryPool() {
        Arbitrary<Integer> orderNo = Arbitraries.integers().between(1, 10).injectNull(0.25);
        return orderNo.list().ofMinSize(1).ofMaxSize(6).map(orderNos -> {
            List<WorkCategoryEntity> categories = new ArrayList<>(orderNos.size());
            long id = 1L;
            for (Integer order : orderNos) {
                categories.add(buildCategory(id, order));
                id++;
            }
            return categories;
        });
    }

    /** Net line values, including {@code null} (an unpriced line counted as zero). */
    @Provide
    Arbitrary<BigDecimal> lineValues() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("1000000.00"))
                .ofScale(2)
                .injectNull(0.15);
    }

    private static WorkCategoryEntity buildCategory(long id, Integer orderNo) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(id);
        category.setCode("CAT-" + id);
        category.setOrderNo(orderNo);
        category.setNameRU("Категория " + id);
        category.setNamePL("Kategoria " + id);
        category.setActive(true);
        return category;
    }

    private static EstimateLineEntity buildLine(WorkCategoryEntity category, BigDecimal valueNet, Integer lineNo) {
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setWorkCategory(category);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(nextLineId());
        line.setWorkItem(workItem);
        line.setLineNo(lineNo);
        line.setValueNet(valueNet);
        return line;
    }

    /**
     * Monotonically increasing line ids so each generated line is a distinct JPA entity and the
     * {@code (lineNo, id)} intra-category ordering has a defined tie-break. The counter only needs
     * to be unique within a single property invocation's line list; a process-wide counter is
     * sufficient and simplest.
     */
    private static long lineIdCounter = 0L;

    private static synchronized long nextLineId() {
        return ++lineIdCounter;
    }
}
