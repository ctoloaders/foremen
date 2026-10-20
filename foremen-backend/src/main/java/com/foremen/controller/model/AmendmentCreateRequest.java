package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * FOR-06-08 (Requirements 2.2, 2.5; design §Backend components) — create payload for an
 * {@code EstimateAmendment}. Carries only the owning {@code projectId} plus an optional free-text
 * {@code note}; the {@code seqNo}, the {@code status} ({@code OPEN}), and the empty line set are
 * derived server-side by {@code AmendmentService}, which also enforces the eligibility guard (the
 * project must be {@code ACTIVE} with a signed estimate baseline, R2.5) and the single-open-amendment
 * invariant (a create while an {@code OPEN} amendment exists is rejected with
 * {@code error.amendment.openExists}, R2.3).
 *
 * <p>Change lines are NOT added through this create — they go through the dedicated add-line path
 * ({@link AddLineRequest}) so the same-subject merge can be resolved (R3, R5). {@code effectiveDate}
 * and the signable document are written only by FOR-06-09 (R7.4) and are not settable here.
 */
public record AmendmentCreateRequest(
        @NotNull Long projectId,
        String note) {
}
