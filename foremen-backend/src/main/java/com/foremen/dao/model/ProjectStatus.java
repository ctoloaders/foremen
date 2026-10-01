package com.foremen.dao.model;

/**
 * Lifecycle status of a {@code Project}.
 *
 * <p>The offer-preparation slice (FOR-05-07) inserts the {@link #READY_TO_OFFER}, {@link #OFFERED},
 * and {@link #APPROVED} stages between {@link #DRAFT} and {@link #ACTIVE}. FOR-05-07 drives only the
 * {@code READY_TO_OFFER → OFFERED} (offer sent) and {@code OFFERED → APPROVED} (client approves)
 * transitions; the {@code APPROVED → ACTIVE} transition is owned by FOR-05-09 (contract).
 */
public enum ProjectStatus {
    DRAFT,
    READY_TO_OFFER,
    OFFERED,
    APPROVED,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    CANCELLED
}
