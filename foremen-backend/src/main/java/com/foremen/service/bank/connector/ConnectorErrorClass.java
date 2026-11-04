package com.foremen.service.bank.connector;

/**
 * FOR-07-04 (Requirement 4; design §Error mapping): classification of a connector failure, driving
 * the run status, the connection status and the localized error code.
 */
public enum ConnectorErrorClass {
    /** Application-level configuration / credentials / signature problem → connection ERROR. */
    CONFIG,
    /** Consent expired / revoked / connection gone → connection REAUTH_REQUIRED. */
    REAUTH,
    /** Provider refresh / rate / connection limit → run PARTIAL, connection unchanged. */
    LIMIT,
    /** Bank unavailable / inactive / needs own keys → connection ERROR. */
    PROVIDER,
    /** Network / 5xx / timeout / unknown → retried, then ERROR. */
    TRANSIENT;

    /** Only {@link #TRANSIENT} failures are retried. */
    public boolean isRetryable() {
        return this == TRANSIENT;
    }
}
