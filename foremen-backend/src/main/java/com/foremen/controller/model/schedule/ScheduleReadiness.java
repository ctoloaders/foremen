package com.foremen.controller.model.schedule;

/**
 * The {@code schedule} readiness gate returned by {@code GET /api/project-schedules/readiness}
 * (FOR-05-10 Requirement 12). {@link #key()} is the constant gate key {@code "schedule"};
 * {@link #state()} is derived from {@code (rowCount, scheduledCount)} where {@code scheduledCount}
 * counts only bars of current rows (orphans excluded):
 *
 * <ul>
 *   <li>{@link State#DONE} when {@code rowCount > 0} and every row is scheduled;</li>
 *   <li>{@link State#BLOCKED} when nothing is scheduled (including {@code rowCount == 0});</li>
 *   <li>{@link State#PARTIAL} otherwise.</li>
 * </ul>
 *
 * @param key            the gate key — always {@value #KEY}
 * @param state          the gate state (DONE / PARTIAL / BLOCKED)
 * @param rowCount       the number of current schedule rows (work categories of the estimate)
 * @param scheduledCount the number of current rows that have a bar
 */
public record ScheduleReadiness(
        String key,
        State state,
        int rowCount,
        int scheduledCount
) {

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
