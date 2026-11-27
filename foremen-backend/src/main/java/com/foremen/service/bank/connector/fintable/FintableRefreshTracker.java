package com.foremen.service.bank.connector.fintable;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FOR-07-04a (Requirement 9.7; design §awaitProviderSync): best-effort, in-memory marks per Foremen
 * connection id: when this application last requested a Fintable refresh and when it last minted an
 * authorization link. Lost on restart; callers fall back to the provider state check.
 */
public class FintableRefreshTracker {

    private final Map<Long, Instant> refreshRequested = new ConcurrentHashMap<>();
    private final Map<Long, Instant> linkMinted = new ConcurrentHashMap<>();

    public void markRefreshRequested(Long connectionId, Instant at) {
        if (connectionId != null && at != null) {
            refreshRequested.put(connectionId, at);
        }
    }

    /** When a refresh was last requested for the connection; {@code null} when none is pending. */
    public Instant refreshRequestedAt(Long connectionId) {
        return connectionId == null ? null : refreshRequested.get(connectionId);
    }

    public void clearRefresh(Long connectionId) {
        if (connectionId != null) {
            refreshRequested.remove(connectionId);
        }
    }

    public void markLinkMinted(Long connectionId, Instant at) {
        if (connectionId != null && at != null) {
            linkMinted.put(connectionId, at);
        }
    }

    /** When an authorization link was last minted for the connection; {@code null} when unknown. */
    public Instant linkMintedAt(Long connectionId) {
        return connectionId == null ? null : linkMinted.get(connectionId);
    }

    public void forget(Long connectionId) {
        if (connectionId != null) {
            refreshRequested.remove(connectionId);
            linkMinted.remove(connectionId);
        }
    }
}
