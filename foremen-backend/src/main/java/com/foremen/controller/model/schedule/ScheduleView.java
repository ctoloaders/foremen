package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The planning Gantt payload returned by {@code GET /api/project-schedules},
 * {@code PUT /api/project-schedules/bars}, and {@code POST /api/project-schedules/auto-create}
 * (FOR-05-10 Requirement 5). It carries everything the Harmonogram tab needs to render the schedule
 * without extra lookups: the schedule anchor and project-end dates, the optimistic-locking
 * {@link #version()}, the active-worker {@link #crewSize()}, the computed finish, and one
 * {@link ScheduleRowView} per work category of the estimate (ordered, including inactive
 * categories).
 *
 * <p>Day {@code n} (1-based) maps to {@code anchorDate + (n - 1)} days; when the project has no
 * start date {@link #anchorDate()} is {@code null} and days are counted from an abstract "Day 1".
 * {@link #finishDay()} / {@link #finishDate()} are {@code null} when no row is scheduled, and
 * {@link #exceedsProjectEnd()} is {@code true} only when a start, an end, and a finish all exist and
 * the finish date is after the project end date (Requirement 5.5).
 *
 * <p><strong>Money masking (D-money, R5.3).</strong> {@link #currency()} and every row's
 * {@link ScheduleRowView#categoryValue()} are populated only for a Money_Viewer; for a non-viewer
 * the service leaves {@link #currency()} {@code null} and the class-level
 * {@link JsonInclude}({@code NON_NULL}) omits it from the payload entirely. No field on this model
 * (or any nested model) ever carries a man-days figure or the internal {@code Daily_Output_Rate}
 * (Requirement 5.4, 9.3).
 *
 * @param projectId         the owning project id
 * @param anchorDate        the schedule anchor (project start date); {@code null} when the project
 *                          has no start date, in which case days count from an abstract "Day 1"
 * @param projectEndDate    the project end date; {@code null} when the project has no end date
 * @param editable          whether the schedule may be modified in the project's current status
 * @param version           the optimistic-locking version to echo back on the next write
 * @param crewSize          the number of ACTIVE WORKER members of the project team
 * @param currency          the money currency code; present only for a Money_Viewer
 * @param finishDay         the schedule finish day (max row {@code finishDay}); {@code null} when
 *                          nothing is scheduled
 * @param finishDate        the schedule finish date; {@code null} when nothing is scheduled or the
 *                          project has no anchor date
 * @param exceedsProjectEnd whether the finish date falls after the project end date (R5.5)
 * @param rows              one row per work category, ordered {@code (orderNo ASC NULLS LAST, id ASC)}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleView(
        Long projectId,
        LocalDate anchorDate,
        LocalDate projectEndDate,
        boolean editable,
        long version,
        int crewSize,
        String currency,
        Integer finishDay,
        LocalDate finishDate,
        boolean exceedsProjectEnd,
        List<ScheduleRowView> rows
) {}
