package com.foremen.service.bank.connector;

import java.time.Instant;

/**
 * FOR-07-04 (Requirements 1.4, 4.1): provider-side state of a connection (status + consent expiry +
 * identifiers), returned by authorization completion and state reads.
 *
 * @param status                 status hint
 * @param externalConnectionId   provider connection id
 * @param externalConsentId      provider consent id; nullable
 * @param consentExpiresAt       consent expiry; nullable
 * @param nextRefreshPossibleAt  earliest allowed refresh; nullable
 * @param providerCode           provider's bank code; nullable
 * @param bankName               provider-reported bank name; nullable
 * @param bankLogoUrl            provider-reported logo URL; nullable
 */
public record ConnectionState(
        ConnectionStatusHint status,
        String externalConnectionId,
        String externalConsentId,
        Instant consentExpiresAt,
        Instant nextRefreshPossibleAt,
        String providerCode,
        String bankName,
        String bankLogoUrl) {
}
