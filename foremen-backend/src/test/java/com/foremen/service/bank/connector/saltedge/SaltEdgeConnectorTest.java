package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.TxnStatus;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * FOR-07-04 task 5.4 — end-to-end test of the Salt Edge connector in both modes:
 * real {@link SaltEdgeConnector} → real {@link SaltEdgeModeApi} ({@link ClientModeApi} / {@link PartnerModeApi})
 * → real {@link SaltEdgeClient} → {@link MockRestServiceServer} serving the recorded JSON fixtures under
 * {@code saltedge/client/} and {@code saltedge/partner/}.
 *
 * <p>Covers identity (customer create / duplicate, lead create-or-return), session bodies incl.
 * {@code return_to} derived from {@code Public_Base_URL}, reconnect, refresh (not-yet / allowed),
 * connection-state mapping, paged accounts / transactions with pending rows, holder info, the provider
 * listing ({@code requiresOwnKeys}, test banks), error-class mapping and request signing. The pending-
 * transactions and holder-info routes are asserted against the pinned {@code routes.json} of each mode.
 *
 * <p><b>Validates: Requirements 25.2, 9.2, 11.2, 12.1</b>
 */
class SaltEdgeConnectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Trailing slash on purpose: {@link PublicBaseUrl} normalizes it away. */
    private static final String PUBLIC_BASE = "https://dev.foremen.eu/";
    private static final long FOREMEN_CONNECTION_ID = 42L;
    private static final String RETURN_TO = "https://dev.foremen.eu/bank/connections/return?connectionId=42";
    private static final String FAKE_BANK = "fakebank_simple_xf";
    private static final String LEAD_EMAIL = "finance@example.com";
    private static final int FETCH_DAYS = 89;

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    // =====================================================================================
    // per-mode fixtures, ids and routes
    // =====================================================================================

    enum Mode {
        CLIENT(SaltEdgeMode.CLIENT, "client",
                "1160000000000000001", "1170000000000000001", "1180000000000000001", "1150000000000000001",
                "2026-11-05T07:00:00Z", "connection-with-holder-info.json",
                "accounts-fakebank_simple_xf.json", "1180000000000000005",
                "transactions-posted-fakebank_simple_xf.json", "transactions-posted-page-2-fakebank_simple_xf.json",
                "transactions-pending-fakebank_simple_xf.json",
                "connect-session.json", "reconnect-session.json", "refresh-session.json"),
        PARTNER(SaltEdgeMode.PARTNER, "partner",
                "222222222222222222", "111111111111111111", "333333333333333333", "555555555555555555",
                "2026-10-29T08:00:00Z", "connection.json",
                "accounts.json", "333333333333333334",
                "transactions-posted.json", "transactions-posted-page-2.json",
                "transactions-pending.json",
                "lead-session-create.json", "lead-session-reconnect.json", "lead-session-refresh.json");

        final SaltEdgeMode mode;
        final String dir;
        final String customer;
        final String conn;
        final String acc;
        final String consentId;
        /** Default clock: before the fixture's {@code next_refresh_possible_at}, far from consent expiry. */
        final Instant now;
        final String connectionFixture;
        final String accountsFixture;
        final String accountsNextId;
        final String postedFixture;
        final String postedPage2Fixture;
        final String pendingFixture;
        final String createFixture;
        final String reconnectFixture;
        final String refreshFixture;

        Mode(SaltEdgeMode mode, String dir, String customer, String conn, String acc, String consentId, String now,
             String connectionFixture, String accountsFixture, String accountsNextId, String postedFixture,
             String postedPage2Fixture, String pendingFixture, String createFixture, String reconnectFixture,
             String refreshFixture) {
            this.mode = mode;
            this.dir = dir;
            this.customer = customer;
            this.conn = conn;
            this.acc = acc;
            this.consentId = consentId;
            this.now = Instant.parse(now);
            this.connectionFixture = connectionFixture;
            this.accountsFixture = accountsFixture;
            this.accountsNextId = accountsNextId;
            this.postedFixture = postedFixture;
            this.postedPage2Fixture = postedPage2Fixture;
            this.pendingFixture = pendingFixture;
            this.createFixture = createFixture;
            this.reconnectFixture = reconnectFixture;
            this.refreshFixture = refreshFixture;
        }

        boolean client() {
            return this == CLIENT;
        }

        String base() {
            return SaltEdgeProperties.defaultBaseUrl(mode);
        }

        String fixture(String name) {
            try (InputStream in = SaltEdgeConnectorTest.class.getResourceAsStream("/saltedge/" + dir + "/" + name)) {
                assertThat(in).as("fixture %s/%s", dir, name).isNotNull();
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        JsonNode json(String name) {
            try {
                return JSON.readTree(fixture(name));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The fixture with its {@code data} object (or {@code meta} for listings) mutated. */
        String mutate(String name, Consumer<ObjectNode> root) {
            ObjectNode node = (ObjectNode) json(name);
            root.accept(node);
            return node.toString();
        }

        /** Absolute URL of a route pinned in {@code routes.json}, placeholders substituted. */
        String route(String routeName, Map<String, String> vars) {
            JsonNode route = json("routes.json").path("routes").path(routeName);
            assertThat(route.isMissingNode()).as("route %s pinned in %s/routes.json", routeName, dir).isFalse();
            String path = route.path("path").asText();
            for (Map.Entry<String, String> v : vars.entrySet()) {
                path = path.replace("{" + v.getKey() + "}", v.getValue());
            }
            assertThat(path).as("all placeholders of %s substituted", routeName).doesNotContain("{");
            return base() + path;
        }

        String connectUrl() {
            return base() + (client() ? "/connections/connect" : "/lead_sessions/create");
        }

        String reconnectUrl() {
            return base() + (client() ? "/connections/" + conn + "/reconnect" : "/lead_sessions/reconnect");
        }

        String refreshUrl() {
            return base() + (client() ? "/connections/" + conn + "/refresh" : "/lead_sessions/refresh");
        }

        String providersUrl(String country, boolean fake) {
            return client()
                    ? base() + "/providers?country_code=" + country + "&include_sandboxes=" + fake
                            + "&exclude_inactive=true&include_ais_fields=true"
                    : base() + "/providers?country_code=" + country + "&include_fake_providers=" + fake;
        }

        String holderConsentScope() {
            return client() ? "holder_info" : "holder_information";
        }

        String fromDatePath() {
            return client() ? "$.data.attempt.fetch_from_date" : "$.data.attempt.from_date";
        }

        String providerCodePath() {
            return client() ? "$.data.provider.code" : "$.data.provider_code";
        }

        String includeFakePath() {
            return client() ? "$.data.provider.include_sandboxes" : "$.data.include_fake_providers";
        }

        Instant nextRefreshPossibleAt() {
            return Instant.parse(json(connectionFixture).path("data").path("next_refresh_possible_at").asText());
        }

        String sessionUrl(String fixtureName) {
            JsonNode data = json(fixtureName).path("data");
            return data.path(client() ? "connect_url" : "redirect_url").asText();
        }

        Instant sessionExpiresAt(String fixtureName) {
            return Instant.parse(json(fixtureName).path("data").path("expires_at").asText());
        }
    }

    /** A connector wired end-to-end over a {@link MockRestServiceServer}. */
    private static final class Harness {
        final Mode mode;
        final Instant now;
        final MockRestServiceServer server;
        final SaltEdgeConnector connector;
        final AtomicInteger sleeps = new AtomicInteger();

        Harness(Mode mode, Instant now, boolean includeFake, SaltEdgeRequestSigner signer, List<Duration> retryDelays) {
            this.mode = mode;
            this.now = now;
            RestClient.Builder builder = RestClient.builder();
            this.server = MockRestServiceServer.bindTo(builder).build();
            SaltEdgeProperties props = new SaltEdgeProperties(true, mode.mode, null, "app", "secret",
                    signer != null ? "/run/secrets/saltedge.pem" : null, null, null,
                    mode == Mode.PARTNER ? LEAD_EMAIL : null, FETCH_DAYS, includeFake, null, null, null);
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            SaltEdgeClient client = new SaltEdgeClient(props, builder, signer, null, clock,
                    d -> sleeps.incrementAndGet(), retryDelays);
            SaltEdgeModeApi api = SaltEdgeModeApi.forMode(client);
            this.connector = new SaltEdgeConnector(api, props, new PublicBaseUrl(PUBLIC_BASE), clock, 14);
        }

        BankConnectionRef ref(String externalCustomerId, String externalConnectionId, String providerCode) {
            return new BankConnectionRef(FOREMEN_CONNECTION_ID, FeedProvider.SALT_EDGE, mode.mode, externalCustomerId,
                    externalConnectionId, null, providerCode);
        }

        /** An authorized connection with the stored customer / lead id. */
        BankConnectionRef connected(String providerCode) {
            return ref(mode.customer, mode.conn, providerCode);
        }

        void get(String url, String body) {
            server.expect(once(), requestTo(url)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }

        void getFixture(String url, String fixtureName) {
            get(url, mode.fixture(fixtureName));
        }

        void expectConnection(String body) {
            get(mode.base() + "/connections/" + mode.conn, body);
        }

        /** The provider lookup the connector does to decide the holder scope of a {@code *_xf} bank. */
        void expectFakeProviderLookup() {
            if (mode.client()) {
                getFixture(mode.providersUrl("XF", true), "providers-xf.json");
                getFixture(mode.providersUrl("XF", true) + "&key_owner=saltedge", "providers-xf-key-owner-saltedge.json");
            } else {
                getFixture(mode.providersUrl("XF", true), "providers-xf.json");
            }
        }
    }

    private static Harness harness(Mode mode) {
        return new Harness(mode, mode.now, true, null, List.of());
    }

    private static Harness harnessAt(Mode mode, Instant now) {
        return new Harness(mode, now, true, null, List.of());
    }

    private static String errorBody(String errorClass, String requestId) {
        return "{\"error\":{\"class\":\"" + errorClass + "\",\"message\":\"Salt Edge says " + errorClass
                + "\",\"request_id\":\"" + requestId + "\"}}";
    }

    // =====================================================================================
    // identity (R8.7)
    // =====================================================================================

    @Test
    void clientIdentityCreatesTheCustomerOnceAndCachesIt() {
        Harness h = harness(Mode.CLIENT);
        h.getFixture(h.mode.base() + "/customers", "customers-list-empty.json");
        h.server.expect(once(), requestTo(h.mode.base() + "/customers")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.identifier").value(SaltEdgeProperties.DEFAULT_CUSTOMER_IDENTIFIER))
                .andRespond(withSuccess(h.mode.fixture("customer-create.json"), MediaType.APPLICATION_JSON));

        assertThat(h.connector.ensureIdentity()).isEqualTo(h.mode.customer);
        assertThat(h.connector.ensureIdentity()).isEqualTo(h.mode.customer); // cached: no further request
        h.server.verify();
    }

    @Test
    void clientIdentityResolvesDuplicatedCustomerAndUsesItForTheSession() {
        Harness h = harness(Mode.CLIENT);
        h.getFixture(h.mode.base() + "/customers", "customers-list-empty.json");
        h.server.expect(once(), requestTo(h.mode.base() + "/customers")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body(h.mode.fixture("error-duplicated-customer.json")));
        h.getFixture(h.mode.base() + "/customers", "customers-list.json");
        h.server.expect(once(), requestTo(h.mode.connectUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value(h.mode.customer))
                .andRespond(withSuccess(h.mode.fixture(h.mode.createFixture), MediaType.APPLICATION_JSON));

        AuthorizationStart start = h.connector.startAuthorization(h.ref(null, null, null), null, null);

        assertThat(start.externalCustomerId()).isEqualTo(h.mode.customer);
        assertThat(h.connector.ensureIdentity()).isEqualTo(h.mode.customer); // cached
        h.server.verify();
    }

    @Test
    void partnerIdentityCreatesOrReturnsTheLeadOnceAndCachesIt() {
        Harness h = harness(Mode.PARTNER);
        h.server.expect(once(), requestTo(h.mode.base() + "/leads")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.email").value(LEAD_EMAIL))
                .andRespond(withSuccess(h.mode.fixture("lead-create.json"), MediaType.APPLICATION_JSON));
        h.server.expect(once(), requestTo(h.mode.connectUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value(h.mode.customer))
                .andRespond(withSuccess(h.mode.fixture(h.mode.createFixture), MediaType.APPLICATION_JSON));

        AuthorizationStart start = h.connector.startAuthorization(h.ref(null, null, null), null, null);

        assertThat(start.externalCustomerId()).isEqualTo(h.mode.customer);
        assertThat(h.connector.ensureIdentity()).isEqualTo(h.mode.customer); // cached: POST /leads only once
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void storedCustomerIdIsReusedWithoutAnIdentityCall(Mode mode) {
        Harness h = harness(mode);
        h.server.expect(once(), requestTo(mode.connectUrl()))
                .andExpect(jsonPath("$.data.customer_id").value("999"))
                .andRespond(withSuccess(mode.fixture(mode.createFixture), MediaType.APPLICATION_JSON));

        assertThat(h.connector.startAuthorization(h.ref("999", null, null), null, null).externalCustomerId())
                .isEqualTo("999");
        h.server.verify();
    }

    // =====================================================================================
    // sessions (R9.1, R9.4)
    // =====================================================================================

    @ParameterizedTest
    @EnumSource(Mode.class)
    void connectSessionBodyCarriesReturnToFromPublicBaseUrlAndHolderScope(Mode mode) {
        Harness h = harness(mode);
        String fromDate = LocalDate.ofInstant(mode.now, ZoneOffset.UTC).minusDays(FETCH_DAYS).toString();
        h.expectFakeProviderLookup();
        h.server.expect(once(), requestTo(mode.connectUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value(mode.customer))
                .andExpect(jsonPath("$.data.consent.scopes", hasItem(mode.holderConsentScope())))
                .andExpect(jsonPath("$.data.consent.from_date").value(fromDate))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes", hasItem("holder_info")))
                .andExpect(jsonPath(mode.fromDatePath()).value(fromDate))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.attempt.custom_fields.foremenConnectionId")
                        .value(String.valueOf(FOREMEN_CONNECTION_ID)))
                .andExpect(jsonPath(mode.providerCodePath()).value(FAKE_BANK))
                .andExpect(jsonPath(mode.includeFakePath()).value(true))
                .andExpect(jsonPath("$.data.return_connection_id").value(true))
                .andExpect(jsonPath("$.data.return_error_class").value(true))
                .andExpect(jsonPath("$.data.categorization").value("business"))
                .andRespond(withSuccess(mode.fixture(mode.createFixture), MediaType.APPLICATION_JSON));

        // The caller's URL is only a fallback: return_to is always derived from Public_Base_URL.
        AuthorizationStart start = h.connector.startAuthorization(h.ref(mode.customer, null, null), FAKE_BANK,
                URI.create("https://elsewhere.example/ignored"));

        assertThat(start.authorizationUrl().toString()).isEqualTo(mode.sessionUrl(mode.createFixture));
        assertThat(start.expiresAt()).isEqualTo(mode.sessionExpiresAt(mode.createFixture));
        assertThat(start.externalCustomerId()).isEqualTo(mode.customer);
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void connectSessionOmitsHolderScopeWhenTheBankDoesNotSupportIt(Mode mode) {
        Harness h = harness(mode);
        h.expectFakeProviderLookup();
        h.server.expect(once(), requestTo(mode.connectUrl()))
                .andExpect(jsonPath("$.data.consent.scopes", not(hasItem(mode.holderConsentScope()))))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes", not(hasItem("holder_info"))))
                .andExpect(jsonPath(mode.providerCodePath()).value("fake_oauth_client_xf"))
                .andRespond(withSuccess(mode.fixture(mode.createFixture), MediaType.APPLICATION_JSON));

        h.connector.startAuthorization(h.ref(mode.customer, null, null), "fake_oauth_client_xf", null);
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reconnectOpensFreshConsentForTheSameConnection(Mode mode) {
        Harness h = harness(mode);
        h.expectFakeProviderLookup();
        var request = h.server.expect(once(), requestTo(mode.reconnectUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.consent.scopes", hasItem(mode.holderConsentScope())))
                .andExpect(jsonPath("$.data.consent.from_date").exists())
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.attempt.custom_fields.foremenConnectionId").value("42"));
        if (!mode.client()) {
            request.andExpect(jsonPath("$.data.customer_id").value(mode.customer))
                    .andExpect(jsonPath("$.data.connection_id").value(mode.conn));
        }
        request.andRespond(withSuccess(mode.fixture(mode.reconnectFixture), MediaType.APPLICATION_JSON));

        AuthorizationStart start = h.connector.startReconnect(h.connected(FAKE_BANK), null);

        assertThat(start.authorizationUrl().toString()).isEqualTo(mode.sessionUrl(mode.reconnectFixture));
        assertThat(start.externalCustomerId()).isEqualTo(mode.customer);
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reconnectWithoutSaltEdgeConnectionFallsBackToConnectSession(Mode mode) {
        Harness h = harness(mode);
        h.server.expect(once(), requestTo(mode.connectUrl()))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andRespond(withSuccess(mode.fixture(mode.createFixture), MediaType.APPLICATION_JSON));

        assertThat(h.connector.startReconnect(h.ref(mode.customer, null, null), null).authorizationUrl())
                .hasToString(mode.sessionUrl(mode.createFixture));
        h.server.verify();
    }

    // =====================================================================================
    // refresh (R9.5, R9.6)
    // =====================================================================================

    @ParameterizedTest
    @EnumSource(Mode.class)
    void refreshIsNotYetPossibleBeforeNextRefreshPossibleAt(Mode mode) {
        Harness h = harness(mode);
        assertThat(mode.now).isBefore(mode.nextRefreshPossibleAt());
        h.expectConnection(mode.fixture(mode.connectionFixture));

        RefreshResult result = h.connector.requestRefresh(h.connected(FAKE_BANK), RefreshMode.INTERACTIVE);

        assertThat(result.started()).isFalse();
        assertThat(result.nextRefreshPossibleAt()).isEqualTo(mode.nextRefreshPossibleAt());
        assertThat(result.widgetUrl()).isNull();
        h.server.verify(); // no refresh / provider call was made
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void interactiveRefreshOpensTheWidgetOnceAllowed(Mode mode) {
        Harness h = harnessAt(mode, mode.nextRefreshPossibleAt().plusSeconds(3600));
        h.expectConnection(mode.fixture(mode.connectionFixture));
        h.expectFakeProviderLookup();
        var request = h.server.expect(once(), requestTo(mode.refreshUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes", hasItem("holder_info")))
                .andExpect(jsonPath("$.data.consent").doesNotExist());
        if (!mode.client()) {
            request.andExpect(jsonPath("$.data.customer_id").value(mode.customer))
                    .andExpect(jsonPath("$.data.connection_id").value(mode.conn));
        }
        request.andRespond(withSuccess(mode.fixture(mode.refreshFixture), MediaType.APPLICATION_JSON));

        RefreshResult result = h.connector.requestRefresh(h.connected(FAKE_BANK), RefreshMode.INTERACTIVE);

        assertThat(result.started()).isTrue();
        assertThat(result.widgetUrl()).hasToString(mode.sessionUrl(mode.refreshFixture));
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void backgroundRefreshOnlyInClientMode(Mode mode) {
        Harness h = harnessAt(mode, mode.nextRefreshPossibleAt().plusSeconds(3600));
        h.expectConnection(mode.fixture(mode.connectionFixture));
        if (mode.client()) {
            h.server.expect(once(), requestTo(mode.base() + "/connections/" + mode.conn + "/background_refresh"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.data.attempt.return_to").doesNotExist())
                    .andRespond(withSuccess(mode.mutate(mode.connectionFixture, root -> ((ObjectNode) root.path("data"))
                            .put("next_refresh_possible_at", "2026-11-05T10:30:00Z")), MediaType.APPLICATION_JSON));
        }

        RefreshResult result = h.connector.requestRefresh(h.connected(null), RefreshMode.BACKGROUND);

        if (mode.client()) {
            assertThat(result.started()).isTrue();
            assertThat(result.nextRefreshPossibleAt()).isEqualTo(Instant.parse("2026-11-05T10:30:00Z"));
        } else {
            assertThat(result.started()).isFalse(); // Partners: no background refresh, daily_refresh applies
        }
        h.server.verify();
    }

    // =====================================================================================
    // connection-state mapping (R9.7)
    // =====================================================================================

    enum StateCase {
        ACTIVE(ConnectionStatusHint.ACTIVE),
        EXPIRING(ConnectionStatusHint.CONSENT_EXPIRING),
        INACTIVE(ConnectionStatusHint.REAUTH_REQUIRED),
        REVOKED(ConnectionStatusHint.REAUTH_REQUIRED),
        PARTNER_CONSENT_REVOKED(ConnectionStatusHint.REAUTH_REQUIRED),
        DISABLED(ConnectionStatusHint.DISABLED);

        final ConnectionStatusHint expected;

        StateCase(ConnectionStatusHint expected) {
            this.expected = expected;
        }
    }

    static Stream<Arguments> stateCases() {
        List<Arguments> out = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (StateCase c : StateCase.values()) {
                if (c == StateCase.PARTNER_CONSENT_REVOKED && mode.client()) {
                    continue; // partner consents exist only in PARTNER mode
                }
                out.add(Arguments.of(mode, c));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("stateCases")
    void connectionStateIsMappedFromConnectionAndConsents(Mode mode, StateCase c) {
        Harness h = harness(mode);
        String connStatus = switch (c) {
            case INACTIVE -> "inactive";
            case DISABLED -> "disabled";
            default -> "active";
        };
        h.expectConnection(mode.mutate(mode.connectionFixture,
                root -> ((ObjectNode) root.path("data")).put("status", connStatus)));
        Instant expiresAt = c == StateCase.EXPIRING
                ? mode.now.plus(Duration.ofDays(5))
                : Instant.parse(mode.json("consent.json").path("data").path("expires_at").asText());
        String consent = c == StateCase.REVOKED && mode.client()
                ? mode.fixture("consent-revoke.json")
                : mode.mutate("consent.json", root -> {
                    ObjectNode data = (ObjectNode) root.path("data");
                    data.put("expires_at", expiresAt.toString());
                    if (c == StateCase.REVOKED) {
                        data.put("status", "revoked").put("revoked_at", mode.now.minusSeconds(60).toString());
                    }
                });
        h.get(mode.base() + "/consents/" + mode.consentId + "?connection_id=" + mode.conn, consent);
        if (!mode.client()) {
            h.get(mode.base() + "/partner_consents?connection_id=" + mode.conn,
                    mode.mutate("partner-consents.json", root -> {
                        if (c == StateCase.PARTNER_CONSENT_REVOKED) {
                            ObjectNode latest = (ObjectNode) root.path("data").get(root.path("data").size() - 1);
                            latest.put("status", "revoked").put("revoked_at", mode.now.minusSeconds(60).toString());
                        }
                    }));
        }

        ConnectionState state = h.connector.fetchConnectionState(h.connected(FAKE_BANK));

        assertThat(state.status()).isEqualTo(c.expected);
        assertThat(state.externalConnectionId()).isEqualTo(mode.conn);
        assertThat(state.externalConsentId()).isEqualTo(mode.consentId);
        assertThat(state.nextRefreshPossibleAt()).isEqualTo(mode.nextRefreshPossibleAt());
        assertThat(state.providerCode()).isEqualTo(FAKE_BANK);
        assertThat(state.bankName()).isNotBlank();
        if (c == StateCase.ACTIVE || c == StateCase.EXPIRING) {
            assertThat(state.consentExpiresAt()).isEqualTo(expiresAt);
        }
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void completeAuthorizationReadsTheConnectionFromReturnParams(Mode mode) {
        Harness h = harness(mode);
        h.expectConnection(mode.fixture(mode.connectionFixture));
        h.getFixture(mode.base() + "/consents/" + mode.consentId + "?connection_id=" + mode.conn, "consent.json");
        if (!mode.client()) {
            h.getFixture(mode.base() + "/partner_consents?connection_id=" + mode.conn, "partner-consents.json");
        }

        ConnectionState state = h.connector.completeAuthorization(h.ref(mode.customer, null, FAKE_BANK),
                Map.of("connection_id", mode.conn));

        assertThat(state.status()).isEqualTo(ConnectionStatusHint.ACTIVE);
        assertThat(state.externalConnectionId()).isEqualTo(mode.conn);
        h.server.verify();
    }

    // =====================================================================================
    // data: accounts, transactions incl. pending, holder info (R11.2, R3.7)
    // =====================================================================================

    @ParameterizedTest
    @EnumSource(Mode.class)
    void accountsArePagedByNextId(Mode mode) {
        Harness h = harness(mode);
        h.get(mode.base() + "/accounts?connection_id=" + mode.conn, mode.mutate(mode.accountsFixture,
                root -> ((ObjectNode) root.path("meta")).put("next_id", mode.accountsNextId)));
        h.getFixture(mode.base() + "/accounts?connection_id=" + mode.conn + "&from_id=" + mode.accountsNextId,
                "accounts-page-2.json");

        List<NormalizedAccount> accounts = h.connector.fetchAccounts(h.connected(FAKE_BANK));

        List<String> expectedIds = mode.client()
                // 1180000000000000004 has no currency ⇒ skipped as malformed
                ? List.of("1180000000000000001", "1180000000000000002", "1180000000000000003", "1180000000000000005")
                : List.of("333333333333333333", "333333333333333334");
        assertThat(accounts).extracting(NormalizedAccount::externalId).containsExactlyElementsOf(expectedIds);
        assertThat(accounts.get(0).iban()).isEqualTo("PL61109010140000071219812874");
        assertThat(accounts.get(0).currency()).isEqualTo("PLN");
        assertThat(accounts.get(accounts.size() - 1).balance()).isEqualByComparingTo("320.00");
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void transactionsArePagedWithPendingSnapshotOnTheFirstPageOnly(Mode mode) {
        Harness h = harness(mode);
        Map<String, String> vars = Map.of("connection_id", mode.conn, "account_id", mode.acc);
        String postedBase = mode.base() + "/transactions?connection_id=" + mode.conn + "&account_id=" + mode.acc;
        String pendingUrl = mode.route("transactionsPending", vars); // pinned per mode in routes.json
        String nextId = mode.json(mode.postedFixture).path("meta").path("next_id").asText();
        String resumeFrom = mode.client() ? "1190000000000000105" : "444444444444444444";

        // 1) fresh fetch: posted page 1 + pending snapshot
        h.getFixture(postedBase, mode.postedFixture);
        h.getFixture(pendingUrl, mode.pendingFixture);
        // 2) continuation: posted page 2 only
        h.getFixture(mode.route("transactionsPosted", Map.of("connection_id", mode.conn, "account_id", mode.acc,
                "from_id", nextId)), mode.postedPage2Fixture);
        // 3) resume from a stored cursor: posted from_id + pending snapshot again
        h.getFixture(postedBase + "&from_id=" + resumeFrom, mode.postedPage2Fixture);
        h.getFixture(pendingUrl, mode.pendingFixture);

        BankConnectionRef ref = h.connected(FAKE_BANK);
        NormalizedAccountRef account = new NormalizedAccountRef(mode.acc);

        TransactionPage first = h.connector.fetchTransactions(ref, account, null);
        List<String> firstPosted = mode.client()
                ? List.of("1190000000000000101", "1190000000000000102", "1190000000000000103",
                        "1190000000000000104", "1190000000000000105")
                : List.of("444444444444444444");
        String pendingId = mode.client() ? "1190000000000000201" : "444444444444444499";
        assertThat(first.posted()).extracting(NormalizedTransaction::externalId).containsExactlyElementsOf(firstPosted);
        assertThat(first.posted()).allSatisfy(t -> assertThat(t.status()).isEqualTo(TxnStatus.POSTED));
        assertThat(first.pending()).extracting(NormalizedTransaction::externalId).containsExactly(pendingId);
        NormalizedTransaction pending = first.pending().get(0);
        assertThat(pending.status()).isEqualTo(TxnStatus.PENDING);
        assertThat(pending.bookingDate()).isNull();
        assertThat(pending.amount()).isEqualByComparingTo(new BigDecimal("-89.00"));
        // CLIENT fixtures carry 3 malformed posted rows + 1 pending row with an unknown status.
        assertThat(first.skippedMalformed()).isEqualTo(mode.client() ? 4 : 0);
        assertThat(first.nextCursor()).isEqualTo(SaltEdgeConnector.CONTINUATION_PREFIX + nextId);
        assertThat(first.complete()).isFalse();

        TransactionPage second = h.connector.fetchTransactions(ref, account, first.nextCursor());
        assertThat(second.posted()).extracting(NormalizedTransaction::externalId).containsExactly(
                mode.client() ? "1190000000000000108" : "444444444444444445");
        assertThat(second.pending()).isEmpty();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.complete()).isTrue();

        TransactionPage resumed = h.connector.fetchTransactions(ref, account, resumeFrom);
        assertThat(resumed.pending()).extracting(NormalizedTransaction::externalId).containsExactly(pendingId);
        assertThat(resumed.complete()).isTrue();
        h.server.verify();
    }

    @Test
    void pendingRoutesArePinnedPerMode() {
        Map<String, String> vars = Map.of("connection_id", "C", "account_id", "A");
        // API v6: list route with the pending flag; Partners v1: dedicated /transactions/pending route.
        assertThat(Mode.CLIENT.route("transactionsPending", vars))
                .isEqualTo(SaltEdgeProperties.CLIENT_DEFAULT_BASE_URL + "/transactions?connection_id=C&account_id=A&pending=true");
        assertThat(Mode.PARTNER.route("transactionsPending", vars))
                .isEqualTo(SaltEdgeProperties.PARTNER_DEFAULT_BASE_URL + "/transactions/pending?connection_id=C&account_id=A");
        // Holder info: v6 = connection attribute (no route); Partners v1 = GET /holder_info?connection_id=.
        assertThat(Mode.CLIENT.json("routes.json").path("routes").has("holderInfo")).isFalse();
        assertThat(Mode.CLIENT.json("routes.json").path("routes").path("connectionShow").path("notes").asText())
                .contains("holder_info");
        assertThat(Mode.PARTNER.route("holderInfo", Map.of("connection_id", "C")))
                .isEqualTo(SaltEdgeProperties.PARTNER_DEFAULT_BASE_URL + "/holder_info?connection_id=C");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void holderInfoIsReadPerMode(Mode mode) {
        Harness h = harness(mode);
        h.expectConnection(mode.fixture(mode.connectionFixture));
        if (!mode.client()) {
            h.getFixture(mode.route("holderInfo", Map.of("connection_id", mode.conn)), "holder-info.json");
        }

        Optional<HolderInfo> holder = h.connector.fetchHolderInfo(h.connected(FAKE_BANK));

        assertThat(holder).isPresent();
        assertThat(holder.get().names()).contains("Foremen Sp. z o.o.").doesNotContain("", "  ");
        if (mode.client()) {
            assertThat(holder.get().taxIds()).containsExactly("5250000000");
        }
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void holderInfoIsEmptyWhenTheBankReportsNone(Mode mode) {
        Harness h = harness(mode);
        h.expectConnection(mode.mutate(mode.connectionFixture, root -> ((ObjectNode) root.path("data"))
                .remove("holder_info")));
        if (!mode.client()) {
            h.getFixture(mode.route("holderInfo", Map.of("connection_id", mode.conn)), "holder-info-empty.json");
        }

        assertThat(h.connector.fetchHolderInfo(h.connected(FAKE_BANK))).isEmpty();
        // Not yet authorized at Salt Edge: nothing to read, no request.
        assertThat(h.connector.fetchHolderInfo(h.ref(mode.customer, null, FAKE_BANK))).isEmpty();
        h.server.verify();
    }

    // =====================================================================================
    // providers (R9.2, R12.6)
    // =====================================================================================

    private static ProviderBank bank(List<ProviderBank> banks, String code) {
        return banks.stream().filter(b -> b.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("bank " + code + " not listed: " + banks));
    }

    @Test
    void clientListingFlagsBanksWithoutSaltEdgeKeysAndLabelsTestBanks() {
        Harness h = harness(Mode.CLIENT);
        Mode m = h.mode;
        h.getFixture(m.providersUrl("PL", true), "providers-pl.json");
        h.getFixture(m.providersUrl("PL", true) + "&key_owner=saltedge", "providers-pl-key-owner-saltedge.json");
        h.expectFakeProviderLookup();

        List<ProviderBank> banks = h.connector.listBanks("pl");

        assertThat(banks).extracting(ProviderBank::code).containsExactly(
                "pko_bp_oauth_client_pl", "erste_oauth_client_pl", "mbank_pl", // "no code" row skipped
                "fakebank_simple_xf", "fake_oauth_client_xf", "pko_sandbox_pl");
        ProviderBank pko = bank(banks, "pko_bp_oauth_client_pl");
        assertThat(pko.requiresOwnKeys()).isTrue(); // absent from key_owner=saltedge
        assertThat(pko.testBank()).isFalse();
        assertThat(pko.fetchScopes()).contains("holder_info");
        assertThat(pko.accountTypes()).containsExactlyInAnyOrder("personal", "business");
        assertThat(pko.maxConsentDays()).isEqualTo(180);
        assertThat(pko.regulated()).isTrue();
        assertThat(pko.mode()).isEqualTo("oauth");
        assertThat(bank(banks, "erste_oauth_client_pl").requiresOwnKeys()).isFalse();
        assertThat(bank(banks, "erste_oauth_client_pl").maxConsentDays()).isEqualTo(90);
        assertThat(bank(banks, "mbank_pl").requiresOwnKeys()).isFalse();
        assertThat(bank(banks, "fakebank_simple_xf").testBank()).isTrue();
        assertThat(bank(banks, "fakebank_simple_xf").requiresOwnKeys()).isFalse();
        assertThat(bank(banks, "fake_oauth_client_xf").requiresOwnKeys()).isTrue();
        assertThat(bank(banks, "fake_oauth_client_xf").testBank()).isTrue();
        assertThat(bank(banks, "pko_sandbox_pl").testBank()).isTrue();
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void liveListingExcludesFakeAndSandboxProviders(Mode mode) {
        Harness h = new Harness(mode, mode.now, false, null, List.of());
        h.getFixture(mode.providersUrl("PL", false), "providers-pl.json");
        if (mode.client()) {
            h.getFixture(mode.providersUrl("PL", false) + "&key_owner=saltedge", "providers-pl-key-owner-saltedge.json");
        }

        List<ProviderBank> banks = h.connector.listBanks(null); // configured countries (PL)

        assertThat(banks).isNotEmpty().noneMatch(ProviderBank::testBank);
        h.server.verify();
    }

    @Test
    void partnerListingNeverRequiresOwnKeysAndLabelsTestBanks() {
        Harness h = harness(Mode.PARTNER);
        Mode m = h.mode;
        h.getFixture(m.providersUrl("PL", true), "providers-pl.json");
        h.expectFakeProviderLookup();

        List<ProviderBank> banks = h.connector.listBanks("PL");

        assertThat(banks).extracting(ProviderBank::code)
                .containsExactly("pkobp_business_oauth_client_pl", "fakebank_simple_xf", "fake_oauth_client_xf");
        assertThat(banks).noneMatch(ProviderBank::requiresOwnKeys);
        assertThat(bank(banks, "pkobp_business_oauth_client_pl").testBank()).isFalse();
        assertThat(bank(banks, "pkobp_business_oauth_client_pl").maxFetchInterval()).isEqualTo(730);
        assertThat(bank(banks, "fakebank_simple_xf").testBank()).isTrue();
        assertThat(bank(banks, "fake_oauth_client_xf").testBank()).isTrue();
        h.server.verify();
    }

    // =====================================================================================
    // errors (R12.1)
    // =====================================================================================

    static Stream<Arguments> errorCases() {
        List<Object[]> cases = List.of(
                new Object[] {401, "WrongClientCredentials", ConnectorErrorClass.CONFIG, SaltEdgeErrorMapper.KEY_CONFIG},
                new Object[] {406, "ClientDisabled", ConnectorErrorClass.CONFIG, SaltEdgeErrorMapper.KEY_CONFIG},
                new Object[] {406, "AispConsentExpired", ConnectorErrorClass.REAUTH, SaltEdgeErrorMapper.KEY_REAUTH},
                new Object[] {406, "PartnerConsentRevoked", ConnectorErrorClass.REAUTH, SaltEdgeErrorMapper.KEY_REAUTH},
                new Object[] {404, "ConnectionNotFound", ConnectorErrorClass.REAUTH, SaltEdgeErrorMapper.KEY_REAUTH},
                new Object[] {406, "ConnectionCannotBeRefreshed", ConnectorErrorClass.LIMIT, SaltEdgeErrorMapper.KEY_LIMIT},
                new Object[] {429, "RateLimitExceeded", ConnectorErrorClass.LIMIT, SaltEdgeErrorMapper.KEY_LIMIT},
                new Object[] {406, "FakeConnectionsLimitReached", ConnectorErrorClass.LIMIT,
                        SaltEdgeErrorMapper.KEY_CONNECTION_LIMIT},
                new Object[] {406, "ProviderUnavailable", ConnectorErrorClass.PROVIDER, SaltEdgeErrorMapper.KEY_UNAVAILABLE},
                new Object[] {406, "ProviderKeyNotFound", ConnectorErrorClass.PROVIDER, SaltEdgeErrorMapper.KEY_KEYS_REQUIRED},
                new Object[] {500, "InternalServerError", ConnectorErrorClass.TRANSIENT, SaltEdgeErrorMapper.KEY_TRANSIENT});
        List<Arguments> out = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (Object[] c : cases) {
                out.add(Arguments.of(mode, c[0], c[1], c[2], c[3]));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} HTTP {1} {2} → {3}")
    @MethodSource("errorCases")
    void saltEdgeErrorClassesMapToConnectorErrorClasses(Mode mode, int status, String errorClass,
                                                        ConnectorErrorClass expected, String messageKey) {
        Harness h = harness(mode);
        h.server.expect(once(), requestTo(mode.base() + "/accounts?connection_id=" + mode.conn))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body(errorBody(errorClass, "req-" + status)));

        ConnectorError error = connectorError(() -> h.connector.fetchAccounts(h.connected(FAKE_BANK)));

        assertThat(error).isNotNull();
        assertThat(error.getErrorClass()).isEqualTo(expected);
        assertThat(error.getProviderErrorCode()).isEqualTo(errorClass);
        assertThat(error.getMessageKey()).isEqualTo(messageKey);
        assertThat(error.getRequestId()).isEqualTo("req-" + status);
        assertThat(error.getMessage()).doesNotContain("secret");
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void transientErrorsAreRetriedThenSucceed(Mode mode) {
        Harness h = new Harness(mode, mode.now, true, null, List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        String url = mode.base() + "/accounts?connection_id=" + mode.conn;
        h.server.expect(times(2), requestTo(url))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_HTML).body("<html/>"));
        h.getFixture(url, mode.accountsFixture);

        assertThat(h.connector.fetchAccounts(h.connected(FAKE_BANK))).isNotEmpty();
        assertThat(h.sleeps).hasValue(2);
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void callbackErrorClassAndModeMismatchAreTypedErrorsWithoutHttp(Mode mode) {
        Harness h = harness(mode);

        ConnectorError callback = connectorError(() -> h.connector.completeAuthorization(
                h.ref(mode.customer, null, FAKE_BANK),
                Map.of("error_class", "ProviderUnavailable", "error_message", "bank down")));
        assertThat(callback.getErrorClass()).isEqualTo(ConnectorErrorClass.PROVIDER);
        assertThat(callback.getProviderErrorCode()).isEqualTo("ProviderUnavailable");

        SaltEdgeMode other = mode.client() ? SaltEdgeMode.PARTNER : SaltEdgeMode.CLIENT;
        BankConnectionRef otherMode = new BankConnectionRef(FOREMEN_CONNECTION_ID, FeedProvider.SALT_EDGE, other,
                mode.customer, mode.conn, null, FAKE_BANK);
        ConnectorError mismatch = connectorError(() -> h.connector.fetchAccounts(otherMode));
        assertThat(mismatch.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);

        ConnectorError notAuthorized = connectorError(() -> h.connector.fetchAccounts(h.ref(mode.customer, null, FAKE_BANK)));
        assertThat(notAuthorized.getErrorClass()).isEqualTo(ConnectorErrorClass.REAUTH);
        h.server.verify(); // no request went out
    }

    // =====================================================================================
    // signing (R8.5)
    // =====================================================================================

    private static RequestMatcher signedWith(PublicKey key, long expiresAt) {
        return request -> {
            MockClientHttpRequest r = (MockClientHttpRequest) request;
            String signature = r.getHeaders().getFirst(SaltEdgeClient.HEADER_SIGNATURE);
            assertThat(signature).as("Signature header").isNotBlank();
            String canonical = SaltEdgeRequestSigner.canonicalString(expiresAt, r.getMethod().name(),
                    r.getURI().toString(), r.getBodyAsString());
            try {
                Signature verifier = Signature.getInstance(SaltEdgeRequestSigner.ALGORITHM);
                verifier.initVerify(key);
                verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
                assertThat(verifier.verify(Base64.getDecoder().decode(signature)))
                        .as("signature over %s", canonical).isTrue();
            } catch (GeneralSecurityException e) {
                throw new AssertionError(e);
            }
        };
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void requestsAreSignedWhenAKeyIsConfigured(Mode mode) {
        Harness h = new Harness(mode, mode.now, true, new SaltEdgeRequestSigner(keyPair.getPrivate()), List.of());
        long expiresAt = mode.now.getEpochSecond() + SaltEdgeClient.SIGNATURE_TTL_SECONDS;
        h.server.expect(once(), requestTo(mode.connectUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(header(SaltEdgeClient.HEADER_APP_ID, "app"))
                .andExpect(header(SaltEdgeClient.HEADER_SECRET, "secret"))
                .andExpect(header(SaltEdgeClient.HEADER_EXPIRES_AT, Long.toString(expiresAt)))
                .andExpect(signedWith(keyPair.getPublic(), expiresAt))
                .andRespond(withSuccess(mode.fixture(mode.createFixture), MediaType.APPLICATION_JSON));
        h.server.expect(once(), requestTo(mode.base() + "/accounts?connection_id=" + mode.conn))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(SaltEdgeClient.HEADER_EXPIRES_AT, Long.toString(expiresAt)))
                .andExpect(signedWith(keyPair.getPublic(), expiresAt))
                .andRespond(withSuccess(mode.fixture(mode.accountsFixture), MediaType.APPLICATION_JSON));

        h.connector.startAuthorization(h.ref(mode.customer, null, null), null, null);
        h.connector.fetchAccounts(h.connected(null));
        h.server.verify();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void requestsAreUnsignedWithoutAKey(Mode mode) {
        Harness h = harness(mode);
        h.server.expect(once(), requestTo(mode.base() + "/accounts?connection_id=" + mode.conn))
                .andExpect(header(SaltEdgeClient.HEADER_APP_ID, "app"))
                .andExpect(header(SaltEdgeClient.HEADER_SECRET, "secret"))
                .andExpect(headerDoesNotExist(SaltEdgeClient.HEADER_SIGNATURE))
                .andExpect(headerDoesNotExist(SaltEdgeClient.HEADER_EXPIRES_AT))
                .andRespond(withSuccess(mode.fixture(mode.accountsFixture), MediaType.APPLICATION_JSON));

        h.connector.fetchAccounts(h.connected(null));
        h.server.verify();
    }

    private static ConnectorError connectorError(Runnable call) {
        try {
            call.run();
        } catch (ConnectorError e) {
            return e;
        }
        throw new AssertionError("expected a ConnectorError");
    }
}
