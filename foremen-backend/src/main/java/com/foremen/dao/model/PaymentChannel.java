package com.foremen.dao.model;

/**
 * FOR-06-11 (Requirement 2.1) — how a {@link WorkerPayoutEntity} was paid to the worker. Stored on
 * {@code worker_payouts.payment_channel} (VARCHAR(16), NOT NULL) as the enum name (changeset
 * {@code 180-create-worker-payouts.xml}).
 *
 * <p>The two values carry no DB i18n columns; each resolves to a localized display name on the
 * frontend via the enum-label i18n convention (PL + RU), and a raw enum value is never shown to the
 * user (Requirement 2.3, 8.1).
 */
public enum PaymentChannel {
    /** Paid by bank transfer. */
    BANK_TRANSFER,
    /** Paid in cash. */
    CASH
}
