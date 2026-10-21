package com.foremen.dao.model;

/**
 * FOR-06-12 (Requirement 1.1): which side of the per-project conversation authored a
 * {@link ClientMessageEntity}.
 *
 * <p>{@link #CLIENT} — the message is from the project's client; {@link #STAFF} — the message is an
 * admin-side reply (the MANAGER). The side is <strong>derived from the author's project role</strong>
 * at post time (CLIENT role → {@code CLIENT}, otherwise {@code STAFF}), never taken from the request.
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); no DB i18n column — the display label
 * is resolved on the frontend (Requirement 11.1).
 */
public enum AuthorSide {
    CLIENT,
    STAFF
}
