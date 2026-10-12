package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FOR-05-10b (R3.1, R3.2, R3.3, R7.1, R7.2, R7.3; design §"API changes → ScheduleView"): one
 * per-worker bar of a {@link ScheduleRowView}. A single {@code Schedule_Row} (work category) may
 * carry several of these — one per {@code Covering_Worker} who performs the category — so the Gantt
 * renders one colored bar per worker instead of a single per-category bar (the FOR-05-10 model).
 *
 * <p>The bar is keyed by {@link #projectMemberId()}; a {@code null} member marks the
 * {@code Uncovered_Category} virtual bar (D-uncovered, R3.2), of which a row has at most one (and in
 * that case it is the row's only bar). {@link #workerName()} is the covering worker's display name,
 * {@code null} for the virtual uncovered bar.
 *
 * <p><strong>Half-day exposure (D-storage, R3.3).</strong> Bars are stored as integer half-day
 * counts; the API presents days as 0.5-multiples derived from those counts:
 * {@code startDay = startHalfDays / 2}, {@code durationDays = durationHalfDays / 2}, and
 * {@code finishDay = (startHalfDays + durationHalfDays − 1) / 2}. All three are therefore always
 * multiples of 0.5. No field on this model carries a man-days figure or the internal
 * {@code Daily_Output_Rate}.
 *
 * @param projectMemberId the covering worker's project-member id; {@code null} for the virtual
 *                        {@code Uncovered_Category} bar (D-uncovered)
 * @param workerName      the covering worker's display name; {@code null} for the virtual bar
 * @param startDay        the bar start day as a 0.5-multiple ({@code startHalfDays / 2})
 * @param durationDays    the bar duration in days as a 0.5-multiple ({@code durationHalfDays / 2})
 * @param finishDay       the bar inclusive finish day as a 0.5-multiple
 *                        ({@code (startHalfDays + durationHalfDays − 1) / 2})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleBarView(
        Long projectMemberId,
        String workerName,
        Double startDay,
        Double durationDays,
        Double finishDay
) {}
