package com.foremen.controller.model.schedule;

/**
 * FOR-05-10b (R4.2, R4.5; design §"POST /api/project-schedules/auto-create"): one
 * {@code Uncovered_Category} reference carried by an {@link AutoCreateAlert}. An
 * {@code Uncovered_Category} is an estimate work category that no ACTIVE WORKER of the team covers;
 * auto-create still lays it out as a single virtual (null-member) bar but reports it here so the UI
 * can warn the planner that no worker was assigned to it.
 *
 * @param workCategoryId the uncovered work category id
 * @param name           the localized work category name (ru &rarr; pl &rarr; code fallback, R4.5)
 */
public record UncoveredCategoryRef(
        Long workCategoryId,
        String name
) {}
