package com.foremen.dao.model;

/**
 * FOR-06-08 (Requirement 11.1): the lifecycle status of an {@code EstimateAmendmentEntity}.
 *
 * <p>An amendment is created {@link #OPEN} (unsigned, editable, accumulating change lines; at most one
 * per project — the Open_Amendment invariant). FOR-06-09 drives the transition out of {@link #OPEN}:
 * {@link #SENT_FOR_SIGNING} (handed to the signing flow, frozen for edits) → {@link #SIGNED} → {@link
 * #EFFECTIVE} (deltas apply from the amendment's {@code effectiveDate}). {@link #DELETED} is the
 * terminal state of a whole unsigned amendment removed before signing. This spec drives only {@link
 * #OPEN} and {@link #DELETED}; {@link #SENT_FOR_SIGNING}/{@link #SIGNED}/{@link #EFFECTIVE} are owned
 * by FOR-06-09.
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend via the {@code amendments.status.*} i18n keys (no backend i18n, mirroring {@link
 * DocumentStatus}).
 */
public enum AmendmentStatus {
    OPEN,
    SENT_FOR_SIGNING,
    SIGNED,
    EFFECTIVE,
    DELETED
}
