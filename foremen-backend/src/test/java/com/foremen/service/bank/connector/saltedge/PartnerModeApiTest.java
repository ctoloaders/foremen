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
 * FOR-07-04 task 5.5 — {@link PartnerModeApi} (Partners API v1) against {@link MockRestServiceServer}:
 * routes, methods and field names pinned by {@code saltedge/partner/routes.json}.
 *
 * <p><b>Validates: Requirements 8.7, 9.1, 9.4, 9.5, 9.8, 9.9</b>
 */
class PartnerModeApiTest {

    private static final String BASE = "https://www.saltedge.com/api/partners/v1";
    private static final String CUSTOMER = "222222222222222222";
    private static final String CONN = "111111111111111111";
    private static final String ACC = "333333333333333333";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final String RETURN_TO = "https://dev.foremen.eu/bank/connections/return?connectionId=42";

    private MockRestServiceServer server;
    private PartnerModeApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        api = new PartnerModeApi(client(builder, "finance@example.com"));
    }

    private static SaltEdgeClient client(RestClient.Builder builder, String leadEmail) {
        SaltEdgeProperties props = new SaltEdgeProperties(true, SaltEdgeMode.PARTNER, null, "app", "secret", null,
                null, null, leadEmail, 0, null, null, null, null);
        return new SaltEdgeClient(props, builder, null, null,
                Clock.fixed(Instant.parse("2026-10-29T08:00:00Z"), ZoneOffset.UTC), d -> { }, List.of());
    }

    static String fixture(String name) {
        try (InputStream in = PartnerModeApiTest.class.getResourceAsStream("/saltedge/partner/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SaltEdgeModeApi.SessionRequest request(String providerCode, boolean holderInfo) {
        return new SaltEdgeModeApi.SessionRequest(CUSTOMER, providerCode, holderInfo, FROM, RETURN_TO,
                Map.of("foremenConnectionId", "42"), false);
    }

    @Test
    void routesFixtureMatchesTheImplementedRoutes() throws IOException {
        JsonNode root = JSON.readTree(fixture("routes.json"));
        JsonNode routes = root.path("routes");
        assertThat(root.path("baseUrl").asText()).isEqualTo(SaltEdgeProperties.PARTNER_DEFAULT_BASE_URL);
        assertThat(routes.path("leadCreate").path("path").asText()).isEqualTo("/leads");
        assertThat(routes.path("sessionCreate").path("path").asText()).isEqualTo("/lead_sessions/create");
        assertThat(routes.path("sessionReconnect").path("path").asText()).isEqualTo("/lead_sessions/reconnect");
        assertThat(routes.path("sessionRefresh").path("path").asText()).isEqualTo("/lead_sessions/refresh");
        assertThat(routes.path("partnerConsentRevoke").path("method").asText()).isEqualTo("PUT");
        assertThat(routes.path("transactionsPending").path("path").asText()).startsWith("/transactions/pending");
        assertThat(api.mode()).isEqualTo(SaltEdgeMode.PARTNER);
        assertThat(api.supportsBackgroundRefresh()).isFalse();
        assertThatThrownBy(() -> api.backgroundRefresh(CONN, request(null, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- identity (R8.7)

    @Test
    void ensureIdentityCreatesOrReturnsLead() {
        server.expect(once(), requestTo(BASE + "/leads")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.email").value("finance@example.com"))
                .andRespond(withSuccess(fixture("lead-create.json"), MediaType.APPLICATION_JSON));

        assertThat(api.ensureIdentity()).isEqualTo(CUSTOMER);
        server.verify();
    }

    @Test
    void ensureIdentityWithoutLeadEmailIsConfigError() {
        PartnerModeApi noEmail = new PartnerModeApi(client(RestClient.builder(), null));
        assertThatThrownBy(noEmail::ensureIdentity).isInstanceOf(ConnectorError.class)
                .extracting(e -> ((ConnectorError) e).getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);
    }

    // ---------------------------------------------------------------- lead sessions (R9.1, R9.4, R9.5)

    @Test
    void createLeadSessionUsesPartnersFieldNames() {
        server.expect(once(), requestTo(BASE + "/lead_sessions/create")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value(CUSTOMER))
                .andExpect(jsonPath("$.data.consent.scopes",
                        contains("account_details", "holder_information", "transactions_details")))
                .andExpect(jsonPath("$.data.consent.from_date").value("2026-08-01"))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes", contains("accounts", "holder_info", "transactions")))
                .andExpect(jsonPath("$.data.attempt.from_date").value("2026-08-01"))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andExpect(jsonPath("$.data.attempt.custom_fields.foremenConnectionId").value("42"))
                .andExpect(jsonPath("$.data.provider_code").value("pkobp_business_oauth_client_pl"))
                .andExpect(jsonPath("$.data.skip_provider_select").value(true))
                .andExpect(jsonPath("$.data.daily_refresh").value(true))
                .andExpect(jsonPath("$.data.include_fake_providers").value(false))
                .andExpect(jsonPath("$.data.return_connection_id").value(true))
                .andExpect(jsonPath("$.data.return_error_class").value(true))
                .andExpect(jsonPath("$.data.categorization").value("business"))
                .andExpect(jsonPath("$.data", not(hasKey("provider"))))
                .andExpect(jsonPath("$.data", not(hasKey("widget"))))
                .andRespond(withSuccess(fixture("lead-session-create.json"), MediaType.APPLICATION_JSON));

        SaltEdgeModeApi.SessionUrl url = api.createSession(request("pkobp_business_oauth_client_pl", true));

        assertThat(url.url()).isEqualTo("https://www.saltedge.com/dashboard/connect?invitation_token=CREATE_TOKEN");
        assertThat(url.expiresAt()).isEqualTo(Instant.parse("2026-10-29T09:32:02Z"));
        server.verify();
    }

    @Test
    void createLeadSessionWithoutProviderOrHolderScope() {
        server.expect(once(), requestTo(BASE + "/lead_sessions/create"))
                .andExpect(jsonPath("$.data.consent.scopes", contains("account_details", "transactions_details")))
                .andExpect(jsonPath("$.data.attempt.fetch_scopes", contains("accounts", "transactions")))
                .andExpect(jsonPath("$.data.skip_provider_select").value(false))
                .andExpect(jsonPath("$.data", not(hasKey("provider_code"))))
                .andRespond(withSuccess(fixture("lead-session-create.json"), MediaType.APPLICATION_JSON));

        api.createSession(request(null, false));
        server.verify();
    }

    @Test
    void reconnectLeadSessionCarriesConnectionAndFreshConsent() {
        server.expect(once(), requestTo(BASE + "/lead_sessions/reconnect")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value(CUSTOMER))
                .andExpect(jsonPath("$.data.connection_id").value(CONN))
                .andExpect(jsonPath("$.data.consent.scopes", contains("account_details", "transactions_details")))
                .andExpect(jsonPath("$.data.consent.from_date").value("2026-08-01"))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andRespond(withSuccess(fixture("lead-session-reconnect.json"), MediaType.APPLICATION_JSON));

        assertThat(api.reconnectSession(CONN, request(null, false)).url()).endsWith("RECONNECT_TOKEN");
        server.verify();
    }

    @Test
    void refreshLeadSessionHasNoConsent() {
        server.expect(once(), requestTo(BASE + "/lead_sessions/refresh")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.customer_id").value(CUSTOMER))
                .andExpect(jsonPath("$.data.connection_id").value(CONN))
                .andExpect(jsonPath("$.data", not(hasKey("consent"))))
                .andExpect(jsonPath("$.data.attempt.return_to").value(RETURN_TO))
                .andRespond(withSuccess(fixture("lead-session-refresh.json"), MediaType.APPLICATION_JSON));

        assertThat(api.refreshSession(CONN, request(null, false)).url()).endsWith("REFRESH_TOKEN");
        server.verify();
    }

    // ---------------------------------------------------------------- connection + consents + holder info

    @Test
    void readsConnectionConsentPartnerConsentAndHolderInfo() {
        server.expect(once(), requestTo(BASE + "/connections/" + CONN)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("connection.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/consents/555555555555555555?connection_id=" + CONN))
                .andRespond(withSuccess(fixture("consent.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/partner_consents?connection_id=" + CONN))
                .andRespond(withSuccess(fixture("partner-consents.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/holder_info?connection_id=" + CONN))
                .andRespond(withSuccess(fixture("holder-info.json"), MediaType.APPLICATION_JSON));

        JsonNode connection = api.connection(CONN);
        SaltEdgeModeApi.ConsentSnapshot consents = api.consents(connection);

        assertThat(connection.path("status").asText()).isEqualTo("active");
        assertThat(consents.consent().path("expires_at").asText()).isEqualTo("2027-01-27T05:32:02Z");
        assertThat(consents.partnerConsent().path("id").asText()).isEqualTo("666666666666666666");
        assertThat(consents.partnerConsent().path("status").asText()).isEqualTo("active");
        assertThat(api.holderInfo(connection)).hasValueSatisfying(
                h -> assertThat(h.path("names").get(0).asText()).isEqualTo("Foremen Sp. z o.o."));
        server.verify();
    }

    // ---------------------------------------------------------------- data reads

    @Test
    void readsAccountsPostedAndPendingFromPartnersRoutes() {
        server.expect(once(), requestTo(BASE + "/accounts?connection_id=" + CONN))
                .andRespond(withSuccess(fixture("accounts.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/transactions?connection_id=" + CONN + "&account_id=" + ACC))
                .andRespond(withSuccess(fixture("transactions-posted.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/transactions/pending?connection_id=" + CONN + "&account_id=" + ACC))
                .andRespond(withSuccess(fixture("transactions-pending.json"), MediaType.APPLICATION_JSON));

        assertThat(api.accounts(CONN)).extracting(a -> a.path("id").asText()).containsExactly(ACC);
        SaltEdgeClient.Page page = api.transactions(CONN, ACC, null);
        assertThat(page.data()).hasSize(1);
        assertThat(page.nextId()).isEqualTo("444444444444444445");
        assertThat(api.pendingTransactions(CONN, ACC)).extracting(t -> t.path("status").asText())
                .containsExactly("pending");
        server.verify();
    }

    @Test
    void readsProvidersWithIncludeFakeProvidersAndNoKeyOwnerListing() {
        server.expect(once(), requestTo(BASE + "/providers?country_code=PL&include_fake_providers=true"))
                .andRespond(withSuccess(fixture("providers-pl.json"), MediaType.APPLICATION_JSON));

        assertThat(api.providers("PL", true)).extracting(p -> p.path("code").asText())
                .containsExactly("pkobp_business_oauth_client_pl");
        assertThat(api.providersWithSaltEdgeKeys("PL", true)).isEmpty();
        server.verify();
    }

    // ---------------------------------------------------------------- partner consent revoke (R9.8)

    @Test
    void revokeAndRemoveRevokesOnlyActivePartnerConsents() {
        server.expect(once(), requestTo(BASE + "/partner_consents?connection_id=" + CONN))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("partner-consents.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/partner_consents/666666666666666666/revoke?connection_id=" + CONN))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(fixture("partner-consent-revoke.json"), MediaType.APPLICATION_JSON));

        api.revokeAndRemove(CONN);
        server.verify();
    }

    @Test
    void revokeAndRemoveTreatsAlreadyRevokedAsSuccess() {
        server.expect(once(), requestTo(BASE + "/partner_consents?connection_id=" + CONN))
                .andRespond(withSuccess(fixture("partner-consents.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/partner_consents/666666666666666666/revoke?connection_id=" + CONN))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-partner-consent-already-revoked.json")));

        api.revokeAndRemove(CONN);
        server.verify();
    }
}
