package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 13.1): where a link or a suggestion on a ledger line came from.
 *
 * <p>{@link #RULE} — a counterparty rule / deterministic matcher; {@link #AI} — the AI classifier;
 * {@link #MANUAL} — created by a user. Stored as a string ({@code varchar(8)}).
 */
public enum DetectionSource {
    RULE,
    AI,
    MANUAL
}
