package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FOR-05-10b (R7.1, D-color; design §"API changes → ScheduleView.workers"): one entry of the
 * schedule's worker legend. The {@link ScheduleView#workers()} list carries one of these per ACTIVE
 * WORKER project member, so the frontend can render a color legend mapping each worker to the color
 * its bars use.
 *
 * <p>{@link #color()} is reserved and always {@code null} from the backend: the per-worker color is
 * a <em>stable, deterministic</em> value the frontend derives from {@link #projectMemberId()}
 * (D-color — no color is stored). The field exists on the contract so a future server-driven color
 * is a non-breaking change; the class-level {@link JsonInclude}({@code NON_NULL}) omits the null
 * color from the payload today.
 *
 * @param projectMemberId the worker's project-member id
 * @param userId          the worker's stable USER id — the color key (FOR-05-10b bug-report R1):
 *                        the frontend derives the color from this stable id so a worker keeps the
 *                        same color across team/schedule re-creation (membership ids churn on
 *                        remove+re-add; the user id does not)
 * @param name            the worker's display name
 * @param color           reserved; always {@code null} today — the color is computed client-side
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleWorkerView(
        Long projectMemberId,
        Long userId,
        String name,
        String color
) {}
