package com.foremen.controller.model.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The {@code PUT /api/project-schedules/bars} request (FOR-05-10 Requirement 7). It carries the
 * optimistic-locking {@link #version()} the client last read and the list of changed rows: each
 * {@link BarEntry} either sets / replaces or clears the bar of its work category. Rows not listed
 * are left unchanged; orphan bars (categories no longer in the estimate) are purged on every write
 * (Requirement 4.6, 7.5).
 *
 * <p>A {@link #version()} that no longer matches the stored schedule yields 409
 * {@code error.schedule.conflict} (Requirement 11.2). Per-entry business validation — the half-null
 * rule, the {@code [1, 3650]} bounds, and the unknown / duplicate-category check — is performed in
 * the service so the error can name the offending {@code workCategoryId} (Requirement 7.3, 7.4).
 *
 * @param version the optimistic-locking version the client last read (mandatory)
 * @param bars    the changed rows; each sets / replaces or clears one category's bar (mandatory)
 */
public record SaveBarsRequest(
        @NotNull Long version,
        @NotNull List<@Valid BarEntry> bars
) {}
