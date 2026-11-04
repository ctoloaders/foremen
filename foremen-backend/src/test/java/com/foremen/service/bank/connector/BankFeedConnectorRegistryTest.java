package com.foremen.service.bank.connector;

import com.foremen.dao.model.FeedProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FOR-07-04 (Requirements 4.3, 4.5): unit tests for {@link BankFeedConnectorRegistry}.
 *
 * <p>Plain JUnit 5 (no Spring context): the registry is built from in-test connector instances.
 */
class BankFeedConnectorRegistryTest {

    @Test
    @DisplayName("maps connectors by provider and lists only available ones in enum order (R4.3, R4.5)")
    void mapsByProviderAndListsAvailable() {
        TestConnector stub = new TestConnector(FeedProvider.STUB, true);
        TestConnector saltEdge = new TestConnector(FeedProvider.SALT_EDGE, false);
        BankFeedConnectorRegistry registry = new BankFeedConnectorRegistry(List.of(stub, saltEdge));

        assertThat(registry.find(FeedProvider.STUB)).containsSame(stub);
        assertThat(registry.find(FeedProvider.SALT_EDGE)).containsSame(saltEdge);
        assertThat(registry.available()).containsExactly(FeedProvider.STUB);
        assertThat(registry.isAvailable(FeedProvider.SALT_EDGE)).isFalse();
        assertThat(registry.all()).containsExactly(saltEdge, stub);
        assertThat(registry.require(FeedProvider.STUB)).isSameAs(stub);
    }

    @Test
    @DisplayName("require throws CONFIG for an unavailable or missing connector")
    void requireRejectsUnavailableOrMissing() {
        BankFeedConnectorRegistry registry = new BankFeedConnectorRegistry(
                List.of(new TestConnector(FeedProvider.SALT_EDGE, false)));

        assertThatThrownBy(() -> registry.require(FeedProvider.SALT_EDGE))
                .isInstanceOfSatisfying(ConnectorError.class, e -> {
                    assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);
                    assertThat(e.getMessageKey()).isEqualTo("error.bank.provider.config");
                    assertThat(e.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> registry.require(FeedProvider.STUB))
                .isInstanceOfSatisfying(ConnectorError.class,
                        e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG));
        assertThat(registry.find(FeedProvider.STUB)).isEmpty();
    }

    @Test
    @DisplayName("no connector beans is valid: nothing available, start-up does not fail (R4.3)")
    void emptyRegistry() {
        BankFeedConnectorRegistry registry = new BankFeedConnectorRegistry(List.of());

        assertThat(registry.available()).isEmpty();
        assertThat(registry.all()).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    @DisplayName("two connectors for the same provider fail fast")
    void duplicateProviderFails() {
        List<TestConnector> duplicates = List.of(
                new TestConnector(FeedProvider.STUB, true), new TestConnector(FeedProvider.STUB, true));

        assertThatThrownBy(() -> new BankFeedConnectorRegistry(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STUB");
    }

    /** Minimal connector: only provider() / isAvailable() matter to the registry. */
    private record TestConnector(FeedProvider provider, boolean isAvailable) implements BankFeedConnector {

        @Override
        public List<CredentialField> credentialFields() {
            return List.of();
        }

        @Override
        public List<ProviderBank> listBanks(String countryCode) {
            return List.of();
        }

        @Override
        public AuthorizationStart startAuthorization(BankConnectionRef c, String providerBankCode, URI returnUrl) {
            return new AuthorizationStart(returnUrl, null, null);
        }

        @Override
        public ConnectionState completeAuthorization(BankConnectionRef c, Map<String, String> returnParams) {
            return fetchConnectionState(c);
        }

        @Override
        public AuthorizationStart startReconnect(BankConnectionRef c, URI returnUrl) {
            return new AuthorizationStart(returnUrl, null, null);
        }

        @Override
        public RefreshResult requestRefresh(BankConnectionRef c, RefreshMode mode) {
            return RefreshResult.started(null, null);
        }

        @Override
        public ConnectionState fetchConnectionState(BankConnectionRef c) {
            return new ConnectionState(ConnectionStatusHint.ACTIVE, null, null, null, null, null, null, null);
        }

        @Override
        public Optional<HolderInfo> fetchHolderInfo(BankConnectionRef c) {
            return Optional.empty();
        }

        @Override
        public List<NormalizedAccount> fetchAccounts(BankConnectionRef c) {
            return List.of();
        }

        @Override
        public TransactionPage fetchTransactions(BankConnectionRef c, NormalizedAccountRef a, String cursor) {
            return new TransactionPage(List.of(), List.of(), null, true, 0);
        }

        @Override
        public void disable(BankConnectionRef c) {
        }
    }
}
