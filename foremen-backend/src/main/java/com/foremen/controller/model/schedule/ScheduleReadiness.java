package com.foremen.controller.model.schedule;

import java.util.List;

/**
 * The {@code schedule} readiness gate returned by {@code GET /api/project-schedules/readiness}
 * (FOR-05-10 Requirement 12; extended by FOR-05-10b Requirement 8.2). {@link #key()} is the
 * constant gate key {@code "schedule"}; {@link #state()} is derived from
 * {@code (rowCount, scheduledCount)} where {@code scheduledCount} counts only bars of current rows
 * (orphans excluded):
 *
 * <ul>
 *   <li>{@link State#DONE} when {@code rowCount > 0} and every row is scheduled;</li>
 *   <li>{@link State#BLOCKED} when nothing is scheduled (including {@code rowCount == 0});</li>
 *   <li>{@link State#PARTIAL} otherwise.</li>
 * </ul>
 *
 * <p>FOR-05-10b (R8.2) adds the {@code Uncovered_Category} set — estimate work categories that no
 * ACTIVE WORKER of the team covers. {@link #uncoveredCategoryIds()} is the documented API field
 * (the ids, in {@code Schedule_Row} order), while {@link #uncoveredCategories()} carries the same
 * categories together with their localized names so the aggregated-readiness
 * {@code ScheduleReadinessContributor} can emit one {@code readiness.schedule.noWorkerForCategory}
 * item per uncovered category (param = localized name) without a second database read. The gate
 * {@link #state()} itself stays the FOR-05-10 {@code (rowCount, scheduledCount)} derivation; the
 * "BLOCKED when any category is uncovered" rule is owned by the contributor's area state
 * (design §"Readiness design").
 *
 * @param key                  the gate key — always {@value #KEY}
 * @param state                the gate state (DONE / PARTIAL / BLOCKED)
 * @param rowCount             the number of current schedule rows (work categories of the estimate)
 * @param scheduledCount       the number of current rows that have a bar
 * @param uncoveredCategoryIds the ids of the {@code Uncovered_Category} categories, in
 *                             {@code Schedule_Row} order (R8.2); never {@code null}
 * @param uncoveredCategories  the {@code Uncovered_Category} categories with their localized names,
 *                             in {@code Schedule_Row} order; backs the contributor's localized
 *                             outstanding items; never {@code null}
 */
public record ScheduleReadiness(
        String key,
        State state,
        int rowCount,
        int scheduledCount,
        List<Long> uncoveredCategoryIds,
        List<UncoveredCategoryRef> uncoveredCategories
) {

    public ScheduleReadiness {
        uncoveredCategoryIds = uncoveredCategoryIds == null ? List.of() : List.copyOf(uncoveredCategoryIds);
        uncoveredCategories = uncoveredCategories == null ? List.of() : List.copyOf(uncoveredCategories);
    }

    /** The constant readiness gate key this spec contributes. */
    public static final String KEY = "schedule";

    /** The three-valued readiness state of the planning Gantt (Requirement 12). */
    public enum State {
        /** Every current row is scheduled ({@code rowCount > 0} and {@code scheduledCount == rowCount}). */
        DONE,
        /** Some but not all current rows are scheduled. */
        PARTIAL,
        /** No current row is scheduled (includes {@code rowCount == 0}). */
        BLOCKED
    }
}
