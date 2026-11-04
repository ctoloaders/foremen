package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 27.1, 27.2): where a ledger line came from ({@code bank_transactions.origin},
 * {@code varchar(10)}). DB CHECK: {@code BANK} ⇒ {@link #BANK_FEED}; {@code CASH} ⇒ {@link #MANUAL} or
 * {@link #SYSTEM}.
 */
public enum LedgerOrigin {
    BANK_FEED,
    MANUAL,
    SYSTEM
}
