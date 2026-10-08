package com.foremen.dao.model;

/**
 * FOR-05-08 (Requirement 7.1): the kind of a {@link DocumentMediaEntity} evidence/attachment.
 *
 * <p>{@link #SCAN} (wet-ink scan for {@code PRINT}), {@link #TABLET_INITIAL} (captured on-tablet
 * parafka for {@code TABLET_INITIALS}), {@link #RENDERED_BODY} (a rendered body artifact),
 * {@link #ATTACHMENT} (a generic attachment).
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend (no DB i18n column, Requirement 12.1).
 */
public enum DocumentMediaKind {
    SCAN,
    TABLET_INITIAL,
    RENDERED_BODY,
    ATTACHMENT
}
