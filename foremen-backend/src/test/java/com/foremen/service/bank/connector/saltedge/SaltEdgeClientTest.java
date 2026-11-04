package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * FOR-07-04 task 5.1 — {@link SaltEdgeClient} against {@link MockRestServiceServer}: headers, single
 * body serialization + signing, error mapping, TRANSIENT retry with injected delays, pagination.
 *
 * <p><b>Validates: Requirements 8.4, 8.5, 8.6, 12.1, 12.2</b>
 */
class SaltEdgeClientTest {

    private static final String BASE = "https://www.saltedge.com/api/v6";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static KeyPair keyPair;

    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static SaltEdgeProperties props(String appId, String secret) {
        return new SaltEdgeProperties(true, SaltEdgeMode.CLIENT, null, appId, secret, null, null, null, null, 0,
                null, null, null, null);
    }

    private SaltEdgeClient client(RestClient.Builder builder, SaltEdgeRequestSigner signer) {
        return new SaltEdgeClient(props("app-123", "secret-456"), builder, signer, null,
                Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add, SaltEdgeClient.DEFAULT_RETRY_DELAYS);
    }

    @Test
    void sendsAuthHeadersAndSignsTheExactBodySent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> sentBody = new AtomicReference<>();
        AtomicReference<String> sentSignature = new AtomicReference<>();
        long expectedExpiresAt = NOW.getEpochSecond() + 60;

        server.expect(once(), requestTo(BASE + "/customers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("App-id", "app-123"))
                .andExpect(header("Secret", "secret-456"))
                .andExpect(header("Expires-at", Long.toString(expectedExpiresAt)))
                .andExpect(request -> {
                    sentBody.set(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
                    sentSignature.set(request.getHeaders().getFirst("Signature"));
                })
                .andRespond(withSuccess("{\"data\":{\"customer_id\":\"777\"}}", MediaType.APPLICATION_JSON));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", Map.of("identifier", "foremen-company"));
        JsonNode response = client(builder, new SaltEdgeRequestSigner(keyPair.getPrivate()))
                .post("/customers", body);

        server.verify();
        assertThat(response.path("data").path("customer_id").asText()).isEqualTo("777");
        assertThat(sentBody.get()).isEqualTo("{\"data\":{\"identifier\":\"foremen-company\"}}");
        assertThat(verify(expectedExpiresAt + "|POST|" + BASE + "/customers|" + sentBody.get(), sentSignature.get()))
                .isTrue();
    }

    @Test
    void unsignedWhenNoKeyConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(BASE + "/customers"))
                .andExpect(request -> {
                    assertThat(request.getHeaders().containsHeader("Signature")).isFalse();
                    assertThat(request.getHeaders().containsHeader("Expires-at")).isFalse();
                })
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        client(builder, null).get("/customers");

        server.verify();
    }

    @Test
    void retriesTransientThreeTimesWithBackoffThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(3), requestTo(BASE + "/connections/1")).andRespond(withServerError());
        server.expect(once(), requestTo(BASE + "/connections/1"))
                .andRespond(withSuccess("{\"data\":{\"id\":\"1\"}}", MediaType.APPLICATION_JSON));

        JsonNode response = client(builder, null).get("/connections/1");

        server.verify();
        assertThat(response.path("data").path("id").asText()).isEqualTo("1");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(16));
    }

    @Test
    void transientAfterAllRetriesIsThrown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(4), requestTo(BASE + "/connections/1")).andRespond(withServerError());

        assertThatThrownBy(() -> client(builder, null).get("/connections/1"))
                .isInstanceOfSatisfying(ConnectorError.class,
                        e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT));
        server.verify();
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void nonTransientErrorIsMappedAndNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(BASE + "/connections/1/refresh"))
                .andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"class\":\"ConnectionCannotBeRefreshed\",\"message\":\"later\","
                                + "\"request_id\":\"r-9\"}}"));

        assertThatThrownBy(() -> client(builder, null).put("/connections/1/refresh", "{\"data\":{}}"))
                .isInstanceOfSatisfying(ConnectorError.class, e -> {
                    assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.LIMIT);
                    assertThat(e.getProviderErrorCode()).isEqualTo("ConnectionCannotBeRefreshed");
                    assertThat(e.getRequestId()).isEqualTo("r-9");
                });
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void paginatesOnNextId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(BASE + "/transactions?connection_id=5&account_id=9"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"meta\":{\"next_id\":\"3\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/transactions?connection_id=5&account_id=9&from_id=3"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"3\"}],\"meta\":{\"next_id\":null}}",
                        MediaType.APPLICATION_JSON));

        Map<String, String> query = new LinkedHashMap<>();
        query.put("connection_id", "5");
        query.put("account_id", "9");
        List<JsonNode> all = client(builder, null).getAll("/transactions", query);

        server.verify();
        assertThat(all).extracting(n -> n.path("id").asText()).containsExactly("1", "2", "3");
    }

    @Test
    void missingKeysFailWithConfigWithoutCalling() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SaltEdgeClient client = new SaltEdgeClient(props(null, null), builder, null, null, Clock.systemUTC(),
                sleeps::add, SaltEdgeClient.DEFAULT_RETRY_DELAYS);

        assertThatThrownBy(() -> client.get("/customers"))
                .isInstanceOfSatisfying(ConnectorError.class,
                        e -> assertThat(e.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG));
        server.verify();
    }

    @Test
    void redactsSecretsInLoggedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("App-id", "app-123");
        headers.set("Secret", "secret-456");
        headers.set("Signature", "c2ln");
        headers.set("Expires-at", "1");

        Map<String, String> redacted = SaltEdgeClient.redactHeaders(headers);

        assertThat(redacted).containsEntry("App-id", "***").containsEntry("Secret", "***")
                .containsEntry("Signature", "***").containsEntry("Expires-at", "1");
        assertThat(redacted.toString()).doesNotContain("app-123", "secret-456", "c2ln");
    }

    @Test
    void propertiesUseModeDefaultBaseUrlAndRedactToString() {
        SaltEdgeProperties partner = new SaltEdgeProperties(true, SaltEdgeMode.PARTNER, " ", "a", "s", null, null,
                null, "finance@example.com", 0, null, null, null, null);

        assertThat(partner.effectiveBaseUrl()).isEqualTo("https://www.saltedge.com/api/partners/v1");
        assertThat(props("a", "s").effectiveBaseUrl()).isEqualTo(BASE);
        assertThat(props("app-123", "secret-456").toString()).doesNotContain("app-123", "secret-456");
        assertThat(props("a", "s").customerIdentifier()).isEqualTo("foremen-company");
        assertThat(props("a", "s").fetchDays()).isEqualTo(89);
        assertThat(props("a", "s").banksCacheTtl()).isEqualTo(Duration.ofHours(1));
    }

    private static boolean verify(String canonical, String signatureB64) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }
}
