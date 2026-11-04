package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 13.5): the derived link status of a ledger line (bank or cash transaction).
 *
 * <p>Persisted as a derived cache in {@code bank_transactions.link_status}
 * ({@code @Enumerated(EnumType.STRING)}) and always recomputed by
 * {@link com.foremen.service.bank.link.LinkStatusDeriver} after every link / suggestion / ignore
 * mutation. The display label is resolved on the frontend ({@code bankTransactions.linkStatus.*}).
 */
public enum LinkStatus {
    UNLINKED,
    SUGGESTED,
    AUTO_LINKED,
    PARTIALLY_LINKED,
    LINKED,
    IGNORED
}
