package com.foremen.exception;

import lombok.Getter;

/**
 * FOR-06-01 Amendment A1 (Requirement 16.4; design A1.2) — raised when a single item of a bulk
 * {@code WorkReportService.createBatch(...)} fails validation. The bulk operation is
 * all-or-nothing within one transaction, so throwing this aborts (rolls back) the whole batch and
 * nothing is persisted.
 *
 * <p>It wraps the underlying per-item {@link ForemenApiException} unchanged (so the HTTP status,
 * message code, and message params are preserved for the response bundle) and adds the
 * zero-based {@code index} of the failing payload within the submitted list, plus the failing
 * item's {@code workItemId} and the optional offending {@code roomId}, so the controller (A1.3)
 * can build the {@code { index, workItemId, roomId?, message }} error body (R16.4) that identifies
 * exactly which payload failed.
 */
@Getter
public class WorkReportBatchItemException extends RuntimeException {

    /** The zero-based position of the failing payload within the submitted batch (R16.4). */
    private final int index;

    /** The failing payload's {@code workItemId} (may be {@code null} if the item omitted it). */
    private final Long workItemId;

    /** The offending room id when the failure is room-scoped (duplicate / no-plan / quantity), else {@code null}. */
    private final Long roomId;

    /** The underlying per-item validation failure, preserved for status + message-code resolution. */
    private final ForemenApiException cause;

    public WorkReportBatchItemException(int index, Long workItemId, Long roomId,
                                        ForemenApiException cause) {
        super(cause.getMessage(), cause);
        this.index = index;
        this.workItemId = workItemId;
        this.roomId = roomId;
        this.cause = cause;
    }

    @Override
    public ForemenApiException getCause() {
        return cause;
    }
}
