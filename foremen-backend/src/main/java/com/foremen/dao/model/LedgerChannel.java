package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 27.1): the channel of a ledger line ({@code bank_transactions.channel},
 * {@code varchar(8)}); always equals the owning account's {@link BankAccountType}.
 */
public enum LedgerChannel {
    BANK,
    CASH
}
