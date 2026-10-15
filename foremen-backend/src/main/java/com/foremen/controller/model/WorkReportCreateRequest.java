package com.foremen.controller.model;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * FOR-06-01 (Requirements 2.1, 6.5, 11.2; design §4.3) — create payload for a {@code WorkReport}.
 * Carries the author-supplied inputs only: the owning {@code projectId}, the reported
 * {@code workItemId}, the {@code workerId} whose work it records, the
 * {@code [performedFrom, performedTo]} interval, an optional free-form {@code note}, one or more
 * per-room {@link WorkReportRoomModel} rows, and optional {@code mediaRefs}.
 *
 * <p>The author is the authenticated caller (not carried here); the status, the decision trail, and
 * the revenue valuation ({@code clientUnitPrice}/{@code totalQuantity}/{@code revenueValue}) are all
 * derived server-side by {@code WorkReportService} (R1.4, R1.5, R4, R5, R6). Each write-path row
 * supplies {@code roomId}, {@code quantity}, and {@code inputMode}; the service resolves PERCENT
 * rows to an absolute quantity and snapshots {@code planQuantity} (R2.2, R2.6) and enforces the
 * per-room business rules (R2.3–R2.5).
 */
public record WorkReportCreateRequest(
        @NotNull Long projectId,
        @NotNull Long workItemId,
        @NotNull Long workerId,
        @NotNull LocalDate performedFrom,
        @NotNull LocalDate performedTo,
        String note,
        @NotEmpty List<WorkReportRoomModel> rooms,
        List<String> mediaRefs) {
}
