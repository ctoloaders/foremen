package com.foremen.dao.model;

/**
 * FOR-05-08 (Requirements 5.1, 5.2; parent §11.2): the eIDAS assurance level of a signature.
 *
 * <p>{@link #SES} (simple), {@link #AdES} (advanced), {@link #QES} (qualified). {@link #AdES} is the
 * default applied at the service layer when no level is specified.
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend (no DB i18n column, Requirement 12.1).
 */
public enum SignatureLevel {
    SES,
    AdES,
    QES
}
