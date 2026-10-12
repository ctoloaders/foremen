package com.foremen.controller.model.schedule;

import jakarta.validation.constraints.NotNull;

/**
 * One bar entry of a {@link SaveBarsRequest} (FOR-05-10b Requirement 3, 6; design §"PUT
 * /api/project-schedules/bars"). A bar is now keyed per worker by the pair
 * {@code (projectMemberId, workCategoryId)}: a {@code Covering_Worker}'s bar for a category, or —
 * when {@link #projectMemberId()} is {@code null} — the {@code Uncovered_Category} virtual bar
 * (D-uncovered). The entry either sets / replaces or clears that one bar:
 *
 * <ul>
 *   <li>both {@link #startDay()} and {@link #durationDays()} present &rarr; set / replace the
 *       {@code (member, category)} bar;</li>
 *   <li>both {@code null} &rarr; clear that {@code (member, category)} bar.</li>
 * </ul>
 *
 * <p><strong>Half-day grid (D-storage, R3.3, R6).</strong> {@code startDay} / {@code durationDays}
 * are <em>day</em> values on the 0.5 grid (the frontend {@code dragCascade} sends day values, R6).
 * The service converts them to the stored half-day integer counts
 * ({@code startHalfDays = round(startDay × 2)}, {@code durationHalfDays = round(durationDays × 2)})
 * and validates the {@code >= 0.5}-day / 3650-day horizon rule via
 * {@code ScheduleCalculator.validateBar}. A value that is not a multiple of 0.5, a half-null pair,
 * or a finish beyond the horizon is rejected with {@code error.schedule.bar.invalid} naming the
 * offending category (R3.5). An invalid {@code (member, category)} pairing (a member that is not an
 * ACTIVE WORKER of the project covering the category, and not {@code null}) is rejected with
 * {@code error.schedule.assignment.invalid}.
 *
 * <p>The pairing, bounds, half-null, and grid rules are enforced in the service — not via bean
 * validation — so the resulting error can carry the offending ids. Only {@link #workCategoryId()}
 * is bean-validated here as mandatory.
 *
 * <p>This model carries no man-days figure and no rate (Requirement 9).
 *
 * @param projectMemberId the covering worker's project-member id; {@code null} targets the virtual
 *                        {@code Uncovered_Category} bar (D-uncovered)
 * @param workCategoryId  the target work category id (mandatory)
 * @param startDay        the bar start day on the 0.5 grid; {@code null} together with
 *                        {@code durationDays} to clear the bar
 * @param durationDays    the bar duration in days on the 0.5 grid; {@code null} together with
 *                        {@code startDay} to clear the bar
 */
public record BarEntry(
        Long projectMemberId,
        @NotNull Long workCategoryId,
        Double startDay,
        Double durationDays
) {}
