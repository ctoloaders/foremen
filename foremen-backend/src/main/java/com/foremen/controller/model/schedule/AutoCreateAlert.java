package com.foremen.controller.model.schedule;

import java.util.List;

/**
 * FOR-05-10b (R4.5, R4.6; design §"POST /api/project-schedules/auto-create"): the alert block of an
 * {@link AutoCreateResult}. It surfaces the two planning-state warnings auto-create can raise
 * without hard-failing:
 *
 * <ul>
 *   <li>{@link #crewEmpty()} — {@code true} when the project has no ACTIVE WORKER at all, so every
 *       category was laid out as an {@code Uncovered_Category} virtual bar (R4.6). This replaces the
 *       FOR-05-10 {@code error.schedule.crew.empty} hard 409; auto-create no longer fails on an empty
 *       crew.</li>
 *   <li>{@link #uncoveredCategories()} — one {@link UncoveredCategoryRef} per
 *       {@code Uncovered_Category} (id + localized name), in {@code Schedule_Row} order (R4.2, R4.5);
 *       empty when every category has at least one {@code Covering_Worker}.</li>
 * </ul>
 *
 * @param crewEmpty           whether the project had no ACTIVE WORKER (R4.6)
 * @param uncoveredCategories the uncovered categories (id + localized name), in row order (R4.5)
 */
public record AutoCreateAlert(
        boolean crewEmpty,
        List<UncoveredCategoryRef> uncoveredCategories
) {}
