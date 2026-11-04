package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * FOR-07-04 task 5.5 — {@link ClientModeApi} (API v6) against {@link MockRestServiceServer}: route,
 * method and key body fields of every port method, pinned by {@code saltedge/client/routes.json}.
 *
 * <p><b>Validates: Requirements 8.7, 9.1, 9.4, 9.5, 9.8, 9.9</b>
 */
class ClientModeApiTest {

    private static final String BASE = "https://www.saltedge.com/api/v6";
    private static final String CONN = "1170000000000000001";
    private static final String ACC = "1180000000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate FROM = LocalDate.of(2026, 8, 4);
    private static final String RETURN_TO = "https://dev.foremen.eu/bank/connections/return?connectionId=42";

    private MockRestServiceServer server;
    private ClientModeApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SaltEdgeProperties props = new SaltEdgeProperties(true, SaltEdgeMode.CLIENT, null, "app", "secret", null, null,
                null, null, 0, null, null, null, null);
        SaltEdgeClient client = new SaltEdgeClient(props, builder, null, null,
                Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC), d -> { }, List.of());
        api = new ClientModeApi(client);
    }

    static String fixture(String name) {
        try (InputStream in = ClientModeApiTest.class.getResourceAsStream("/saltedge/client/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SaltEdgeModeApi.SessionRequest request(String providerCode, boolean holderInfo) {
        return new SaltEdgeModeApi.SessionRequest("1160000000000000001", providerCode, holderInfo, FROM, RETURN_TO,
                Map.of("foremenConnectionId", "42"), true);
    }

    @Test
    void routesFixtureMatchesTheImplementedRoutes() throws IOException {
        JsonNode routes = JSON.readTree(fixture("routes.json")).path("routes");
        assertThat(routes.path("sessionCreate").path("path").asText()).isEqualTo("/connections/connect");
        assertThat(routes.path("transactionsPending").path("path").asText()).endsWith("&pending=true");
        assertThat(routes.path("consentRevoke").path("method").asText()).isEqualTo("PUT");
        assertThat(routes.path("connectionRemove").path("method").asText()).isEqualTo("DELETE");
        assertThat(api.mode()).isEqualTo(SaltEdgeMode.CLIENT);
        assertThat(api.supportsBackgroundRefresh()).isTrue();
    }

    // ---------------------------------------------------------------- identity (R8.7)

    @Test
    void ensureIdentityReusesExistingCustomerByIdentifier() {
        server.expect(once(), requestTo(BASE + "/customers")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("customers-list.json"), MediaType.APPLICATION_JSON));

        assertThat(api.ensureIdentity()).isEqualTo("1160000000000000001");
        server.verify();
    }

    @Test
    void ensureIdentityCreatesCustomerWhenAbsent() {
        server.expect(once(), requestTo(BASE + "/customers")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("customers-list-empty.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/customers")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.identifier").value("foremen-company"))
                .andRespond(withSuccess(fixture("customer-create.json"), MediaType.APPLICATION_JSON));

        assertThat(api.ensureIdentity()).isEqualTo("1160000000000000001");
        server.verify();
    }

    @Test
    void ensureIdentityResolvesDuplicatedCustomerByLookup() {
        server.expect(once(), requestTo(BASE + "/customers")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("customers-list-empty.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/customers")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-duplicated-customer.json")));
        server.expect(once(), requestTo(BASE + "/customers")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("customers-list.json"), MediaType.APPLICATION_JSON));

        assertThat(api.ensureIdentity()).isEqualTo("1160000000000000001");
        server.verify();
    }

    // ---------------------------------------------------------------- sessions (R9.1, R9.4, R9.5)

    @Test
    void createSessionPostsConnectWithDesignBody() {
        server.expect(once(), requestTo(BASE + "/connections/connect")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value("1160000000000000001"))
                .andExpect(jsonPath("$.data.consent.scopes", contains("accounts", "transactions", "holder_info")))
                .andExpect(jsonPath("$.data.consent.from_date").value("2026-08-04"))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes",
                        contains("accounts", "balance", "transactions", "holder_info")))
                .andExpect(jsonPath("$.data.attempt.fetch_from_date").value("2026-08-04"))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.attempt.custom_fields.foremenConnectionId").value("42"))
                .andExpect(jsonPath("$.data.widget.skip_provider_selection").value(true))
                .andExpect(jsonPath("$.data.provider.code").value("pkobp_business_oauth_client_pl"))
                .andExpect(jsonPath("$.data.provider.include_sandboxes").value(true))
                .andExpect(jsonPath("$.data.return_connection_id").value(true))
                .andExpect(jsonPath("$.data.return_error_class").value(true))
                .andExpect(jsonPath("$.data.automatic_refresh").value(true))
                .andExpect(jsonPath("$.data.categorization").value("business"))
                .andRespond(withSuccess(fixture("connect-session.json"), MediaType.APPLICATION_JSON));

        SaltEdgeModeApi.SessionUrl url = api.createSession(request("pkobp_business_oauth_client_pl", true));

        assertThat(url.url()).isEqualTo("https://www.saltedge.com/connect?token=CONNECT_TOKEN");
        assertThat(url.expiresAt()).isEqualTo(Instant.parse("2026-11-01T11:00:00Z"));
        server.verify();
    }

    @Test
    void createSessionWithoutProviderShowsSelectionAndOmitsHolderScope() {
        server.expect(once(), requestTo(BASE + "/connections/connect"))
                .andExpect(jsonPath("$.data.consent.scopes", contains("accounts", "transactions")))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes", contains("accounts", "balance", "transactions")))
                .andExpect(jsonPath("$.data.widget.skip_provider_selection").value(false))
                .andExpect(jsonPath("$.data.provider", not(hasKey("code"))))
                .andRespond(withSuccess(fixture("connect-session.json"), MediaType.APPLICATION_JSON));

        api.createSession(request(null, false));
        server.verify();
    }

    @Test
    void reconnectSendsFreshConsentAndSameReturnTo() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN + "/reconnect"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.consent.scopes", contains("accounts", "transactions")))
                .andExpect(jsonPath("$.data.consent.from_date").value("2026-08-04"))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.return_connection_id").value(true))
                .andRespond(withSuccess(fixture("reconnect-session.json"), MediaType.APPLICATION_JSON));

        assertThat(api.reconnectSession(CONN, request(null, false)).url()).endsWith("RECONNECT_TOKEN");
        server.verify();
    }

    @Test
    void refreshPostsAttemptWithoutConsent() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN + "/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data", not(hasKey("consent"))))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.automatic_refresh").value(true))
                .andRespond(withSuccess(fixture("refresh-session.json"), MediaType.APPLICATION_JSON));

        assertThat(api.refreshSession(CONN, request(null, false)).url()).endsWith("REFRESH_TOKEN");
        server.verify();
    }

    @Test
    void backgroundRefreshPostsToBackgroundRoute() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN + "/background_refresh"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attempt", not(hasKey("return_to"))))
                .andRespond(withSuccess(fixture("connection-with-holder-info.json"), MediaType.APPLICATION_JSON));

        assertThat(api.backgroundRefresh(CONN, request(null, false)).path("status").asText()).isEqualTo("active");
        server.verify();
    }

    // ---------------------------------------------------------------- connection + consent + holder (R9.7)

    @Test
    void readsConnectionConsentAndHolderInfo() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("connection-with-holder-info.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/consents/1150000000000000001?connection_id=" + CONN))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("consent.json"), MediaType.APPLICATION_JSON));

        JsonNode connection = api.connection(CONN);
        SaltEdgeModeApi.ConsentSnapshot consents = api.consents(connection);

        assertThat(connection.path("next_refresh_possible_at").asText()).isEqualTo("2026-11-05T07:30:00Z");
        assertThat(consents.consent().path("expires_at").asText()).isEqualTo("2027-01-30T10:00:00Z");
        assertThat(consents.partnerConsent()).isNull();
        assertThat(api.holderInfo(connection)).hasValueSatisfying(
                h -> assertThat(h.path("names").get(0).asText()).isEqualTo("Foremen Sp. z o.o."));
        server.verify();
    }

    @Test
    void holderInfoIsEmptyWhenTheConnectionHasNone() {
        assertThat(api.holderInfo(JSON.createObjectNode().put("id", CONN))).isEmpty();
    }

    // ---------------------------------------------------------------- data reads

    @Test
    void readsAccountsPostedAndPendingTransactions() {
        server.expect(once(), requestTo(BASE + "/accounts?connection_id=" + CONN))
                .andRespond(withSuccess(fixture("accounts-fakebank_simple_xf.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/transactions?connection_id=" + CONN + "&account_id=" + ACC
                        + "&from_id=1190000000000000100"))
                .andRespond(withSuccess(fixture("transactions-posted-fakebank_simple_xf.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/transactions?connection_id=" + CONN + "&account_id=" + ACC
                        + "&pending=true"))
                .andRespond(withSuccess(fixture("transactions-pending-fakebank_simple_xf.json"), MediaType.APPLICATION_JSON));

        assertThat(api.accounts(CONN)).isNotEmpty();
        assertThat(api.transactions(CONN, ACC, "1190000000000000100").data()).isNotEmpty();
        assertThat(api.pendingTransactions(CONN, ACC)).extracting(t -> t.path("status").asText()).contains("pending");
        server.verify();
    }

    @Test
    void readsProvidersWithAisFieldsAndKeyOwnerListing() {
        server.expect(once(), requestTo(BASE + "/providers?country_code=PL&include_sandboxes=false"
                        + "&exclude_inactive=true&include_ais_fields=true"))
                .andRespond(withSuccess(fixture("providers-pl.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/providers?country_code=PL&include_sandboxes=false"
                        + "&exclude_inactive=true&include_ais_fields=true&key_owner=saltedge"))
                .andRespond(withSuccess(fixture("providers-pl-key-owner-saltedge.json"), MediaType.APPLICATION_JSON));

        assertThat(api.providers("PL", false)).isNotEmpty();
        assertThat(api.providersWithSaltEdgeKeys("PL", false)).isPresent();
        server.verify();
    }

    // ---------------------------------------------------------------- revoke / delete (R9.8)

    @Test
    void revokeAndRemoveRevokesAisConsentThenDeletesConnection() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("connection-with-holder-info.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/consents/1150000000000000001/revoke?connection_id=" + CONN))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(fixture("consent-revoke.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/connections/" + CONN)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(fixture("connection-remove.json"), MediaType.APPLICATION_JSON));

        api.revokeAndRemove(CONN);
        server.verify();
    }

    @Test
    void revokeAndRemoveTreatsConnectionNotFoundAsSuccess() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-connection-not-found.json")));

        api.revokeAndRemove(CONN);
        server.verify();
    }

    @Test
    void revokeAndRemovePropagatesOtherErrors() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"class\":\"ClientDisabled\",\"message\":\"x\"}}"));

        assertThatThrownBy(() -> api.revokeAndRemove(CONN)).isInstanceOf(ConnectorError.class)
                .extracting(e -> ((ConnectorError) e).getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);
    }

    @Test
    void factorySelectsImplementationByMode() {
        assertThat(SaltEdgeModeApi.forMode(SaltEdgeClient.create(new SaltEdgeProperties(true, SaltEdgeMode.CLIENT,
                null, "a", "s", null, null, null, null, 0, null, null, null, null), Clock.systemUTC())))
                .isInstanceOf(ClientModeApi.class);
        assertThat(SaltEdgeModeApi.forMode(SaltEdgeClient.create(new SaltEdgeProperties(true, SaltEdgeMode.PARTNER,
                null, "a", "s", null, null, null, "finance@example.com", 0, null, null, null, null), Clock.systemUTC())))
                .isInstanceOf(PartnerModeApi.class);
    }
}
