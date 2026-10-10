package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * One {@code Schedule_Row} of the planning Gantt: a single {@code WorkCategory} of the project's
 * estimate together with its aggregated volume of work and, at most, one bar
 * {@code (startDay, durationDays)} measured in calendar days from the schedule anchor
 * (FOR-05-10 Requirements 4, 5). Rows are ordered by {@code (orderNo ASC NULLS LAST, id ASC)}
 * and one row exists per distinct work category of the estimate, including inactive categories
 * (Requirement 4.2, 4.7).
 *
 * <p><strong>Money masking (D-money, R5.3).</strong> {@link #categoryValue()} is the net value of
 * the category and is populated only for a Money_Viewer. For a non-viewer the service leaves it
 * {@code null} and the class-level {@link JsonInclude}({@code NON_NULL}) omits it from the payload
 * entirely rather than emitting {@code null}. No field on this model ever carries a man-days
 * figure or the internal {@code Daily_Output_Rate} (Requirement 5.4, 9.3).
 *
 * <p>{@link #suggestedDurationDays()} is the Suggested_Duration — {@code null} when
 * {@code crewSize = 0} — and {@code startDay} / {@code durationDays} / {@code finishDay} are
 * {@code null} together when the row has no bar (not yet scheduled).
 *
 * @param workCategoryId        the work category id
 * @param code                  the work category code
 * @param orderNo               the work category sort order ({@code null} sorts last)
 * @param name                  the localized work category name (ru → pl → code fallback, R5.7)
 * @param lineCount             the number of estimate lines in this category
 * @param suggestedDurationDays the Suggested_Duration in days; {@code null} when crew size is 0
 * @param categoryValue         the net value of the category; present only for a Money_Viewer
 * @param startDay              the bar start day (1-based); {@code null} when not scheduled
 * @param durationDays          the bar duration in calendar days; {@code null} when not scheduled
 * @param finishDay             the bar finish day ({@code startDay + durationDays - 1});
 *                              {@code null} when not scheduled
 * @param lines                 the estimate lines of this category, sorted by line number then id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleRowView(
        Long workCategoryId,
        String code,
        Integer orderNo,
        String name,
        int lineCount,
        Integer suggestedDurationDays,
        BigDecimal categoryValue,
        Integer startDay,
        Integer durationDays,
        Integer finishDay,
        List<ScheduleLineView> lines
) {}
