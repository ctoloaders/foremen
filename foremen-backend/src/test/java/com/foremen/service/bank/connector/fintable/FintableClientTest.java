package com.foremen.service.bank.connector.fintable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.fintable.dto.FintableTransactionsPage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * FOR-07-04a task 2.2 — {@link FintableClient} against {@link MockRestServiceServer}: headers,
 * {@code workspace_id}, the public institutions call, error mapping through the client, no retry on 429,
 * and the token never in exception messages or logs (Requirements 3.1–3.5, 14.6).
 */
class FintableClientTest {

    private static final String BASE = "https://fintable.test/api/v2";
    private static final String TOKEN = "fnt_pat_TOP_SECRET_0123456789";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(FintableClient.class);
    private Level previousLevel;

    private MockRestServiceServer server;

    @BeforeEach
    void captureLogs() {
        previousLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.TRACE);
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        clientLogger.detachAppender(logs);
        clientLogger.setLevel(previousLevel);
        logs.stop();
    }

    private FintableClient client(String workspaceId) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        FintableProperties props = new FintableProperties(true, BASE, false, TOKEN, null, workspaceId,
                null, null, null, null, null, null, null, null, null);
        return new FintableClient(props, builder, props::apiToken);
    }

    @Test
    void sendsBearerAndAcceptHeaders() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("{\"plan\":\"personal\",\"can_sync\":true}", MediaType.APPLICATION_JSON));

        JsonNode me = client.me();

        assertThat(me.path("can_sync").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    void addsWorkspaceIdQueryParameterWhenConfigured() {
        FintableClient client = client("ws_01ABC");
        server.expect(once(), requestTo(BASE + "/connections?workspace_id=ws_01ABC"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts?connection_id=conn_1&workspace_id=ws_01ABC"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"acc_1\"}]}", MediaType.APPLICATION_JSON));

        client.connections();
        List<JsonNode> accounts = client.accounts("conn_1");

        assertThat(accounts).hasSize(1);
        server.verify();
    }

    @Test
    void omitsWorkspaceIdWhenNotConfigured() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/connections"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        client.connections();

        server.verify();
    }

    @Test
    void institutionsIsPublicWithoutAuthorizationOrWorkspace() {
        FintableClient client = client("ws_01ABC");
        server.expect(once(), requestTo(BASE + "/institutions?q=PKO&country=PL&supported=1"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                        .as("no Authorization on the public call").isNull())
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        client.institutions("PKO", "PL");

        server.verify();
    }

    @Test
    void unwrapsTheDataEnvelopeOfSingleObjectResponses() {
        // Shapes captured from the real API on 2026-11-19 (BUG-2).
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/connections/link"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"data\":{\"url\":\"https://fintable.io/api-link/abc\","
                                + "\"expires_at\":\"2026-11-19T16:01:09Z\"},\"workspace_id\":\"ws_1\"}"));
        server.expect(once(), requestTo(BASE + "/connections/conn_plaid_1"))
                .andRespond(withSuccess("{\"data\":{\"id\":\"conn_plaid_1\",\"healthy\":true,"
                        + "\"needs_reconnect\":false},\"workspace_id\":\"ws_1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/sync"))
                .andRespond(withSuccess("{\"data\":{\"active_syncs\":[],\"connections\":[{\"connection_id\":"
                        + "\"conn_plaid_1\",\"sync_status\":{\"state\":\"finished\"}}]},\"workspace_id\":\"ws_1\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.mintLink("pko_pl", null).path("url").asText())
                .isEqualTo("https://fintable.io/api-link/abc");
        assertThat(client.connection("conn_plaid_1").path("id").asText()).isEqualTo("conn_plaid_1");
        assertThat(client.sync().path("connections").get(0).path("connection_id").asText())
                .isEqualTo("conn_plaid_1");
        server.verify();
    }

    @Test
    void keepsAnUnwrappedObjectAndAListEnvelopeAsTheyAre() {
        JsonNode bare = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("url", "u");
        JsonNode list = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .set("data", new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());

        assertThat(FintableClient.object(bare)).isSameAs(bare);
        assertThat(FintableClient.object(list)).isSameAs(list);
    }

    @Test
    void mintLinkSendsInstitutionAndRedirectUrl() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/connections/link"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().json("{\"institution\":\"pko_pl\",\"redirect_url\":\"https://x.test/r\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"url\":\"https://link.test/abc\",\"expires_at\":\"2026-11-18T12:00:00Z\"}"));
        server.expect(once(), requestTo(BASE + "/connections/link"))
                .andExpect(content().string("{\"institution\":\"pko_pl\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"url\":\"https://link.test/def\"}"));

        assertThat(client.mintLink("pko_pl", "https://x.test/r").path("url").asText())
                .isEqualTo("https://link.test/abc");
        assertThat(client.mintLink("pko_pl", null).path("url").asText()).isEqualTo("https://link.test/def");
        server.verify();
    }

    @Test
    void reconnectDeleteAndSyncRoutes() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/connections/conn_1/link")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"redirect_url\":\"https://x.test/r\"}"))
                .andRespond(withSuccess("{\"url\":\"https://link.test/r\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/connections/conn_1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(once(), requestTo(BASE + "/sync")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"started\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/sync/conn_1")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"already_syncing\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/connections/conn_1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"conn_1\",\"status\":\"connected\"}", MediaType.APPLICATION_JSON));

        assertThat(client.mintReconnectLink("conn_1", "https://x.test/r").path("url").asText())
                .isEqualTo("https://link.test/r");
        assertThat(client.deleteConnection("conn_1").isEmpty()).isTrue();
        assertThat(client.sync().path("status").asText()).isEqualTo("started");
        assertThat(client.syncOne("conn_1").path("status").asText()).isEqualTo("already_syncing");
        assertThat(client.connection("conn_1").path("status").asText()).isEqualTo("connected");
        server.verify();
    }

    @Test
    void transactionsQueryAndNextCursor() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE
                        + "/accounts/acc_1/transactions?order=updated&limit=500&include=raw"
                        + "&updated_since=2026-11-18T10%3A04%3A44Z&cursor=abc%2B%3D"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"tx_1\"},{\"id\":\"tx_2\"}],\"next_cursor\":\"def\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts/acc_1/transactions?order=updated&limit=500&include=raw"))
                .andRespond(withSuccess("{\"data\":[],\"next_cursor\":null}", MediaType.APPLICATION_JSON));

        FintableTransactionsPage page = client.transactions("acc_1", Instant.parse("2026-11-18T10:04:44Z"), "abc+=");
        FintableTransactionsPage last = client.transactions("acc_1", null, null);

        assertThat(page.data()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo("def");
        assertThat(page.hasNext()).isTrue();
        assertThat(last.data()).isEmpty();
        assertThat(last.hasNext()).isFalse();
        server.verify();
    }

    @Test
    void errorsAreMappedAndRateLimitIsNotRetried() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/connections/link"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.RETRY_AFTER, "60")
                        .body("{\"error\":{\"type\":\"rate_limited\",\"message\":\"Slow down\"}}"));

        assertThatThrownBy(() -> client.mintLink("pko_pl", null))
                .isInstanceOfSatisfying(FintableConnectorError.class, e -> {
                    assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.LIMIT);
                    assertThat(e.getMessageKey()).isEqualTo("error.bank.fintable.rateLimited");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(60L);
                    assertThat(e.getProviderErrorCode()).isEqualTo("rate_limited");
                });
        server.verify(); // exactly one request: no retry
    }

    @Test
    void serverErrorIsTransientAndNotRetriedByTheClient() {
        FintableClient client = client(null);
        server.expect(once(), requestTo(BASE + "/me")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(client::me).isInstanceOfSatisfying(FintableConnectorError.class,
                e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT));
        server.verify();
    }

    @Test
    void blankTokenFailsWithConfigWithoutCalling() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        FintableProperties props = new FintableProperties(true, BASE, false, null, null, null,
                null, null, null, null, null, null, null, null, null);
        FintableClient client = new FintableClient(props, builder, props::apiToken);

        assertThatThrownBy(client::me).isInstanceOfSatisfying(FintableConnectorError.class, e -> {
            assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);
            assertThat(e.getMessageKey()).isEqualTo("error.bank.fintable.tokenInvalid");
        });
        server.verify();
    }

    @Test
    void tokenNeverAppearsInExceptionMessagesOrLogs() {
        FintableClient client = client("ws_01ABC");
        // The provider echoes the token in its message; the client must redact it.
        server.expect(once(), requestTo(BASE + "/me?workspace_id=ws_01ABC"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"unauthenticated\",\"message\":\"Bad token " + TOKEN + "\"}}"));
        server.expect(once(), requestTo(BASE + "/connections?workspace_id=ws_01ABC"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/sync?workspace_id=ws_01ABC"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"forbidden\",\"message\":\"no plan\"}}"));

        Throwable unauthorized = org.assertj.core.api.Assertions.catchThrowable(client::me);
        client.connections();
        Throwable forbidden = org.assertj.core.api.Assertions.catchThrowable(client::sync);

        assertThat(unauthorized).isInstanceOf(FintableConnectorError.class);
        assertThat(unauthorized.getMessage()).doesNotContain(TOKEN);
        assertThat(forbidden.getMessage()).doesNotContain(TOKEN);
        assertThat(logs.list).as("client logged something at DEBUG").isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(TOKEN).doesNotContain("Bearer")
                    .doesNotContain("ws_01ABC");
            assertThat(String.valueOf(event.getArgumentArray() == null ? "" : List.of(event.getArgumentArray())))
                    .doesNotContain(TOKEN);
        }
    }
}
