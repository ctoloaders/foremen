package com.foremen.controller.model;

/**
 * FOR-06-08 (design §Backend components) — update payload for an {@code EstimateAmendment}. While an
 * amendment is {@code OPEN} the only editable aggregate-level field is the free-text {@code note};
 * the {@code projectId} is fixed at create time and not re-settable here, and the {@code seqNo} /
 * {@code status} / {@code effectiveDate} are driven server-side (the lifecycle out of {@code OPEN}
 * is owned by FOR-06-09, R7.4). Change lines are added/merged/deleted through the dedicated line
 * endpoints ({@link AddLineRequest}), not through this aggregate update; the {@code OPEN} guard
 * ({@code error.amendment.notOpen}) is enforced by {@code AmendmentService} (R2.4).
 */
public record AmendmentUpdateRequest(
        String note) {
}
