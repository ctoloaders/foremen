package com.foremen.controller.model.schedule;

import jakarta.validation.constraints.NotNull;

/**
 * The {@code POST /api/project-schedules/auto-create} request (FOR-05-10 Requirement 8). It carries
 * only the optimistic-locking {@link #version()} the client last read; auto-create replaces all bars
 * by laying each work category back to back from day 1 using the internal
 * {@code Daily_Output_Rate} (from {@code foremen.schedule.*} config) and the project's active-worker
 * crew size.
 *
 * <p>The rate is <strong>never</strong> accepted from the client and never appears in any request or
 * response (Requirement 9.2, 9.3); the request intentionally has no rate, man-days, crew-size, or
 * row field — version only. A {@link #version()} that no longer matches the stored schedule yields
 * 409 {@code error.schedule.conflict} (Requirement 11.2).
 *
 * @param version the optimistic-locking version the client last read (mandatory)
 */
public record AutoCreateRequest(
        @NotNull Long version
) {}
