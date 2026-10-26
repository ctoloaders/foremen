package com.foremen.controller.model.companyschedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * One {@code Projected_Bar} of a {@link MemberRow} in the cross-project staffing projection
 * (FOR-07-01-cross-project-schedule-core, Requirements 1.6, 1.7, 4). It is one of two variants
 * discriminated by {@link #kind()}:
 *
 * <ul>
 *   <li>{@link BarKind#WORKER_CATEGORY} — a {@code Worker_Category_Bar}: carries the owning project
 *       id and name, the {@link #colorIndex()}, the {@link #workCategoryId()} and localized
 *       {@link #workCategoryName()}, the raw {@link #startHalfDays()} / {@link #durationHalfDays()},
 *       and the absolute {@link #startDate()} / {@link #endDate()} when datable (Requirement 1.6).</li>
 *   <li>{@link BarKind#FOREMAN_SPAN} — a {@code Foreman_Span_Bar}: carries the owning project id and
 *       name and the {@link #colorIndex()}, and carries <strong>no</strong> work category and
 *       <strong>no</strong> half-day fields (they are {@code null}). Its dates are the project's own
 *       {@code startDate} / {@code endDate} when both are present (Requirement 1.7, 4.2).</li>
 * </ul>
 *
 * <p><strong>Undatable bars (Requirement 4.3, 4.4).</strong> A worker bar of an unanchored project
 * (null {@code project.startDate}) or a foreman span of a project missing either project date is an
 * {@code Undatable_Bar}: {@link #startDate()} / {@link #endDate()} are {@code null} and
 * {@link #unanchored()} is {@code true}. A worker bar still carries its raw half-day fields.
 *
 * <p><strong>No cost-bearing field (Requirement 8.1, 8.2, 8.3).</strong> This model carries no
 * money figure, no {@code Daily_Output_Rate}, no man-days value, and no worker pay tier / worker-type
 * cost field — it is a pure time/assignment bar.
 *
 * <p>The class-level {@link JsonInclude}({@code NON_NULL}) omits the {@code null} fields of a
 * foreman span (category / half-day fields) and of an undatable bar (dates) from the payload.
 *
 * @param kind             the bar variant: {@link BarKind#WORKER_CATEGORY} or
 *                         {@link BarKind#FOREMAN_SPAN}
 * @param projectId        the owning project id
 * @param projectName      the owning project name
 * @param colorIndex       the deterministic {@code Project_Color} index of the owning project,
 *                         shared by every bar of that project and its legend entry (Requirement 3)
 * @param workCategoryId   the work category id for a worker bar; {@code null} for a foreman span
 * @param workCategoryName the localized work category name for a worker bar; {@code null} for a
 *                         foreman span
 * @param startHalfDays    the raw 1-based start half-day for a worker bar; {@code null} for a
 *                         foreman span
 * @param durationHalfDays the raw half-day duration for a worker bar; {@code null} for a foreman span
 * @param startDate        the absolute inclusive start date; {@code null} for an {@code Undatable_Bar}
 * @param endDate          the absolute inclusive end date; {@code null} for an {@code Undatable_Bar}
 * @param unanchored       whether this bar is an {@code Undatable_Bar} (null absolute dates)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectedBar(
        BarKind kind,
        Long projectId,
        String projectName,
        int colorIndex,
        Long workCategoryId,
        String workCategoryName,
        Integer startHalfDays,
        Integer durationHalfDays,
        LocalDate startDate,
        LocalDate endDate,
        boolean unanchored
) {}
