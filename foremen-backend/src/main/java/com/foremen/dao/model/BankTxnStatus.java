package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 6.1): booking status of a ledger line ({@code bank_transactions.status},
 * {@code varchar(8)}). Cash lines are always {@link #POSTED}; {@code booking_date} is NOT NULL when
 * {@link #POSTED}.
 */
public enum BankTxnStatus {
    PENDING,
    POSTED
}
