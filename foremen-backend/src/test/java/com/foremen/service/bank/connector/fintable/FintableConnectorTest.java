package com.foremen.service.bank.connector.fintable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connection.BoundConnectionIds;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.fintable.dto.FintableTransactionsPage;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FOR-07-04a task 2.4 — {@link FintableConnector} against a mocked {@link FintableClient}: catalogue,
 * authorization, completion (incl. fallback binding), state, holder, accounts, transactions, cursors,
 * refresh spacing, sync waiting and disable (Requirements 4.1-10.2).
 */
class FintableConnectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-11-18T10:00:00Z");

    /** A clock the tests (and the fake sleeper) move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private FintableClient client;
    private BoundConnectionIds bound;
    private FintableRefreshTracker tracker;
    private MutableClock clock;
    private FintableConnector connector;
    private int sleeps;

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode fixture(String name) {
        try (var in = FintableConnectorTest.class.getResourceAsStream("/fintable/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PublicBaseUrl publicBase(String url) {
        return new PublicBaseUrl(url);
    }

    private static FintableProperties properties() {
        return new FintableProperties(true, null, false, "token", null, null, null,
                List.of("PL"), List.of("ENABLEBANKING", "NORDIGEN"), List.of("PKO", "mBank"),
                Duration.ofMinutes(10), Duration.ofSeconds(15), Duration.ofHours(12), Duration.ofHours(1), 30);
    }

    private static BankConnectionRef ref(Long id, String externalId) {
        return new BankConnectionRef(id, FeedProvider.FINTABLE, null, null, externalId, null, "slug");
    }

    @BeforeEach
    void setUp() {
        client = mock(FintableClient.class);
        bound = mock(BoundConnectionIds.class);
        when(bound.externalConnectionIds(FeedProvider.FINTABLE)).thenReturn(Set.of());
        tracker = new FintableRefreshTracker();
        clock = new MutableClock();
        sleeps = 0;
        connector = newConnector(properties(), publicBase("https://foremen.example"));
    }

    private FintableConnector newConnector(FintableProperties props, PublicBaseUrl base) {
        return new FintableConnector(client, props, base, bound, tracker, clock, d -> {
            sleeps++;
            clock.advance(d);
        });
    }

    // ------------------------------------------------------------------ basics

    @Test
    void identityAndAvailability() {
        assertThat(connector.provider()).isEqualTo(FeedProvider.FINTABLE);
        assertThat(connector.isAvailable()).isTrue();
        assertThat(connector.credentialFields()).isEmpty();
        assertThat(connector.catalogueSearchOnly()).isTrue();
        assertThat(connector.listBanks("PL")).isEmpty();
        assertThat(connector.providerSyncMaxWait()).isEqualTo(Duration.ofMinutes(10));
        assertThat(newConnector(properties(), publicBase("")).isAvailable()).isFalse();
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void searchShorterThanThreeCharactersReturnsEmptyWithoutCall() {
        assertThat(connector.searchBanks("PL", "ab")).isEmpty();
        verify(client, never()).institutions(anyString(), anyString());
    }

    @Test
    void searchMapsRowsOrderedByProviderPreferenceAndDropsDefunct() {
        when(client.institutions("test", "PL")).thenReturn(fixture("institutions-search.json"));

        List<ProviderBank> banks = connector.searchBanks("PL", "test");

        assertThat(banks).extracting(ProviderBank::code).doesNotContain("test-bank-old");
        assertThat(banks.get(0).code()).isEqualTo("test-bank-pl-retail-enablebanking");
        assertThat(banks.get(0).via()).isEqualTo("ENABLEBANKING");
        assertThat(banks.get(1).via()).isEqualTo("NORDIGEN");
        assertThat(banks.get(banks.size() - 1).via()).isEqualTo("PLAID");
    }

    @Test
    void searchIsCachedForOneHour() {
        when(client.institutions("test", "PL")).thenReturn(fixture("institutions-search.json"));

        connector.searchBanks("PL", "test");
        clock.advance(Duration.ofMinutes(59));
        connector.searchBanks("PL", "test");
        verify(client, times(1)).institutions("test", "PL");

        clock.advance(Duration.ofMinutes(2));
        connector.searchBanks("PL", "test");
        verify(client, times(2)).institutions("test", "PL");
    }

    @Test
    void blankQueryReturnsPopularBanksMergedAndDeduplicated() {
        when(client.institutions(anyString(), eq("PL"))).thenReturn(fixture("institutions-search.json"));

        List<ProviderBank> banks = connector.searchBanks(null, "");

        verify(client).institutions("PKO", "PL");
        verify(client).institutions("mBank", "PL");
        assertThat(banks).extracting(ProviderBank::code).doesNotHaveDuplicates();
        assertThat(banks).isNotEmpty();
    }

    @Test
    void rateLimitedSearchServesStaleCache() {
        when(client.institutions("test", "PL")).thenReturn(fixture("institutions-search.json"));
        connector.searchBanks("PL", "test");
        clock.advance(Duration.ofHours(2));
        when(client.institutions("test", "PL")).thenThrow(new FintableConnectorError(ConnectorErrorClass.LIMIT,
                null, FintableErrorMapper.KEY_RATE_LIMITED, "limited", 429, 30L, null));

        assertThat(connector.searchBanks("PL", "test")).isNotEmpty();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void startAuthorizationSendsHttpsRedirectUrl() {
        when(client.mintLink("slug-1", "https://foremen.example/return"))
                .thenReturn(json("{\"url\":\"https://fintable.io/link/abc\",\"expires_at\":\"2026-11-18T10:30:00Z\"}"));

        AuthorizationStart start = connector.startAuthorization(ref(7L, null), "slug-1",
                URI.create("https://foremen.example/return"));

        assertThat(start.authorizationUrl()).isEqualTo(URI.create("https://fintable.io/link/abc"));
        assertThat(start.expiresAt()).isEqualTo(Instant.parse("2026-11-18T10:30:00Z"));
        assertThat(tracker.linkMintedAt(7L)).isEqualTo(T0);
    }

    @Test
    void startAuthorizationOmitsRedirectUrlForHttp() {
        when(client.mintLink(eq("slug-1"), isNull()))
                .thenReturn(json("{\"url\":\"https://fintable.io/link/abc\"}"));

        connector.startAuthorization(ref(7L, null), "slug-1", URI.create("http://localhost:3000/return"));

        verify(client).mintLink("slug-1", null);
    }

    @Test
    void secondLinkWithinAMinuteIsRejectedLocallyThenAllowed() {
        when(client.mintLink(anyString(), any())).thenReturn(json("{\"url\":\"https://fintable.io/l\"}"));
        connector.startAuthorization(ref(1L, null), "a", null);
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> connector.startAuthorization(ref(2L, null), "b", null))
                .isInstanceOfSatisfying(FintableConnectorError.class, e -> {
                    assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.LIMIT);
                    assertThat(e.getMessageKey()).isEqualTo(FintableErrorMapper.KEY_RATE_LIMITED);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(40L);
                });
        verify(client, times(1)).mintLink(anyString(), any());

        clock.advance(Duration.ofSeconds(41));
        connector.startAuthorization(ref(2L, null), "b", null);
        verify(client, times(2)).mintLink(anyString(), any());
    }

    @Test
    void failedLinkCallReleasesTheGuard() {
        when(client.mintLink(anyString(), any()))
                .thenThrow(new FintableConnectorError(ConnectorErrorClass.TRANSIENT, null, null, "boom", 503, null,
                        null))
                .thenReturn(json("{\"url\":\"https://fintable.io/l\"}"));

        assertThatThrownBy(() -> connector.startAuthorization(ref(1L, null), "a", null))
                .isInstanceOf(FintableConnectorError.class);
        assertThat(connector.startAuthorization(ref(1L, null), "a", null).authorizationUrl()).isNotNull();
    }

    @Test
    void startReconnectUsesTheExistingConnection() {
        when(client.mintReconnectLink("conn_1", "https://foremen.example/return"))
                .thenReturn(json("{\"url\":\"https://fintable.io/re\"}"));

        AuthorizationStart start = connector.startReconnect(ref(3L, "conn_1"),
                URI.create("https://foremen.example/return"));

        assertThat(start.authorizationUrl()).isEqualTo(URI.create("https://fintable.io/re"));
    }

    // ------------------------------------------------------------------ completion

    private static String connectionJson(String id, String extra) {
        return "{\"id\":\"" + id + "\",\"institution_name\":\"PKO BP\"" + extra + "}";
    }

    @Test
    void completeWithConnectedStatusReadsTheConnection() {
        when(client.connection("conn_x")).thenReturn(json(connectionJson("conn_x", "")));

        ConnectionState state = connector.completeAuthorization(ref(1L, null),
                Map.of("connection_id", "conn_x", "status", "connected", "connectionId", "1"));

        assertThat(state.status()).isEqualTo(ConnectionStatusHint.ACTIVE);
        assertThat(state.externalConnectionId()).isEqualTo("conn_x");
        assertThat(state.bankName()).isEqualTo("PKO BP");
        assertThat(state.consentExpiresAt()).isNull();
        verify(client, never()).connections();
    }

    @Test
    void completeWithErrorStatusStaysPending() {
        ConnectionState state = connector.completeAuthorization(ref(1L, null),
                Map.of("connection_id", "conn_x", "status", "error"));

        assertThat(state.status()).isEqualTo(ConnectionStatusHint.PENDING_AUTH);
        assertThat(state.externalConnectionId()).isNull();
        verify(client, never()).connection(anyString());
    }

    @Test
    void completeWithoutParamsAndNoUnboundCandidateStaysPending() {
        when(client.connections()).thenReturn(json("{\"data\":[{\"id\":\"conn_a\"}]}"));
        when(bound.externalConnectionIds(FeedProvider.FINTABLE)).thenReturn(Set.of("conn_a"));

        ConnectionState state = connector.completeAuthorization(ref(1L, null), Map.of());

        assertThat(state.status()).isEqualTo(ConnectionStatusHint.PENDING_AUTH);
        verify(client, never()).connection(anyString());
    }

    @Test
    void completeWithoutParamsBindsTheSingleUnboundCandidate() {
        tracker.markLinkMinted(1L, T0);
        when(client.connections()).thenReturn(json("{\"data\":["
                + "{\"id\":\"conn_bound\",\"created_at\":\"2026-11-18T10:05:00Z\"},"
                + "{\"id\":\"conn_old\",\"created_at\":\"2026-11-10T10:00:00Z\"},"
                + "{\"id\":\"conn_new\",\"created_at\":\"2026-11-18T10:05:00Z\"}]}"));
        when(bound.externalConnectionIds(FeedProvider.FINTABLE)).thenReturn(Set.of("conn_bound"));
        when(client.connection("conn_new")).thenReturn(json(connectionJson("conn_new", "")));

        ConnectionState state = connector.completeAuthorization(ref(1L, null), null);

        assertThat(state.status()).isEqualTo(ConnectionStatusHint.ACTIVE);
        assertThat(state.externalConnectionId()).isEqualTo("conn_new");
    }

    @Test
    void completeWithoutParamsAndTwoUnboundCandidatesStaysPending() {
        tracker.markLinkMinted(1L, T0);
        when(client.connections()).thenReturn(json("{\"data\":["
                + "{\"id\":\"conn_1\",\"created_at\":\"2026-11-18T10:05:00Z\"},"
                + "{\"id\":\"conn_2\",\"created_at\":\"2026-11-18T10:06:00Z\"}]}"));

        ConnectionState state = connector.completeAuthorization(ref(1L, null), Map.of());

        assertThat(state.status()).isEqualTo(ConnectionStatusHint.PENDING_AUTH);
        verify(client, never()).connection(anyString());
    }

    @Test
    void completeWithoutParamsAfterRestartAcceptsAConnectionFromTheLastDay() {
        when(client.connections()).thenReturn(json("{\"data\":["
                + "{\"id\":\"conn_recent\",\"created_at\":\"2026-11-18T08:00:00Z\"},"
                + "{\"id\":\"conn_ancient\",\"created_at\":\"2026-10-01T08:00:00Z\"}]}"));
        when(client.connection("conn_recent")).thenReturn(json(connectionJson("conn_recent", "")));

        ConnectionState state = connector.completeAuthorization(ref(1L, null), Map.of());

        assertThat(state.externalConnectionId()).isEqualTo("conn_recent");
    }

    @Test
    void completeOfAnAlreadyBoundConnectionRereadsItsState() {
        when(client.connection("conn_1")).thenReturn(json(connectionJson("conn_1", "")));

        ConnectionState state = connector.completeAuthorization(ref(1L, "conn_1"), Map.of());

        assertThat(state.externalConnectionId()).isEqualTo("conn_1");
        verify(client, never()).connections();
    }

    // ------------------------------------------------------------------ state

    @Test
    void stateMappingCoversReauthErrorAndHealthy() {
        when(client.connection("c1")).thenReturn(json(connectionJson("c1", ",\"needs_reconnect\":true,\"healthy\":false")));
        when(client.connection("c2")).thenReturn(json(connectionJson("c2", ",\"healthy\":false,\"needs_reconnect\":false")));
        when(client.connection("c3")).thenReturn(json(connectionJson("c3", ",\"sync_status\":{\"state\":\"failed\"}")));
        when(client.connection("c4")).thenReturn(json(connectionJson("c4", ",\"healthy\":true,\"sync_status\":{\"state\":\"finished\"}")));

        assertThat(connector.fetchConnectionState(ref(1L, "c1")).status()).isEqualTo(ConnectionStatusHint.REAUTH_REQUIRED);
        assertThat(connector.fetchConnectionState(ref(1L, "c2")).status()).isEqualTo(ConnectionStatusHint.ERROR);
        assertThat(connector.fetchConnectionState(ref(1L, "c3")).status()).isEqualTo(ConnectionStatusHint.ERROR);
        ConnectionState ok = connector.fetchConnectionState(ref(1L, "c4"));
        assertThat(ok.status()).isEqualTo(ConnectionStatusHint.ACTIVE);
        assertThat(ok.consentExpiresAt()).isNull();
        assertThat(ok.externalConsentId()).isNull();
    }

    @Test
    void missingConnectionAtFintablePropagatesAsReauth() {
        when(client.connection("gone")).thenThrow(new FintableConnectorError(ConnectorErrorClass.REAUTH, null,
                FintableErrorMapper.KEY_REAUTH, "404", 404, null, null));

        assertThatThrownBy(() -> connector.fetchConnectionState(ref(1L, "gone")))
                .isInstanceOfSatisfying(FintableConnectorError.class,
                        e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.REAUTH));
    }

    @Test
    void stateWithoutExternalIdIsAProviderError() {
        assertThatThrownBy(() -> connector.fetchConnectionState(ref(1L, null)))
                .isInstanceOf(com.foremen.service.bank.connector.ConnectorError.class);
    }

    // ------------------------------------------------------------------ holder and accounts

    private static List<JsonNode> accounts() {
        return List.of(
                json("{\"id\":\"acc_1\",\"display_name\":\"Main\",\"type\":\"depository / checking\","
                        + "\"iban\":\"PL61109010140000071219812874\",\"currency\":\"PLN\",\"balance\":\"10.50\","
                        + "\"holder_name\":\"ACME Sp. z o.o.\"}"),
                json("{\"id\":\"acc_2\",\"display_name\":\"Card\",\"type\":\"credit / credit card\","
                        + "\"currency\":\"PLN\",\"balance\":\"-5.00\",\"holder_name\":\"ACME Sp. z o.o.\"}"),
                json("{\"name\":\"no id\"}"));
    }

    @Test
    void holderInfoIsDistinctNonBlankNames() {
        when(client.accounts("c1")).thenReturn(accounts());

        Optional<HolderInfo> holder = connector.fetchHolderInfo(ref(1L, "c1"));

        assertThat(holder).isPresent();
        assertThat(holder.get().names()).containsExactly("ACME Sp. z o.o.");
        assertThat(holder.get().taxIds()).isEmpty();
    }

    @Test
    void holderInfoIsEmptyWithoutHolderNames() {
        when(client.accounts("c1")).thenReturn(List.of(json("{\"id\":\"acc_1\"}")));

        assertThat(connector.fetchHolderInfo(ref(1L, "c1"))).isEmpty();
    }

    @Test
    void accountsAreMappedWithTheConnectionBalanceTime() {
        when(client.connection("c1")).thenReturn(json(connectionJson("c1",
                ",\"last_successful_update\":\"2026-11-18T09:00:00Z\"")));
        when(client.accounts("c1")).thenReturn(accounts());

        List<NormalizedAccount> result = connector.fetchAccounts(ref(1L, "c1"));

        assertThat(result).extracting(NormalizedAccount::externalId).containsExactly("acc_1", "acc_2");
        assertThat(result.get(0).nature()).isEqualTo("account");
        assertThat(result.get(1).nature()).isEqualTo("card");
        assertThat(result.get(0).balanceAt()).isEqualTo(Instant.parse("2026-11-18T09:00:00Z"));
    }

    // ------------------------------------------------------------------ transactions and cursors

    private static JsonNode tx(String id, String amount, String updatedAt) {
        return json("{\"id\":\"" + id + "\",\"amount\":\"" + amount + "\",\"currency\":\"PLN\","
                + "\"date\":\"2026-11-17\",\"description\":\"d\",\"updated_at\":\"" + updatedAt + "\"}");
    }

    @Test
    void firstFetchOmitsUpdatedSinceAndEncodesTheNextPageCursor() {
        when(client.transactions("acc_1", null, null)).thenReturn(new FintableTransactionsPage(
                List.of(tx("tx_1", "-12.50", "2026-11-18T09:00:00Z"), json("{\"id\":\"tx_bad\",\"amount\":\"x\"}")),
                "CUR1"));

        TransactionPage page = connector.fetchTransactions(ref(1L, "conn_enablebanking_1"),
                new NormalizedAccountRef("acc_1"), null);

        assertThat(page.posted()).hasSize(1);
        assertThat(page.skippedMalformed()).isEqualTo(1);
        assertThat(page.pending()).isEmpty();
        assertThat(page.complete()).isFalse();
        assertThat(page.nextCursor()).isEqualTo("p:|CUR1");
    }

    @Test
    void resumeCursorSubtractsTheOverlapAndPageCursorRoundTrips() {
        Instant since = Instant.parse("2026-11-18T10:00:00Z");
        when(client.transactions("acc_1", since, null)).thenReturn(new FintableTransactionsPage(
                List.of(tx("tx_1", "5.00", "2026-11-18T10:30:00Z")), "CUR9"));

        TransactionPage first = connector.fetchTransactions(ref(1L, "c"), new NormalizedAccountRef("acc_1"),
                "u:2026-11-18T11:00:00Z");

        assertThat(first.nextCursor()).isEqualTo("p:2026-11-18T10:00:00Z|CUR9");

        when(client.transactions("acc_1", since, "CUR9")).thenReturn(new FintableTransactionsPage(
                List.of(tx("tx_2", "6.00", "2026-11-18T10:45:00Z")), null));

        TransactionPage second = connector.fetchTransactions(ref(1L, "c"), new NormalizedAccountRef("acc_1"),
                first.nextCursor());

        assertThat(second.complete()).isTrue();
        assertThat(second.nextCursor()).isNull();
        verify(client).transactions("acc_1", since, "CUR9");
    }

    @Test
    void resumeCursorIsTheMaxUpdatedAtAndNeverMovesBackwards() {
        when(client.transactions(anyString(), any(), any())).thenReturn(new FintableTransactionsPage(
                List.of(tx("tx_1", "1.00", "2026-11-18T09:00:00Z"), tx("tx_2", "2.00", "2026-11-18T09:30:00Z")),
                null));
        TransactionPage page = connector.fetchTransactions(ref(1L, "c"), new NormalizedAccountRef("a"), null);

        assertThat(connector.resumeCursor(null, List.of(page))).isEqualTo("u:2026-11-18T09:30:00Z");
        assertThat(connector.resumeCursor("u:2026-11-18T12:00:00Z", List.of(page))).isEqualTo("u:2026-11-18T12:00:00Z");
        assertThat(connector.resumeCursor("u:2026-11-18T12:00:00Z", List.of())).isEqualTo("u:2026-11-18T12:00:00Z");
        assertThat(connector.resumeCursor(null, List.of())).isNull();
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void interactiveRefreshStartsThenIsSpacedLocally() {
        RefreshResult first = connector.requestRefresh(ref(1L, "c1"), RefreshMode.INTERACTIVE);

        assertThat(first.started()).isTrue();
        assertThat(first.nextRefreshPossibleAt()).isEqualTo(T0.plus(Duration.ofHours(12)));
        verify(client).syncOne("c1");
        assertThat(tracker.refreshRequestedAt(1L)).isEqualTo(T0);

        clock.advance(Duration.ofHours(1));
        RefreshResult second = connector.requestRefresh(ref(1L, "c1"), RefreshMode.INTERACTIVE);

        assertThat(second.started()).isFalse();
        assertThat(second.nextRefreshPossibleAt()).isEqualTo(T0.plus(Duration.ofHours(12)));
        verify(client, times(1)).syncOne("c1");

        clock.advance(Duration.ofHours(12));
        assertThat(connector.requestRefresh(ref(1L, "c1"), RefreshMode.INTERACTIVE).started()).isTrue();
        verify(client, times(2)).syncOne("c1");
    }

    @Test
    void backgroundRefreshNeverCallsFintable() {
        RefreshResult result = connector.requestRefresh(ref(1L, "c1"), RefreshMode.BACKGROUND);

        assertThat(result.started()).isFalse();
        verify(client, never()).syncOne(anyString());
    }

    @Test
    void refreshRateLimitedByFintableBecomesNotYet() {
        when(client.syncOne("c1")).thenThrow(new FintableConnectorError(ConnectorErrorClass.LIMIT, null,
                FintableErrorMapper.KEY_RATE_LIMITED, "429", 429, 120L, null));

        RefreshResult result = connector.requestRefresh(ref(1L, "c1"), RefreshMode.INTERACTIVE);

        assertThat(result.started()).isFalse();
        assertThat(result.nextRefreshPossibleAt()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    void refreshWithoutPlanPropagatesAsConfigError() {
        when(client.syncOne("c1")).thenThrow(new FintableConnectorError(ConnectorErrorClass.CONFIG, null,
                FintableErrorMapper.KEY_NO_PLAN, "403", 403, null, null));

        assertThatThrownBy(() -> connector.requestRefresh(ref(1L, "c1"), RefreshMode.INTERACTIVE))
                .isInstanceOfSatisfying(FintableConnectorError.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(FintableErrorMapper.KEY_NO_PLAN));
    }

    // ------------------------------------------------------------------ awaitProviderSync

    private static JsonNode syncState(String state, String finishedAt) {
        return json("{\"connections\":{\"c1\":{\"sync_status\":{\"state\":\"" + state + "\""
                + (finishedAt == null ? "" : ",\"finished_at\":\"" + finishedAt + "\"") + "}}}}");
    }

    @Test
    void awaitReturnsImmediatelyWhenNothingIsRunning() {
        when(client.sync()).thenReturn(syncState("finished", "2026-11-18T09:00:00Z"));

        connector.awaitProviderSync(ref(1L, "c1"), Duration.ofMinutes(10));

        assertThat(sleeps).isZero();
    }

    @Test
    void awaitPollsWhileRunningUntilFinished() {
        when(client.sync()).thenReturn(syncState("queued", null), syncState("executing", null),
                syncState("finished", "2026-11-18T10:00:30Z"));

        connector.awaitProviderSync(ref(1L, "c1"), Duration.ofMinutes(10));

        assertThat(sleeps).isEqualTo(2);
        verify(client, times(3)).sync();
    }

    @Test
    void awaitWaitsForARefreshRequestedByThisApplicationThenClearsTheMark() {
        tracker.markRefreshRequested(1L, T0);
        when(client.sync()).thenReturn(syncState("finished", "2026-11-18T09:00:00Z"),
                syncState("finished", "2026-11-18T10:00:20Z"));

        connector.awaitProviderSync(ref(1L, "c1"), Duration.ofMinutes(10));

        assertThat(sleeps).isEqualTo(1);
        assertThat(tracker.refreshRequestedAt(1L)).isNull();
    }

    @Test
    void awaitTimeoutLogsAndContinuesWithoutFailing() {
        when(client.sync()).thenReturn(syncState("executing", null));

        connector.awaitProviderSync(ref(1L, "c1"), Duration.ofMinutes(1));

        assertThat(sleeps).isEqualTo(4);
    }

    @Test
    void awaitTreatsAFailedStatusCallAsNotWaiting() {
        when(client.sync()).thenThrow(new FintableConnectorError(ConnectorErrorClass.TRANSIENT, null, null, "x",
                503, null, null));

        connector.awaitProviderSync(ref(1L, "c1"), Duration.ofMinutes(10));

        assertThat(sleeps).isZero();
    }

    @Test
    void awaitSupportsAnArrayShapedSyncResponse() {
        when(client.sync()).thenReturn(
                json("{\"connections\":[{\"id\":\"c1\",\"sync_status\":{\"state\":\"retrying\"}}]}"),
                json("{\"connections\":[{\"id\":\"c1\",\"sync_status\":{\"state\":\"failed\"}}]}"));

        connector.awaitProviderSync(ref(1L, "c1"), Duration.ofMinutes(10));

        assertThat(sleeps).isEqualTo(1);
    }

    // ------------------------------------------------------------------ disable

    @Test
    void disableDeletesTheConnection() {
        connector.disable(ref(1L, "c1"));

        verify(client).deleteConnection("c1");
    }

    @Test
    void disableTreatsNotFoundAsSuccess() {
        when(client.deleteConnection("c1")).thenThrow(new FintableConnectorError(ConnectorErrorClass.REAUTH, null,
                FintableErrorMapper.KEY_REAUTH, "404", 404, null, null));

        connector.disable(ref(1L, "c1"));
    }

    @Test
    void disableWhileLockedIsATransientError() {
        when(client.deleteConnection("c1")).thenThrow(new FintableConnectorError(ConnectorErrorClass.TRANSIENT,
                null, FintableErrorMapper.KEY_TRANSIENT, "503", 503, null, null));

        assertThatThrownBy(() -> connector.disable(ref(1L, "c1")))
                .isInstanceOfSatisfying(FintableConnectorError.class,
                        e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT));
    }

    @Test
    void disableWithoutExternalIdDoesNotCallFintable() {
        connector.disable(ref(1L, null));

        verify(client, never()).deleteConnection(anyString());
    }
}
