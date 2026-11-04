package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;

/**
 * FOR-07-04 task 8.1 — Salt Edge callbacks through the real filter chain ({@code permitAll} matcher, no
 * JWT), the {@code SaltEdgeCallbackController} signature check and the asynchronous
 * {@code SaltEdgeCallbackHandler}.
 *
 * <p>The verifier is configured with a test public key ({@code foremen.bank-feed.saltedge.callback-public-key});
 * the test signs {@code callbackUrl|body} with the matching private key, where {@code callbackUrl} is
 * derived from the configured {@code foremen.public-base-url}. A {@code destroy} callback signed with the
 * test key is accepted (200) and disables the connection; the same callback signed with a different key
 * (or with a tampered body) is rejected with 401 and changes nothing (R21.5, R10.2).
 *
 * <p>Repeatability: a fresh container per run (Hibernate {@code create-drop} schema); the connections
 * carry per-run external ids.
 *
 * <p>Validates: Requirements 21.5 (R10.2, R10.3)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 8.1: Salt Edge callbacks")
class BankLedgerCallbackIntegrationTest {

    private static final String BASE = "https://foremen.test";
    private static final KeyPair TEST_KEY = generate();
    private static final KeyPair WRONG_KEY = generate();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("foremen.public-base-url", () -> BASE);
        registry.add("foremen.bank-feed.saltedge.callback-public-key", () -> pem(TEST_KEY.getPublic().getEncoded()));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private BankConnectionDao connectionDao;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

    @Test
    @DisplayName("R21.5: a destroy callback signed with the configured test key is accepted and disables the connection")
    void acceptedWithTestKey() throws Exception {
        BankConnectionEntity connection = activeConnection("SE-OK-" + runId);
        byte[] body = destroyBody(connection.getExternalConnectionId());

        int status = callback("destroy", body, sign(TEST_KEY.getPrivate(), BASE + "/api/bank-feed/saltedge/callbacks/destroy", body));

        assertThat(status).isEqualTo(200);
        assertThat(awaitStatus(connection.getId(), BankConnectionStatus.DISABLED))
                .as("the async handler disabled the connection").isEqualTo(BankConnectionStatus.DISABLED);
    }

    @Test
    @DisplayName("R21.5: a callback signed with a wrong key, for another URL or with a tampered body is rejected with 401")
    void rejectedWithWrongKey() throws Exception {
        BankConnectionEntity connection = activeConnection("SE-BAD-" + runId);
        byte[] body = destroyBody(connection.getExternalConnectionId());
        String url = BASE + "/api/bank-feed/saltedge/callbacks/destroy";

        assertThat(callback("destroy", body, sign(WRONG_KEY.getPrivate(), url, body))).as("wrong key").isEqualTo(401);
        assertThat(callback("destroy", body, sign(TEST_KEY.getPrivate(), BASE + "/api/bank-feed/saltedge/callbacks/notify", body)))
                .as("signed for another callback URL").isEqualTo(401);
        byte[] tampered = destroyBody(connection.getExternalConnectionId() + "X");
        assertThat(callback("destroy", tampered, sign(TEST_KEY.getPrivate(), url, body))).as("tampered body").isEqualTo(401);
        assertThat(callback("destroy", body, null)).as("no signature").isEqualTo(401);

        Thread.sleep(500);
        assertThat(connectionDao.findById(connection.getId()).orElseThrow().getStatus())
                .as("nothing was handled").isEqualTo(BankConnectionStatus.ACTIVE);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private int callback(String type, byte[] body, String signature) throws Exception {
        var request = post("/api/bank-feed/saltedge/callbacks/" + type)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Signature-key-version", "6.0")
                .content(body);
        if (signature != null) {
            request.header("Signature", signature);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private BankConnectionEntity activeConnection(String externalId) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setProvider(FeedProvider.SALT_EDGE);
        c.setProviderMode(SaltEdgeMode.CLIENT);
        c.setBankName("Fake Bank " + runId);
        c.setExternalConnectionId(externalId);
        c.setStatus(BankConnectionStatus.ACTIVE);
        return connectionDao.save(c);
    }

    private static byte[] destroyBody(String externalConnectionId) {
        return ("{\"data\":{\"connection_id\":\"" + externalConnectionId + "\",\"customer_id\":\"cust-1\"},"
                + "\"meta\":{\"version\":\"6\",\"time\":\"2026-01-01T00:00:00Z\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private BankConnectionStatus awaitStatus(Long id, BankConnectionStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        BankConnectionStatus current = null;
        while (System.currentTimeMillis() < deadline) {
            current = connectionDao.findById(id).orElseThrow().getStatus();
            if (current == expected) {
                return current;
            }
            Thread.sleep(100);
        }
        return current;
    }

    private static String sign(PrivateKey key, String callbackUrl, byte[] body) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update((callbackUrl + "|").getBytes(StandardCharsets.UTF_8));
            signature.update(body);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(byte[] der) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END PUBLIC KEY-----\n";
    }
}
