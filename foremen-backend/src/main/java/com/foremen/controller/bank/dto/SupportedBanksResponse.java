package com.foremen.controller.bank.dto;

import java.time.Instant;
import java.util.List;

import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.account.SupportedBanksService.EmptyReason;
import com.foremen.service.bank.connector.ProviderBank;

/**
 * FOR-07-04 (Requirements 9.2, 19.9) — the live supported-bank catalogue after the tab filters.
 *
 * @param banks       the filtered banks
 * @param totalCount  number of banks the provider returned before filtering
 * @param fetchedAt   when the provider was last asked (cache time)
 * @param emptyReason non-null when no real bank of the country is available (not an error)
 */
public record SupportedBanksResponse(
        FeedProvider provider,
        SaltEdgeMode mode,
        String country,
        List<ProviderBank> banks,
        int totalCount,
        Instant fetchedAt,
        EmptyReason emptyReason) {
}
