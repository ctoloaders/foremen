package com.foremen.service.payment;

/**
 * The channel a {@code PaymentTransaction} arrived through (FOR-06-07, design §Data Models).
 *
 * <ul>
 *   <li>{@link #BANK_TRANSFER} — ingested from the mass-collect bank feed.</li>
 *   <li>{@link #ONLINE} — a przelewy24 online payment confirmed via webhook.</li>
 *   <li>{@link #MANUAL} — an operator-recorded settlement / mark-tranche-paid.</li>
 * </ul>
 */
public enum PaymentChannel {
    BANK_TRANSFER,
    ONLINE,
    MANUAL
}
