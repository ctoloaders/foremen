package com.foremen.dao.model;

/**
 * FOR-05-08 (Requirement 1.2): the lifecycle status of a {@link SignableDocumentEntity}.
 *
 * <p>A document is created {@link #DRAFT}, frozen into {@link #PENDING_SIGNATURES} on
 * request-signatures, and reaches {@link #SIGNED} <b>only</b> as a derived consequence of every
 * {@link DocumentSignatureEntity} being {@code SIGNED} (never set imperatively —
 * {@code recomputeSignedState}). {@link #VOID} is the discarded terminal state. {@link #SIGNED} and
 * {@link #VOID} are terminal: no action exits them (design §Signing lifecycle, Property 2).
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend (no DB i18n column, Requirement 12.1).
 */
public enum DocumentStatus {
    DRAFT,
    PENDING_SIGNATURES,
    SIGNED,
    VOID
}
