package com.foremen.service.companyschedule;

/**
 * DB-free input to the pure {@code CrossProjectScheduleProjector} describing one worker's category
 * bar — a {@code project_schedule_bars} Real_Bar whose {@code projectMember} is non-null (FOR-07-01
 * Requirements 1.2, 2.1; design §"Pure projector"). The projector turns it into a
 * Worker_Category_Bar for the owning worker's Member_Row.
 *
 * <p>Carries only the bar's time fields, its worker identity and role, and its work category — and
 * no cost-bearing source (no money, Daily_Output_Rate, man-days, or pay tier), per Requirement 8.2.
 * The absolute dates are derived by the projector from the owning project's Schedule_Anchor and
 * these half-day fields, so they are not carried here.
 *
 * @param startHalfDays    1-based half-day offset from the schedule anchor ({@code >= 1})
 * @param durationHalfDays bar length in half-days ({@code >= 1})
 */
public record WorkerBarInput(long userId, String userName, String roleCode,
                             long workCategoryId, String categoryNameRu, String categoryNamePl,
                             int startHalfDays, int durationHalfDays) {
}
