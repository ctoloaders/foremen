package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * FOR-06-02 (Requirement 1) — the read-only plan/actual overlay payload returned by
 * {@code GET /api/project-schedules/plan-actual}. It frames the planning Gantt (anchor, project end,
 * finish day, schedule version) and overlays, per work category, the confirmed / pending reported
 * quantity, the confirmed completion, and the trajectory classification (design §4.1).
 *
 * <p>This is a strictly transient projection: the endpoint that produces it creates / updates /
 * deletes no row and bumps no schedule version (R1.6). Day values are the same 0.5-multiples the
 * {@code ScheduleView} exposes. {@link #money()} is present only for a Money_Viewer (R6.5); the
 * class-level {@link JsonInclude}({@code NON_NULL}) omits it for a non-viewer.
 *
 * @param projectId      the owning project id
 * @param anchorDate     the schedule anchor (project start date); {@code null} when the project has
 *                       no start date, in which case trajectory falls back to {@code ON_TRACK} /
 *                       {@code noAnchor} (R4.1)
 * @param projectEndDate the project end date; {@code null} when unset
 * @param finishDay      the schedule finish day as a 0.5-multiple; {@code null} when nothing is
 *                       scheduled
 * @param version        the schedule optimistic-lock version this view was computed from
 * @param computedAt     the server timestamp the view was assembled at
 * @param totals         the project-level roll-up (R1.4)
 * @param categories     one entry per work-category schedule row
 * @param money          the revenue roll-up; {@code null} for a non-money-viewer (R6.5)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanActualView(
        Long projectId,
        LocalDate anchorDate,
        LocalDate projectEndDate,
        Double finishDay,
        long version,
        OffsetDateTime computedAt,
        PlanActualTotals totals,
        List<PlanActualCategory> categories,
        PlanActualMoney money
) {}
