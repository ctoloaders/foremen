package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 27.2): the business source of a {@link LedgerOrigin#SYSTEM} cash line
 * ({@code bank_transactions.system_source}, {@code varchar(32)}). Together with
 * {@code system_source_id} (payout id / document id) it is partially UNIQUE, so one source produces at
 * most one SYSTEM line.
 */
public enum SystemSource {
    PAYROLL_PAYOUT,
    PROCUREMENT_CASH
}
