package com.foremen.controller.model;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * FOR-06-01 (Requirements 2.1, 4.5, 6.5, 11.2; design §4.3) — update payload for a
 * {@code WorkReport} (an edit of a non-terminal report, or a resubmit of a
 * {@code CHANGES_REQUESTED} one, R4.5/R4.6). Carries the same author-supplied inputs as the create
 * payload: the reported {@code workItemId}, the {@code workerId}, the
 * {@code [performedFrom, performedTo]} interval, an optional {@code note}, the per-room
 * {@link WorkReportRoomModel} rows, and optional {@code mediaRefs}.
 *
 * <p>The {@code projectId} is fixed at create time and not re-settable here. The status machine,
 * the ownership/role guards, and the revenue derivations are enforced/derived server-side by
 * {@code WorkReportService}; editing an {@code APPROVED}/{@code REJECTED} report is refused (R6.4).
 */
public record WorkReportUpdateRequest(
        @NotNull Long workItemId,
        @NotNull Long workerId,
        @NotNull LocalDate performedFrom,
        @NotNull LocalDate performedTo,
        String note,
        @NotEmpty List<WorkReportRoomModel> rooms,
        List<String> mediaRefs) {
}
