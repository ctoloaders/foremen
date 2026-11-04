package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 3.1, 27.1): the type of a ledger account in {@code bank_accounts.type}.
 *
 * <p>{@link #BANK} — a provider-backed bank account (connection + external id required);
 * {@link #CASH} — a company cash register (no connection, no external id). Stored as a string
 * ({@code varchar(8)}).
 */
public enum BankAccountType {
    BANK,
    CASH
}
