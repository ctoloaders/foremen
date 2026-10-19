package com.foremen.service.payment;

/**
 * The lifecycle status of a {@code PaymentTransaction} (FOR-06-07, design §Data Models).
 *
 * <ul>
 *   <li>{@link #PENDING} — recorded but not yet confirmed by its channel.</li>
 *   <li>{@link #CONFIRMED} — funds confirmed received; the transaction is reconciled against the
 *       schedule's open tranches.</li>
 *   <li>{@link #FAILED} — an online payment attempt failed.</li>
 *   <li>{@link #REFUNDED} — a previously confirmed payment was reversed/refunded.</li>
 * </ul>
 */
public enum TransactionStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    REFUNDED
}
