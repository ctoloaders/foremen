package com.foremen.service.schedule;

import com.foremen.controller.model.schedule.ScheduleReadiness;
import com.foremen.controller.model.schedule.ScheduleReadiness.State;

/**
 * Pure derivation of the {@code schedule} readiness state (FOR-05-10 Requirement 12; design
 * §"ScheduleReadinessState"). The single method {@link #deriveState(int, int)} maps a pair of
 * counts — the number of current schedule rows and the number of those rows that carry a bar — to a
 * {@link State}. It is static and side-effect free: no database, no Spring context, no clock, so the
 * rule is directly unit- and property-testable (task 3.6, Property 6).
 *
 * <p>The {@code scheduledCount} the service passes counts only bars of <strong>current</strong>
 * rows; orphan bars (bars whose category is no longer an estimate row) are excluded upstream, so
 * {@code 0 <= scheduledCount <= rowCount} always holds in production. The rule itself is total over
 * all integer inputs.
 *
 * <p>The derivation (design §"ScheduleReadinessState"):
 * <pre>
 * state(rowCount, scheduledCount) =
 *    rowCount &gt; 0 AND scheduledCount == rowCount -&gt; DONE
 *    scheduledCount == 0                          -&gt; BLOCKED   // includes rowCount == 0
 *    otherwise                                    -&gt; PARTIAL
 * </pre>
 */
public final class ScheduleReadinessState {

    private ScheduleReadinessState() {
    }

    /**
     * Derives the three-valued {@link State} of the planning Gantt from the row counts (R12.1,
     * R12.3). Evaluation order matters: {@link State#DONE} is tested before {@link State#BLOCKED} so
     * that an all-scheduled schedule is {@code DONE} rather than falling through, and the empty
     * schedule ({@code rowCount == 0}, hence {@code scheduledCount == 0}) is {@link State#BLOCKED}.
     *
     * @param rowCount       the number of current schedule rows (work categories of the estimate)
     * @param scheduledCount the number of current rows that have a bar (orphans excluded upstream)
     * @return {@link State#DONE} when {@code rowCount > 0} and every row is scheduled;
     *         {@link State#BLOCKED} when nothing is scheduled (including {@code rowCount == 0});
     *         {@link State#PARTIAL} otherwise
     */
    public static ScheduleReadiness.State deriveState(int rowCount, int scheduledCount) {
        if (rowCount > 0 && scheduledCount == rowCount) {
            return State.DONE;
        }
        if (scheduledCount == 0) {
            return State.BLOCKED;
        }
        return State.PARTIAL;
    }
}
