package com.foremen.dao.model;

/**
 * FOR-06-11 (Requirement 7) — the two-status lifecycle of a {@link WorkerPayoutEntity}. Stored on
 * {@code worker_payouts.status} (VARCHAR(16), NOT NULL, default {@code PLANNED}) as the enum name
 * (changeset {@code 180-create-worker-payouts.xml}).
 *
 * <p>The lifecycle is {@code PLANNED → PAID}: a payout is created {@link #PLANNED} (mutable only
 * insofar as it can be marked paid or hard-deleted) and transitions to {@link #PAID} on mark-paid.
 * {@link #PAID} is <b>terminal</b> — a paid payout can no longer be edited, re-paid, or deleted
 * (Requirements 7.1, 7.2). Hard-delete is allowed only while {@link #PLANNED} (Requirement 7.3).
 *
 * <p>No DB i18n columns; each value resolves to a localized badge label on the frontend via the
 * enum-label i18n convention (PL + RU) (Requirement 8.1, 8.2).
 */
public enum PayoutStatus {
    /** Created, not yet paid; mutable (mark-paid / hard-delete) only. */
    PLANNED,
    /** Settled; terminal and immutable. */
    PAID
}
