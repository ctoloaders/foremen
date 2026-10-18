package com.foremen.exception.dto;

/**
 * FOR-06-01 Amendment A1 (Requirement 16.4; design A1.2) — the {@code 400} error body returned when
 * a single item of a bulk {@code POST /api/work-reports/batch} fails validation.
 *
 * <p>The whole batch is all-or-nothing in one transaction, so this identifies exactly which
 * submitted payload aborted the batch: its zero-based {@code index} within the list, the failing
 * item's {@code workItemId}, the optional offending {@code roomId} (present when the failure is
 * room-scoped — duplicate / no-plan / quantity), and the already-localized {@code message} resolved
 * from the underlying per-item {@code ForemenApiException} message code for the request locale.
 *
 * @param index      the zero-based position of the failing payload within the submitted batch
 * @param workItemId the failing payload's {@code workItemId} (may be {@code null})
 * @param roomId     the offending room id when the failure is room-scoped, else {@code null}
 * @param message    the localized failure message for the request locale
 */
public record WorkReportBatchItemError(
        int index,
        Long workItemId,
        Long roomId,
        String message) {
}
