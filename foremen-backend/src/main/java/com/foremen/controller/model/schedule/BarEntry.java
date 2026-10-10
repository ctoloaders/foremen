package com.foremen.controller.model.schedule;

import jakarta.validation.constraints.NotNull;

/**
 * One bar entry of a {@link SaveBarsRequest} (FOR-05-10 Requirement 7). It targets the
 * {@code Schedule_Row} of {@link #workCategoryId()} and sets, or clears, that row's bar:
 *
 * <ul>
 *   <li>both {@link #startDay()} and {@link #durationDays()} present &rarr; set / replace the bar;</li>
 *   <li>both {@code null} &rarr; clear the bar (remove the category from the schedule).</li>
 * </ul>
 *
 * <p>The bounds and the half-null rule ({@code startDay} / {@code durationDays} must be both null or
 * both in {@code [1, 3650]} with {@code startDay + durationDays - 1 <= 3650}) and the
 * unknown / duplicate-category rule are enforced in the service — not via bean validation — so the
 * resulting error can carry the offending {@code workCategoryId} ({@code error.schedule.bar.invalid}
 * / {@code error.schedule.category.invalid}, Requirement 7.3, 7.4). Only {@link #workCategoryId()}
 * is bean-validated here as mandatory.
 *
 * <p>This model carries no man-days figure and no rate (Requirement 9.3).
 *
 * @param workCategoryId the target work category id (mandatory)
 * @param startDay       the bar start day (1-based); {@code null} together with {@code durationDays}
 *                       to clear the bar
 * @param durationDays   the bar duration in calendar days; {@code null} together with {@code startDay}
 *                       to clear the bar
 */
public record BarEntry(
        @NotNull Long workCategoryId,
        Integer startDay,
        Integer durationDays
) {}
