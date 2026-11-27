package com.foremen.service.bank.connector;

import com.foremen.dao.model.FeedProvider;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FOR-07-04 (Requirement 4): the provider-agnostic {@code Bank_Feed_Connector} SPI.
 *
 * <p>Connectors are Spring beans selected per connection by {@link #provider()} through
 * {@link BankFeedConnectorRegistry} (Requirement 4.3). They only talk to the provider and return
 * normalized DTOs; they never see JPA entities ({@link BankConnectionRef} is a read-only view) and
 * never write ledger tables (Requirement 4.2). A connector whose configuration is missing reports
 * {@link #isAvailable()} {@code = false} instead of failing start-up.
 *
 * <p>Provider failures are thrown as {@link ConnectorError} carrying a {@link ConnectorErrorClass}.
 */
public interface BankFeedConnector {

    /** The provider this connector serves; unique across all connector beans. */
    FeedProvider provider();

    /** {@code false} when the connector's configuration is missing or invalid (Requirement 4.3, 4.5). */
    boolean isAvailable();

    /** Per-connection credential fields the frontend renders (Requirement 2.2); empty for SALT_EDGE. */
    List<CredentialField> credentialFields();

    /** Live supported-bank catalogue for the picker (Requirement 9.2); may be empty. */
    List<ProviderBank> listBanks(String countryCode);

    /** Opens an authorization session and returns the URL the user is redirected to (Requirement 9.1). */
    AuthorizationStart startAuthorization(BankConnectionRef connection, String providerBankCode, URI returnUrl);

    /** Completes authorization from return-URL / callback parameters; idempotent (Requirement 9.3). */
    ConnectionState completeAuthorization(BankConnectionRef connection, Map<String, String> returnParams);

    /** Renews consent for the same provider connection (Requirement 9.4). */
    AuthorizationStart startReconnect(BankConnectionRef connection, URI returnUrl);

    /** Requests a provider-side refresh; returns "not yet possible" without calling the provider when limited (Requirement 9.5). */
    RefreshResult requestRefresh(BankConnectionRef connection, RefreshMode mode);

    /** Reads the provider-side status and consent expiry. */
    ConnectionState fetchConnectionState(BankConnectionRef connection);

    /** Account holder data (Requirement 3.7); empty when the provider / bank does not support it. */
    Optional<HolderInfo> fetchHolderInfo(BankConnectionRef connection);

    /** All accounts the provider currently returns for the connection. */
    List<NormalizedAccount> fetchAccounts(BankConnectionRef connection);

    /** One page of posted + pending transactions after {@code cursor} ({@code null} = from the start). */
    TransactionPage fetchTransactions(BankConnectionRef connection, NormalizedAccountRef account, String cursor);

    /** Revokes provider-side access; already-revoked / not-found is treated as success. */
    void disable(BankConnectionRef connection);

    /**
     * The resume cursor to store for an account after one fetch ({@code pages} = all pages read, in order).
     * The cursor is opaque per connector. Default: {@code null}, i.e. the next sync re-reads from the start
     * and the idempotent ingest de-duplicates.
     */
    default String resumeCursor(String previous, List<TransactionPage> pages) {
        return null;
    }

    /**
     * Waits (at most {@code maxWait}) until the provider has finished its own asynchronous sync of the
     * connection, so the following pull reads fresh data. Default: no-op. Called by the orchestrator after
     * {@link #fetchConnectionState} and before {@link #fetchAccounts}; implementations should return normally
     * on timeout and may throw {@link ConnectorError}.
     */
    default void awaitProviderSync(BankConnectionRef connection, Duration maxWait) {
        // no-op
    }

    /** The longest the orchestrator waits in {@link #awaitProviderSync}. Default: {@link Duration#ZERO}. */
    default Duration providerSyncMaxWait() {
        return Duration.ZERO;
    }

    /** {@code true} when the catalogue is search-only (no "list all"): use {@link #searchBanks}. */
    default boolean catalogueSearchOnly() {
        return false;
    }

    /** Catalogue search for search-only providers; {@code q} blank = popular banks. Default: empty. */
    default List<ProviderBank> searchBanks(String countryCode, String query) {
        return List.of();
    }
}
