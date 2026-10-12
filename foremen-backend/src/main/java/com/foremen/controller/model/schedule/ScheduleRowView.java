package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * One {@code Schedule_Row} of the planning Gantt: a single {@code WorkCategory} of the project's
 * estimate together with its aggregated volume of work and, as of FOR-05-10b, the per-worker bars
 * that plan it (FOR-05-10 Requirements 4, 5; FOR-05-10b Requirements 3, 7). Rows are ordered by
 * {@code (orderNo ASC NULLS LAST, id ASC)} and one row exists per distinct work category of the
 * estimate, including inactive categories (Requirement 4.2, 4.7).
 *
 * <p><strong>Per-worker bars (FOR-05-10b, D-bar-key, R3.1, R7.2).</strong> {@link #bars()} replaces
 * the FOR-05-10 single-bar {@code (startDay, durationDays, finishDay)} triple with a list of
 * {@link ScheduleBarView} — one bar per {@code Covering_Worker} who performs this category, each
 * keyed by its {@code projectMemberId} and rendered in that worker's color. A row with no scheduled
 * bar carries an empty list. {@link #covered()} is {@code true} iff at least one ACTIVE WORKER of
 * the team covers this category; an {@code Uncovered_Category} has {@code covered = false} and, when
 * a virtual bar exists, a single {@link ScheduleBarView} whose {@code projectMemberId} is
 * {@code null} (D-uncovered, R3.2, R7.3).
 *
 * <p><strong>Money masking (D-money, R5.3).</strong> {@link #categoryValue()} is the net value of
 * the category and is populated only for a Money_Viewer. For a non-viewer the service leaves it
 * {@code null} and the class-level {@link JsonInclude}({@code NON_NULL}) omits it from the payload
 * entirely rather than emitting {@code null}. No field on this model ever carries a man-days
 * figure or the internal {@code Daily_Output_Rate} (Requirement 5.4, 9.3).
 *
 * <p>{@link #suggestedDurationDays()} is the Suggested_Duration — {@code null} when
 * {@code crewSize = 0}.
 *
 * @param workCategoryId        the work category id
 * @param code                  the work category code
 * @param orderNo               the work category sort order ({@code null} sorts last)
 * @param name                  the localized work category name (ru → pl → code fallback, R5.7)
 * @param lineCount             the number of estimate lines in this category
 * @param suggestedDurationDays the Suggested_Duration in days; {@code null} when crew size is 0
 * @param categoryValue         the net value of the category; present only for a Money_Viewer
 * @param covered               whether at least one ACTIVE WORKER covers this category (R7); an
 *                              {@code Uncovered_Category} is {@code false}
 * @param bars                  the per-worker bars planning this category (FOR-05-10b R3.1, R7.2);
 *                              empty when nothing is scheduled, a single null-member bar for an
 *                              {@code Uncovered_Category} virtual bar (D-uncovered)
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
        boolean covered,
        List<ScheduleBarView> bars,
        List<ScheduleLineView> lines
) {}
