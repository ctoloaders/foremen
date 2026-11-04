package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 13.1, 13.7, 17.3): the target kind of a ledger link, a suggestion and a
 * counterparty rule ({@code kind} / {@code target_kind}, {@code varchar(24)}). A per-kind DB CHECK
 * enforces the required target column.
 */
public enum LinkKind {
    WORKER_PAYOUT,
    PROCUREMENT,
    CLIENT_PAYMENT,
    OTHER_EXPENSE,
    OTHER_INCOME,
    INTERNAL_TRANSFER
}
