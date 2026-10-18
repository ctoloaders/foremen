package com.foremen.service.schedule;

import java.util.OptionalDouble;

/**
 * FOR-06-02 (Requirement 4; design §4.2) — the pure arithmetic of the plan/actual Gantt overlay's
 * <em>trajectory</em>: given how much of a work category's planned time window has elapsed and how
 * much of its planned quantity is confirmed done, classify the category as {@code ON_TRACK},
 * {@code AT_RISK}, or {@code OVERDUE}, and project a likely finish day.
 *
 * <p>Every method is static and side-effect free: no database, no Spring context, no clock. Results
 * depend only on the arguments, so the classifier is directly unit-testable on its boundary cases
 * (R4.6). The caller ({@code PlanActualService}) supplies {@code elapsedShare} from {@code today}
 * versus the schedule's calendar anchor and {@code confirmedCompletion} from the APPROVED reported
 * quantity versus the planned quantity; <strong>pending and rejected reported quantity never reach
 * this helper</strong> (R4.5) — trajectory is computed from confirmed fact only.
 *
 * <p><strong>Classification rule (R4.2).</strong> With {@code e = elapsedShare} (clamped to
 * {@code [0, 1]} by the caller when a calendar anchor exists), {@code c = confirmedCompletion}
 * (clamped to {@code [0, 1]}), and a configured {@code riskMargin ≥ 0}:
 * <ul>
 *   <li>{@code c ≥ 1} → {@code ON_TRACK} (the category is complete; done is never a risk);</li>
 *   <li>else {@code e ≥ 1} (time is up) → {@code OVERDUE};</li>
 *   <li>else {@code c < e − riskMargin} (progress trails elapsed time by more than the tolerance)
 *       → {@code AT_RISK};</li>
 *   <li>otherwise → {@code ON_TRACK}.</li>
 * </ul>
 *
 * <p><strong>No-anchor safety (R4.1).</strong> A category whose schedule has no calendar anchor has
 * no meaningful elapsed share; the caller passes {@link #NO_ANCHOR} and this helper returns
 * {@code ON_TRACK} with no projected finish, so a project planned on an abstract "Day 1" never
 * raises a false alert.
 */
public final class TrajectoryCalculator {

    /**
     * The sentinel {@code elapsedShare} the caller passes for a category whose schedule has no
     * calendar anchor (FOR-05-10 abstract "Day 1"): there is no real elapsed time, so
     * {@link #classify(double, double, double)} returns {@code ON_TRACK} and
     * {@link #projectFinishDay(int, int, double, double)} returns empty (R4.1).
     */
    public static final double NO_ANCHOR = Double.NaN;

    /**
     * An upper bound on the projected finish day so a near-zero confirmed completion cannot
     * extrapolate to an absurd horizon. Matches the planning Gantt's 3650-day horizon
     * ({@link ScheduleCalculator#MAX_DAY}); the caller may tighten it via configuration but never
     * loosen it past a sane value.
     */
    public static final int MAX_PROJECTED_FINISH_DAY = ScheduleCalculator.MAX_DAY;

    private TrajectoryCalculator() {
    }

    /** The trajectory classification of a work category on the plan/actual overlay (R4.2). */
    public enum TrajectoryStatus {
        /** On schedule: complete, or progress keeps pace with (or leads) elapsed time. */
        ON_TRACK,
        /** Behind pace: time remains but confirmed progress trails elapsed time by > {@code riskMargin}. */
        AT_RISK,
        /** Time is up ({@code elapsedShare ≥ 1}) and the category is not yet complete. */
        OVERDUE
    }

    /**
     * Classifies a category's trajectory from its elapsed-time share and confirmed completion
     * (R4.2). See the class Javadoc for the full rule. A {@link #NO_ANCHOR} {@code elapsedShare}
     * (NaN) yields {@code ON_TRACK} (R4.1): without a calendar there is no pace to fall behind.
     *
     * @param elapsedShare        the share of the category's planned window that has elapsed, in
     *                            {@code [0, 1]} when a calendar anchor exists, or {@link #NO_ANCHOR}
     *                            (NaN) when the schedule has no anchor
     * @param confirmedCompletion the confirmed (APPROVED) completion ratio, expected in {@code [0, 1]}
     *                            (the caller clamps it); {@code ≥ 1} means the category is complete
     * @param riskMargin          the non-negative at-risk tolerance (default {@code 0.1}); a category
     *                            is {@code AT_RISK} only once confirmed completion trails elapsed
     *                            share by strictly more than this
     * @return the trajectory status, never {@code null}
     */
    public static TrajectoryStatus classify(
            double elapsedShare, double confirmedCompletion, double riskMargin) {
        if (confirmedCompletion >= 1.0) {
            return TrajectoryStatus.ON_TRACK; // complete — never a risk (R4.2)
        }
        if (Double.isNaN(elapsedShare)) {
            return TrajectoryStatus.ON_TRACK; // no calendar anchor — no false alert (R4.1)
        }
        if (elapsedShare >= 1.0) {
            return TrajectoryStatus.OVERDUE; // time is up and not complete (R4.2)
        }
        double margin = Math.max(0.0, riskMargin);
        if (confirmedCompletion < elapsedShare - margin) {
            return TrajectoryStatus.AT_RISK; // progress trails elapsed time beyond tolerance (R4.2)
        }
        return TrajectoryStatus.ON_TRACK;
    }

    /**
     * Projects the category's likely finish day by pace extrapolation (R4.4): the planned window is
     * {@code [minStartDay, minStartDay + plannedDurationDays − 1]}; if {@code elapsedShare} of it has
     * elapsed while only {@code confirmedCompletion} of the quantity is done, the work is proceeding
     * at pace {@code confirmedCompletion / elapsedShare} of plan, so the projected finish day is
     * {@code minStartDay + plannedDurationDays × (elapsedShare / confirmedCompletion)}, capped at
     * {@link #MAX_PROJECTED_FINISH_DAY}.
     *
     * <p>Returns {@link OptionalDouble#empty()} when there is no basis to project: a
     * {@link #NO_ANCHOR} elapsed share (R4.1), a non-positive confirmed completion (no pace yet,
     * R4.4), or a non-positive elapsed share. A confirmed completion {@code ≥ 1} projects the finish
     * at the planned finish day (the category is done no later than planned).
     *
     * @param minStartDay         the 1-based start day of the category's planned window (≥ 1)
     * @param plannedDurationDays the planned window length in days (≥ 1)
     * @param elapsedShare        the elapsed share in {@code [0, 1]}, or {@link #NO_ANCHOR}
     * @param confirmedCompletion the confirmed completion ratio (expected {@code [0, 1]})
     * @return the projected finish day as a {@code double}, or empty when it cannot be projected
     */
    public static OptionalDouble projectFinishDay(
            int minStartDay, int plannedDurationDays, double elapsedShare, double confirmedCompletion) {
        if (Double.isNaN(elapsedShare) || elapsedShare <= 0.0 || confirmedCompletion <= 0.0) {
            return OptionalDouble.empty();
        }
        double effectiveCompletion = Math.min(1.0, confirmedCompletion);
        double projected =
                minStartDay + plannedDurationDays * (elapsedShare / effectiveCompletion);
        if (projected > MAX_PROJECTED_FINISH_DAY) {
            projected = MAX_PROJECTED_FINISH_DAY;
        }
        return OptionalDouble.of(projected);
    }
}
