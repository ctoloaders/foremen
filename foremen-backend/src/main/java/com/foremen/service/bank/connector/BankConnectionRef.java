package com.foremen.service.bank.connector;

import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;

/**
 * FOR-07-04 (Requirement 4.2): read-only view of a {@code Bank_Connection} handed to connectors, so
 * connectors never see JPA entities.
 *
 * @param id                   Foremen connection id
 * @param provider             feed provider of the connection
 * @param providerMode         Salt Edge mode the connection was created in; {@code null} for non-Salt-Edge providers
 * @param externalCustomerId   provider customer / lead id; {@code null} until known
 * @param externalConnectionId provider connection id; {@code null} until authorized
 * @param externalConsentId    provider consent id; {@code null} until known
 * @param providerCode         provider's bank code (e.g. Salt Edge {@code provider_code}); nullable
 */
public record BankConnectionRef(
        Long id,
        FeedProvider provider,
        SaltEdgeMode providerMode,
        String externalCustomerId,
        String externalConnectionId,
        String externalConsentId,
        String providerCode) {
}
