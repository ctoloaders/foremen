package com.foremen.dao.model;

/**
 * FOR-06-12 (Requirement 1.3): the kind of a {@link ClientMessageMediaEntity} attachment, derived
 * from the uploaded file's content type by the media service (image/* → {@link #IMAGE},
 * application/pdf → {@link #DOCUMENT}, video/* → {@link #VIDEO}).
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}) in the {@code kind VARCHAR(24)}
 * column (changeset {@code 185-create-client-message-media.xml}); no DB i18n column — the display
 * label is resolved on the frontend (Requirement 11.1).
 */
public enum MediaKind {
    IMAGE,
    VIDEO,
    DOCUMENT
}
