package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.fintable.FintableConfig;
import com.foremen.service.bank.connector.fintable.FintableConnector;
import com.foremen.service.bank.connector.fintable.FintableTokenExpiryCheck;
import com.foremen.service.bank.connector.saltedge.SaltEdgeConnector;
import com.foremen.service.bank.sync.BankConsentSweep;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-07-04a task 4.2 — application start-up with the provider flags:
 * <ul>
 *   <li>{@link BothFlagsOff}: Salt Edge and Fintable both disabled — the full context loads, no Fintable bean
 *       exists, the registry has no {@code FINTABLE} connector and offers no provider;</li>
 *   <li>{@link FintableOnWithValidToken}: Fintable enabled with a valid fake token (HTTP base URL allowed
 *       for tests, public base URL set) — the context loads, the registry holds an available
 *       {@code FINTABLE} connector next to the unavailable {@code SALT_EDGE} one, {@code GET
 *       /api/bank/providers} lists only FINTABLE as search-only, and the token-expiry check is wired
 *       into the consent sweep.</li>
 * </ul>
 * No outbound call to Fintable is made at start-up.
 *
 * <p>Repeatability: nothing is written; a fresh container per run (Hibernate {@code create-drop} schema of
 * the {@code integration-test} profile — no migrations are needed for a start-up check).
 *
 * <p>Validates: Requirements 16.3, 16.6 (R1.1, R1.2, R2.1, R2.2)
 */
@Testcontainers
@Tag("Feature: FOR-07-04a-fintable-integration, task 4.2: start-up with provider flags")
class FintableStartupIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    static void datasource(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static String admin(JwtTokenProvider jwt) {
        return "Bearer " + jwt.generateAccessToken(1L, "ADMIN", "fintable-startup@example.com");
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
            "foremen.public-base-url=http://localhost:3000",
            "foremen.bank-feed.saltedge.enabled=false",
            "foremen.bank-feed.stub.enabled=false",
            "foremen.bank-feed.fintable.enabled=false"
    })
    @AutoConfigureMockMvc
    @ActiveProfiles("integration-test")
    @DisplayName("both flags off")
    class BothFlagsOff {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
        }

        @Autowired private ApplicationContext context;
        @Autowired private BankFeedConnectorRegistry registry;
        @Autowired private MockMvc mockMvc;
        @Autowired private JwtTokenProvider jwt;

        @Test
        @DisplayName("the context loads; no Fintable bean; registry has no FINTABLE; no provider is offered")
        void contextLoadsWithoutFintable() throws Exception {
            assertThat(context.getBeanNamesForType(FintableConfig.class)).isEmpty();
            assertThat(context.getBeanNamesForType(FintableConnector.class)).isEmpty();
            assertThat(context.getBeanNamesForType(FintableTokenExpiryCheck.class)).isEmpty();
            assertThat(context.getBean(BankConsentSweep.class)).isNotNull();

            assertThat(registry.find(FeedProvider.FINTABLE)).isEmpty();
            assertThat(registry.isAvailable(FeedProvider.FINTABLE)).isFalse();
            assertThat(registry.isAvailable(FeedProvider.SALT_EDGE)).isFalse();
            assertThat(registry.available()).isEmpty();

            MvcResult providers = mockMvc.perform(get("/api/bank/providers").header("Authorization", admin(jwt))).andReturn();
            assertThat(providers.getResponse().getStatus()).isEqualTo(200);
            assertThat(providers.getResponse().getContentAsString()).isEqualTo("[]");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
            "foremen.public-base-url=http://localhost:3000",
            "foremen.bank-feed.saltedge.enabled=false",
            "foremen.bank-feed.stub.enabled=false",
            "foremen.bank-feed.fintable.enabled=true",
            "foremen.bank-feed.fintable.allow-insecure-base-url=true",
            "foremen.bank-feed.fintable.base-url=http://127.0.0.1:1/api/v2",
            "foremen.bank-feed.fintable.api-token=fake-it-token-0123456789"
    })
    @AutoConfigureMockMvc
    @ActiveProfiles("integration-test")
    @DisplayName("Fintable on with a valid fake token")
    class FintableOnWithValidToken {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
        }

        @Autowired private ApplicationContext context;
        @Autowired private BankFeedConnectorRegistry registry;
        @Autowired private SaltEdgeConnector saltEdge;
        @Autowired private MockMvc mockMvc;
        @Autowired private JwtTokenProvider jwt;

        @Test
        @DisplayName("the context loads; registry shape: FINTABLE available, SALT_EDGE registered but unavailable")
        void registryShape() throws Exception {
            assertThat(context.getBeanNamesForType(FintableConnector.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(FintableTokenExpiryCheck.class)).hasSize(1);
            assertThat(context.getBean(BankConsentSweep.class)).isNotNull();

            assertThat(registry.find(FeedProvider.FINTABLE)).containsInstanceOf(FintableConnector.class);
            assertThat(registry.isAvailable(FeedProvider.FINTABLE)).isTrue();
            assertThat(saltEdge.isAvailable()).isFalse();
            assertThat(registry.isAvailable(FeedProvider.SALT_EDGE)).isFalse();
            assertThat(registry.find(FeedProvider.STUB)).isEmpty();
            assertThat(registry.available()).containsExactly(FeedProvider.FINTABLE);

            FintableConnector connector = (FintableConnector) registry.find(FeedProvider.FINTABLE).orElseThrow();
            assertThat(connector.catalogueSearchOnly()).isTrue();
            assertThat(connector.credentialFields()).isEmpty();

            MvcResult providers = mockMvc.perform(get("/api/bank/providers").header("Authorization", admin(jwt))).andReturn();
            assertThat(providers.getResponse().getStatus()).isEqualTo(200);
            String body = providers.getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(body, "$[*].provider")).containsExactly("FINTABLE");
            assertThat(JsonPath.<Boolean>read(body, "$[0].available")).isTrue();
            assertThat(JsonPath.<Boolean>read(body, "$[0].searchOnly")).isTrue();
            assertThat(JsonPath.<Boolean>read(body, "$[0].credentialsRequired")).isFalse();
        }
    }
}
