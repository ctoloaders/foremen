package com.foremen.controller.bank.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;

/**
 * FOR-07-04 (Requirements 1.3–1.8, 2.3, 19.3, 21.6) — read model of a bank connection.
 *
 * <p>Never carries credentials or provider secrets: per-connection credentials are reported only as
 * {@link #credentialsSet()} / {@link #credentialsUpdatedAt()} (R2.3); the provider customer / consent
 * ids and the stored holder tax ids are not exposed.
 *
 * @param accounts the connection's bank accounts — filled on the detail endpoint only ({@code null} in
 *                 the list)
 */
public record BankConnectionDto(
        Long id,
        FeedProvider provider,
        SaltEdgeMode providerMode,
        String providerCode,
        String bankName,
        String bankLogoUrl,
        String displayName,
        BankConnectionStatus status,
        OffsetDateTime consentExpiresAt,
        OffsetDateTime lastSyncAt,
        OffsetDateTime lastSuccessfulSyncAt,
        OffsetDateTime nextRefreshPossibleAt,
        String lastErrorCode,
        String lastErrorMessage,
        String externalConnectionId,
        int accountsCount,
        boolean credentialsRequired,
        boolean credentialsSet,
        OffsetDateTime credentialsUpdatedAt,
        LocalDateTime createdDate,
        List<BankAccountDto> accounts) {
}
