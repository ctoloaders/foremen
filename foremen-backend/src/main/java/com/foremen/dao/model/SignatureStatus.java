package com.foremen.dao.model;

/**
 * FOR-05-08 (Requirements 4.1, 4.4): the status of a single {@link DocumentSignatureEntity}.
 *
 * <p>A signature starts {@link #PENDING}, becomes {@link #SIGNED} once its method-specific
 * evidence/integrity precondition is satisfied, or {@link #DECLINED} when the signer declines (with
 * a reason). A document is derived {@link DocumentStatus#SIGNED} iff every one of its signatures is
 * {@link #SIGNED} (parent Property 16).
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend (no DB i18n column, Requirement 12.1).
 */
public enum SignatureStatus {
    PENDING,
    SIGNED,
    DECLINED
}
