package com.foremen.service.schedule;

import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.WorkCategoryEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure derivation of the {@code Schedule_Row} skeleton of a project's planning Gantt from its
 * estimate lines (FOR-05-10 Requirement 4). This is a <strong>pure</strong> helper: it holds no
 * state, touches no database or Spring context, and its result depends only on its argument, so it
 * is directly unit- and property-testable (task 3.2).
 *
 * <p>{@link #deriveRows(Collection)} groups the estimate lines by their work item's
 * {@link WorkCategoryEntity}, producing exactly one {@link RowSource} per distinct category
 * (R4.1). For each category it reports:
 * <ul>
 *   <li>the {@code lineCount} — the number of estimate lines in the category;</li>
 *   <li>the {@code categoryValue} — the sum of the lines' {@code valueNet}, treating a {@code null}
 *       line value as {@link BigDecimal#ZERO};</li>
 *   <li>the {@code lines} — the category's estimate lines sorted by {@code lineNo} ascending (with
 *       a {@code null} {@code lineNo} sorting last) and then by id ascending.</li>
 * </ul>
 *
 * <p>Rows are ordered by {@code (WorkCategory.orderNo ASC NULLS LAST, WorkCategory.id ASC)} (R4.2);
 * the caller never supplies a row order. A category is kept as a row regardless of its
 * {@code active} flag, as long as the estimate has at least one line in it (R4.7). An empty input —
 * no estimate or no lines — yields an empty list (R4.3).
 *
 * <p>Monetary masking, localized names, bar data, and Suggested_Duration are <em>not</em> the
 * concern of this helper: it only shapes the raw aggregate the service later maps onto
 * {@code ScheduleRowView}.
 */
public final class ScheduleRowDerivation {

    private ScheduleRowDerivation() {
    }

    /**
     * The raw per-category aggregate feeding a {@code ScheduleRowView}: the owning
     * {@link WorkCategoryEntity}, the number of estimate lines in it, the summed net value
     * ({@code null} line values counted as zero), and the category's estimate lines sorted by
     * {@code lineNo} then id.
     *
     * @param category     the work category the row represents (never {@code null})
     * @param lineCount    the number of estimate lines in the category ({@code >= 1})
     * @param categoryValue the sum of the lines' net values; never {@code null}, never
     *                      computed from a {@code null} line value (those are treated as zero)
     * @param lines        the category's estimate lines, sorted by {@code lineNo} then id;
     *                      an immutable snapshot
     */
    public record RowSource(
            WorkCategoryEntity category,
            int lineCount,
            BigDecimal categoryValue,
            List<EstimateLineEntity> lines
    ) {}

    /**
     * Sorts estimate lines by {@code lineNo} ascending (a {@code null} {@code lineNo} sorts last)
     * and then by id ascending (a {@code null} id sorts last).
     */
    private static final Comparator<EstimateLineEntity> LINE_ORDER =
            Comparator.<EstimateLineEntity, Integer>comparing(
                            EstimateLineEntity::getLineNo,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(EstimateLineEntity::getId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Orders rows by {@code WorkCategory.orderNo} ascending (a {@code null} {@code orderNo} sorts
     * last) and then by work category id ascending (a {@code null} id sorts last).
     */
    private static final Comparator<RowSource> ROW_ORDER =
            Comparator.<RowSource, Integer>comparing(
                            row -> row.category().getOrderNo(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(row -> row.category().getId(),
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Derives the ordered {@code Schedule_Row} skeleton from a project's estimate lines.
     *
     * @param estimateLines the project's estimate lines; may be {@code null} or empty (no estimate
     *                      or no lines), in which case an empty list is returned (R4.3). Each line
     *                      must reference a work item with a non-null work category.
     * @return one {@link RowSource} per distinct work category of the lines, ordered by
     *         {@code (orderNo ASC NULLS LAST, id ASC)} (R4.2); never {@code null}
     */
    public static List<RowSource> deriveRows(Collection<EstimateLineEntity> estimateLines) {
        if (estimateLines == null || estimateLines.isEmpty()) {
            return List.of();
        }

        // Group by category, preserving first-seen insertion order for determinism before sorting.
        Map<WorkCategoryEntity, List<EstimateLineEntity>> byCategory = new LinkedHashMap<>();
        for (EstimateLineEntity line : estimateLines) {
            WorkCategoryEntity category = line.getWorkItem().getWorkCategory();
            byCategory.computeIfAbsent(category, c -> new ArrayList<>()).add(line);
        }

        List<RowSource> rows = new ArrayList<>(byCategory.size());
        for (Map.Entry<WorkCategoryEntity, List<EstimateLineEntity>> entry : byCategory.entrySet()) {
            List<EstimateLineEntity> lines = new ArrayList<>(entry.getValue());
            lines.sort(LINE_ORDER);

            BigDecimal value = BigDecimal.ZERO;
            for (EstimateLineEntity line : lines) {
                BigDecimal lineValue = line.getValueNet();
                value = value.add(lineValue == null ? BigDecimal.ZERO : lineValue);
            }

            rows.add(new RowSource(
                    entry.getKey(),
                    lines.size(),
                    value,
                    List.copyOf(lines)));
        }

        rows.sort(ROW_ORDER);
        return rows;
    }
}
