package com.foremen.service.bank.connector;

/**
 * FOR-07-04 (Requirement 4.2): provider-side booking status of a {@link NormalizedTransaction}.
 * Connector-level counterpart of the persisted transaction status.
 */
public enum TxnStatus {
    POSTED,
    PENDING
}
