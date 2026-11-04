package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.bank.BankConnectionController;
import com.foremen.controller.bank.BankTransactionController;
import com.foremen.controller.bank.SaltEdgeCallbackController;
import com.foremen.controller.bank.StubBankFeedController;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.saltedge.SaltEdgeConnector;

/**
 * FOR-07-04 task 8.1 — start-up without any bank-feed configuration: Salt Edge disabled, the STUB off and
 * a <b>blank</b> {@code foremen.public-base-url}. The full application context (all bank controllers,
 * {@code PermissionAnnotationValidator}, the sync scheduler / executor) must load, and the bank surface
 * must degrade instead of failing:
 * <ul>
 *   <li>the {@code SALT_EDGE} connector bean exists but reports unavailable, so the registry's
 *       {@code available()} is empty and {@code GET /api/bank/providers} returns {@code []};</li>
 *   <li>creating a connection is refused with 409 (provider not available);</li>
 *   <li>the stub endpoint is absent (no bean);</li>
 *   <li>a Salt Edge callback cannot be verified without a public base URL → 401.</li>
 * </ul>
 *
 * <p>Repeatability: nothing is written; a fresh container per run (Hibernate {@code create-drop} schema
 * of the {@code integration-test} profile — no migrations are needed for a start-up check).
 *
 * <p>Validates: Requirements 25.3, 21.5 (R8.2, R8.3, R4.4)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "foremen.public-base-url=",
        "foremen.bank-feed.saltedge.enabled=false",
        "foremen.bank-feed.stub.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 8.1: start-up without Salt Edge / public base URL")
class BankLedgerStartupIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private BankFeedConnectorRegistry registry;
    @Autowired private PublicBaseUrl publicBaseUrl;
    @Autowired private SaltEdgeConnector saltEdge;

    private String admin() {
        return "Bearer " + jwt.generateAccessToken(1L, "ADMIN", "startup-admin@example.com");
    }

    @Test
    @DisplayName("the context loads with every bank controller; Salt Edge is registered but unavailable, no STUB")
    void contextLoadsWithSaltEdgeUnavailable() {
        assertThat(context.getBean(BankConnectionController.class)).isNotNull();
        assertThat(context.getBean(BankTransactionController.class)).isNotNull();
        assertThat(context.getBean(SaltEdgeCallbackController.class)).isNotNull();
        assertThat(context.getBeanNamesForType(StubBankFeedController.class)).isEmpty();

        assertThat(publicBaseUrl.isConfigured()).isFalse();
        assertThat(saltEdge.isAvailable()).isFalse();
        assertThat(registry.isAvailable(FeedProvider.SALT_EDGE)).isFalse();
        assertThat(registry.find(FeedProvider.STUB)).isEmpty();
        assertThat(registry.available()).isEmpty();
    }

    @Test
    @DisplayName("providers list is empty and creating a Salt Edge connection is refused with 409")
    void bankSurfaceDegrades() throws Exception {
        MvcResult providers = mockMvc.perform(get("/api/bank/providers").header("Authorization", admin())).andReturn();
        assertThat(providers.getResponse().getStatus()).isEqualTo(200);
        assertThat(providers.getResponse().getContentAsString()).isEqualTo("[]");

        MvcResult create = mockMvc.perform(post("/api/bank/connections").header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"SALT_EDGE\"}")).andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(409);

        assertThat(mockMvc.perform(post("/api/bank/stub/connections/1/data").header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus())
                .as("stub endpoint absent").isIn(404, 405);

        MvcResult ledger = mockMvc.perform(get("/api/bank/transactions").header("Authorization", admin())).andReturn();
        assertThat(ledger.getResponse().getStatus()).as("the ledger still works (cash only)").isEqualTo(200);
    }

    @Test
    @DisplayName("a Salt Edge callback is rejected with 401 while the public base URL is blank")
    void callbackRejectedWithoutBaseUrl() throws Exception {
        byte[] body = "{\"data\":{\"connection_id\":\"1\",\"customer_id\":\"2\"}}".getBytes(StandardCharsets.UTF_8);
        int status = mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", "c2lnbmF0dXJl")
                        .header("Signature-key-version", "6.0")
                        .content(body))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }
}
