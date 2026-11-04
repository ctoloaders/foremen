package com.foremen.service.bank.connector;

/**
 * FOR-07-04 (Requirements 1.2, 4.1): the connection status as reported / derived by a connector.
 * The connection service maps it onto the persisted {@code Bank_Connection.status}.
 */
public enum ConnectionStatusHint {
    PENDING_AUTH,
    ACTIVE,
    CONSENT_EXPIRING,
    REAUTH_REQUIRED,
    ERROR,
    DISABLED
}
