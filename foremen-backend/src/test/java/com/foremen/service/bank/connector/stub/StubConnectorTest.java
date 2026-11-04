package com.foremen.service.bank.connector.stub;

import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.TxnStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FOR-07-04 (Requirement 4.4): unit tests for the {@code STUB} connector and its in-memory store.
 *
 * <p>Plain JUnit 5; the conditional registration is checked with an {@link ApplicationContextRunner}.
 */
class StubConnectorTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final URI RETURN_URL = URI.create("http://localhost:3000/bank/connections/return?connectionId=7");

    private StubDataStore store;
    private StubProperties properties;
    private StubConnector connector;
    private BankConnectionRef ref;

    @BeforeEach
    void setUp() {
        store = new StubDataStore();
        properties = new StubProperties();
        properties.setPageSize(2);
        connector = new StubConnector(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        ref = new BankConnectionRef(7L, FeedProvider.STUB, null, null, null, null, null);
    }

    @Test
    @DisplayName("provider STUB, available, no credential fields, two stub banks (business + personal)")
    void basics() {
        assertThat(connector.provider()).isEqualTo(FeedProvider.STUB);
        assertThat(connector.isAvailable()).isTrue();
        assertThat(connector.credentialFields()).isEmpty();
        List<ProviderBank> banks = connector.listBanks("PL");
        assertThat(banks).extracting(ProviderBank::code)
                .containsExactly(StubConnector.BUSINESS_BANK_CODE, StubConnector.PERSONAL_BANK_CODE);
        assertThat(banks).flatExtracting(b -> new ArrayList<>(b.accountTypes()))
                .containsExactly("business", "personal");
    }

    @Test
    @DisplayName("authorization is instant: URL = caller return URL + stub=1; complete ⇒ ACTIVE with consent")
    void instantAuthorization() {
        assertThat(connector.fetchConnectionState(ref).status()).isEqualTo(ConnectionStatusHint.PENDING_AUTH);

        AuthorizationStart start = connector.startAuthorization(ref, StubConnector.PERSONAL_BANK_CODE, RETURN_URL);
        assertThat(start.authorizationUrl())
                .isEqualTo(URI.create("http://localhost:3000/bank/connections/return?connectionId=7&stub=1"));
        assertThat(connector.fetchConnectionState(ref).status()).isEqualTo(ConnectionStatusHint.PENDING_AUTH);

        ConnectionState state = connector.completeAuthorization(ref, Map.of("stub", "1"));
        assertThat(state.status()).isEqualTo(ConnectionStatusHint.ACTIVE);
        assertThat(state.externalConnectionId()).isEqualTo("stub-conn-7");
        assertThat(state.providerCode()).isEqualTo(StubConnector.PERSONAL_BANK_CODE);
        assertThat(state.bankName()).isEqualTo("Stub Bank Personal");
        assertThat(state.consentExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));

        assertThat(StubConnector.instantAuthorizationUrl(URI.create("http://h/return")))
                .isEqualTo(URI.create("http://h/return?stub=1"));
        assertThat(StubConnector.instantAuthorizationUrl(URI.create("http://h/r?a=1#x")))
                .isEqualTo(URI.create("http://h/r?a=1&stub=1#x"));
    }

    @Test
    @DisplayName("seeded state: consentExpiresAt kept, REAUTH ⇒ REAUTH_REQUIRED + reads fail, DISABLED after disable")
    void seededStateAndDisable() {
        Instant expires = Instant.parse("2027-05-01T00:00:00Z");
        store.seed(7L, new StubSeed(null, new StubSeed.State(StubSeed.Status.ACTIVE, expires), null, null, null, null));
        connector.completeAuthorization(ref, Map.of());
        assertThat(connector.fetchConnectionState(ref).consentExpiresAt()).isEqualTo(expires);

        store.seed(7L, new StubSeed(StubSeed.Mode.APPEND, new StubSeed.State(StubSeed.Status.REAUTH, null),
                null, null, null, null));
        assertThat(connector.fetchConnectionState(ref).status()).isEqualTo(ConnectionStatusHint.REAUTH_REQUIRED);
        assertThatThrownBy(() -> connector.fetchAccounts(ref))
                .isInstanceOfSatisfying(ConnectorError.class,
                        e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.REAUTH));

        connector.disable(ref);
        assertThat(connector.fetchConnectionState(ref).status()).isEqualTo(ConnectionStatusHint.DISABLED);
    }

    @Test
    @DisplayName("accounts and holder come from the seed; empty holder ⇒ Optional.empty")
    void accountsAndHolder() {
        assertThat(connector.fetchHolderInfo(ref)).isEmpty();
        store.seed(7L, seed(StubSeed.Mode.REPLACE, List.of(account("acc-1")), List.of()));

        List<NormalizedAccount> accounts = connector.fetchAccounts(ref);
        assertThat(accounts).singleElement().satisfies(a -> {
            assertThat(a.externalId()).isEqualTo("acc-1");
            assertThat(a.iban()).isEqualTo("PL61109010140000071219812874");
            assertThat(a.balance()).isEqualByComparingTo("10000.00");
            assertThat(a.nature()).isEqualTo("account");
            assertThat(a.balanceAt()).isEqualTo(NOW);
        });
        assertThat(connector.fetchHolderInfo(ref)).contains(
                new HolderInfo(List.of("Foremen Sp. z o.o."), List.of("5250000000")));
    }

    @Test
    @DisplayName("transactions honour cursors (page size) and return pending with the first page only")
    void cursorAndPending() {
        store.seed(7L, seed(StubSeed.Mode.REPLACE, List.of(account("acc-1"), account("acc-2")), List.of(
                txn("acc-1", "t-1", TxnStatus.POSTED), txn("acc-1", "t-2", TxnStatus.POSTED),
                txn("acc-1", "p-1", TxnStatus.PENDING), txn("acc-1", "t-3", TxnStatus.POSTED),
                txn("acc-2", "x-1", TxnStatus.POSTED))));
        NormalizedAccountRef acc1 = new NormalizedAccountRef("acc-1");

        TransactionPage first = connector.fetchTransactions(ref, acc1, null);
        assertThat(first.posted()).extracting(NormalizedTransaction::externalId).containsExactly("t-1", "t-2");
        assertThat(first.pending()).extracting(NormalizedTransaction::externalId).containsExactly("p-1");
        assertThat(first.pending().get(0).status()).isEqualTo(TxnStatus.PENDING);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.complete()).isFalse();

        TransactionPage second = connector.fetchTransactions(ref, acc1, first.nextCursor());
        assertThat(second.posted()).extracting(NormalizedTransaction::externalId).containsExactly("t-3");
        assertThat(second.pending()).isEmpty();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.complete()).isTrue();

        NormalizedTransaction t1 = first.posted().get(0);
        assertThat(t1.counterpartyIbanRaw()).isEqualTo("PL27114020040000300201355387");
        assertThat(t1.counterpartyRaw().get("name").asText()).isEqualTo("Jan Kowalski");
        assertThat(t1.payload().get("stub").asBoolean()).isTrue();

        // Re-seeding the pending row as POSTED (APPEND) replaces it, like a real provider.
        store.seed(7L, seed(StubSeed.Mode.APPEND, List.of(), List.of(txn("acc-1", "p-1", TxnStatus.POSTED))));
        properties.setPageSize(10);
        TransactionPage all = connector.fetchTransactions(ref, acc1, null);
        assertThat(all.posted()).extracting(NormalizedTransaction::externalId).containsExactly("t-1", "t-2", "p-1", "t-3");
        assertThat(all.pending()).isEmpty();
        assertThat(all.complete()).isTrue();

        assertThatThrownBy(() -> connector.fetchTransactions(ref, acc1, "abc")).isInstanceOf(ConnectorError.class);
    }

    @Test
    @DisplayName("failNextFetch throws its error class exactly once; REPLACE clears previous data")
    void failNextFetchOnceAndReplace() {
        store.seed(7L, new StubSeed(null, null, null, ConnectorErrorClass.TRANSIENT,
                List.of(account("acc-1")), List.of(txn("acc-1", "t-1", TxnStatus.POSTED))));
        NormalizedAccountRef acc1 = new NormalizedAccountRef("acc-1");

        assertThatThrownBy(() -> connector.fetchTransactions(ref, acc1, null))
                .isInstanceOfSatisfying(ConnectorError.class, e -> {
                    assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT);
                    assertThat(e.isRetryable()).isTrue();
                });
        assertThat(connector.fetchTransactions(ref, acc1, null).posted()).hasSize(1);

        store.seed(7L, new StubSeed(StubSeed.Mode.REPLACE, null, null, null, List.of(account("acc-9")), null));
        assertThat(connector.fetchAccounts(ref)).extracting(NormalizedAccount::externalId).containsExactly("acc-9");
        assertThat(connector.fetchTransactions(ref, acc1, null).posted()).isEmpty();
        assertThat(connector.fetchHolderInfo(ref)).isEmpty();
    }

    @Test
    @DisplayName("beans are registered only when foremen.bank-feed.stub.enabled=true")
    void conditionalRegistration() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(StubConnectorConfig.class);
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(BankFeedConnector.class));
        runner.withPropertyValues("foremen.bank-feed.stub.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BankFeedConnector.class));
        runner.withPropertyValues("foremen.bank-feed.stub.enabled=true", "foremen.bank-feed.stub.page-size=5")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StubConnector.class).hasSingleBean(StubDataStore.class);
                    assertThat(ctx.getBean(StubProperties.class).getPageSize()).isEqualTo(5);
                });
    }

    // ------------------------------------------------------------------ fixtures

    private static StubSeed seed(StubSeed.Mode mode, List<StubSeed.Account> accounts, List<StubSeed.Transaction> txns) {
        return new StubSeed(mode, new StubSeed.State(StubSeed.Status.ACTIVE, null),
                new HolderInfo(List.of("Foremen Sp. z o.o."), List.of("5250000000")), null, accounts, txns);
    }

    private static StubSeed.Account account(String id) {
        return new StubSeed.Account(id, "Firmowe PLN", null, "PL61109010140000071219812874", null, "PLN",
                new BigDecimal("10000.00"), new BigDecimal("10000.00"), "Foremen Sp. z o.o.", null);
    }

    private static StubSeed.Transaction txn(String accountId, String id, TxnStatus status) {
        LocalDate date = LocalDate.of(2026, 11, 2);
        return new StubSeed.Transaction(accountId, id, status, new BigDecimal("-1500.00"), "PLN", date, date,
                "Wynagrodzenie 10/2026", "Jan Kowalski", "PL27114020040000300201355387", null, false);
    }
}
