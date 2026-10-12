package com.foremen.controller.model.schedule;

/**
 * FOR-05-10b (R4.5; design §"POST /api/project-schedules/auto-create"): the response of
 * {@code POST /api/project-schedules/auto-create}. It wraps the freshly laid-out
 * {@link #schedule() Schedule_View} together with the {@link #alert() auto-create alert} that lists
 * every {@code Uncovered_Category} and whether the crew was empty.
 *
 * <p>This replaces the FOR-05-10 shape, where auto-create returned a bare {@link ScheduleView} and
 * hard-failed with 409 {@code error.schedule.crew.empty} on an empty crew. In the per-worker model
 * auto-create never fails on an empty crew (R4.6): it lays every category out (as a virtual
 * {@code Uncovered_Category} bar when no worker covers it) and reports the gaps through the alert so
 * the planner can act on them.
 *
 * @param schedule the fresh {@code Schedule_View} after the layout (never {@code null})
 * @param alert    the uncovered-category / empty-crew alert (never {@code null})
 */
public record AutoCreateResult(
        ScheduleView schedule,
        AutoCreateAlert alert
) {}
