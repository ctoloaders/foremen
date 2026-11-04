package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 1.1, 2.1): lifecycle status of a {@code Bank_Connection}
 * ({@code bank_connections.status}, {@code varchar(24)}).
 */
public enum BankConnectionStatus {
    PENDING_AUTH,
    ACTIVE,
    CONSENT_EXPIRING,
    REAUTH_REQUIRED,
    ERROR,
    DISABLED
}
