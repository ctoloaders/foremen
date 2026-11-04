package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 3.7): result of verifying a bank account holder against the company profile.
 *
 * <p>{@link #MATCH} — the NIP or the normalized name matches the company; {@link #MISMATCH} — holder
 * data is present but nothing matches; {@link #UNKNOWN} — no holder data or no company profile.
 * Stored as a string ({@code varchar(10)}).
 */
public enum HolderMatch {
    MATCH,
    MISMATCH,
    UNKNOWN
}
