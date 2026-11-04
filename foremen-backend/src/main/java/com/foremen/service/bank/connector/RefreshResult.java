package com.foremen.service.bank.connector;

import java.net.URI;
import java.time.Instant;

/**
 * FOR-07-04 (Requirements 1.6, 9.5): result of {@link BankFeedConnector#requestRefresh}.
 *
 * @param started               {@code true} when the provider refresh was requested;
 *                              {@code false} when it is not yet possible
 * @param nextRefreshPossibleAt earliest allowed next refresh; nullable when unknown
 * @param widgetUrl             URL for an interactive refresh (user may need SCA); nullable
 */
public record RefreshResult(boolean started, Instant nextRefreshPossibleAt, URI widgetUrl) {

    /** Refresh not possible yet; the provider was not called. */
    public static RefreshResult notYet(Instant nextRefreshPossibleAt) {
        return new RefreshResult(false, nextRefreshPossibleAt, null);
    }

    /** Refresh requested at the provider. */
    public static RefreshResult started(Instant nextRefreshPossibleAt, URI widgetUrl) {
        return new RefreshResult(true, nextRefreshPossibleAt, widgetUrl);
    }
}
