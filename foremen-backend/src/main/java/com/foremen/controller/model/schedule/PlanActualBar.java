package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FOR-06-02 (Requirement 1.3) — one planned per-worker bar of a {@link PlanActualCategory}, carried
 * through unchanged from the planning Gantt's {@link ScheduleBarView} so the read-only plan/actual
 * overlay renders the plan on the same half-day timeline. A {@code null} {@link #projectMemberId()}
 * marks the virtual "uncovered category" bar (FOR-05-10b D-uncovered).
 *
 * <p>Day values are the same 0.5-multiples the schedule exposes
 * ({@code startDay = startHalfDays / 2}, {@code durationDays = durationHalfDays / 2},
 * {@code finishDay = (startHalfDays + durationHalfDays − 1) / 2}).
 *
 * @param projectMemberId the covering worker's project-member id; {@code null} for the virtual bar
 * @param workerName      the covering worker's display name; {@code null} for the virtual bar
 * @param startDay        the bar start day as a 0.5-multiple
 * @param durationDays    the bar duration in days as a 0.5-multiple
 * @param finishDay       the bar inclusive finish day as a 0.5-multiple
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanActualBar(
        Long projectMemberId,
        String workerName,
        Double startDay,
        Double durationDays,
        Double finishDay
) {}
