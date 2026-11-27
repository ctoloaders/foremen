package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.sync.BankSyncScheduler;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-07-04a task 4.2 — behaviour with Salt Edge switched off while its connections stay in the database
 * (Requirements 1.2, 1.4–1.7): {@code providerAvailable=false} in the connection DTO, the scheduler skips
 * the connection (no run, no status change), a Salt Edge callback is acknowledged as ignored before any
 * verification, and {@code disable} works locally without a provider call. Real Liquibase schema.
 *
 * <p>Repeatability: a fresh container per run; connections and the admin user carry a per-run id.
 *
 * <p>Validates: Requirements 16.3, 16.6 (R1.2, R1.4, R1.5, R1.6, R1.7)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=false",
        "foremen.bank-feed.saltedge.enabled=false",
        "foremen.bank-feed.fintable.enabled=false",
        "foremen.public-base-url=https://foremen.test",
        "foremen.bank-feed.sync.cron=-",
        "foremen.bank-feed.consent.sweep-cron=-"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04a-fintable-integration, task 4.2: Salt Edge disabled")
class SaltEdgeDisabledIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (SaltEdgeDisabledIntegrationTest.class) {
            if (!migrated) {
                BankLedgerItSupport.migrate(POSTGRES);
                migrated = true;
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private BankConnectionDao connectionDao;
    @Autowired private BankSyncRunDao runDao;
    @Autowired private BankSyncScheduler scheduler;
    @Autowired private BankFeedConnectorRegistry registry;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

    @Test
    @DisplayName("Salt Edge off: providerAvailable=false, scheduler skip, callback ignored, local disable")
    void saltEdgeSwitchedOff() throws Exception {
        UserEntity admin = new TransactionTemplate(transactionManager).execute(s -> {
            UserEntity u = new UserEntity();
            u.setName("Salt Edge off IT admin");
            u.setEmail("saltedge-off+" + runId.toLowerCase(Locale.ROOT) + "@example.com");
            u.setRole(roleDao.findByCode("ADMIN").orElseThrow());
            u.setActive(true);
            return userDao.save(u);
        });
        String auth = "Bearer " + jwt.generateAccessToken(admin.getId(), "ADMIN", admin.getEmail());
        BankConnectionEntity connection = activeSaltEdgeConnection("SE-OFF-" + runId);
        long id = connection.getId();

        assertThat(registry.isAvailable(FeedProvider.SALT_EDGE)).isFalse();

        // --- providerAvailable=false in the detail and the list; the provider is not offered
        String detail = body(mockMvc.perform(get("/api/bank/connections/" + id).header("Authorization", auth)).andReturn(), 200);
        assertThat(JsonPath.<Boolean>read(detail, "$.providerAvailable")).isFalse();
        assertThat(JsonPath.<String>read(detail, "$.status")).isEqualTo("ACTIVE");
        String list = body(mockMvc.perform(get("/api/bank/connections").header("Authorization", auth)).andReturn(), 200);
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.id==" + id + ")].providerAvailable")).containsExactly(false);
        String providers = body(mockMvc.perform(get("/api/bank/providers").header("Authorization", auth)).andReturn(), 200);
        assertThat(providers).isEqualTo("[]");

        // --- the scheduler skips it: no run, no status change
        assertThat(scheduler.tick()).as("no run requested for an unavailable provider").isZero();
        assertThat(runDao.findByConnectionIdOrderByIdDesc(id, PageRequest.of(0, 10))).isEmpty();
        assertThat(connectionDao.findById(id).orElseThrow().getStatus()).isEqualTo(BankConnectionStatus.ACTIVE);

        // --- a callback is acknowledged as ignored, even unsigned, and changes nothing
        byte[] destroy = ("{\"data\":{\"connection_id\":\"" + connection.getExternalConnectionId()
                + "\",\"customer_id\":\"cust-1\"}}").getBytes(StandardCharsets.UTF_8);
        MvcResult callback = mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/destroy")
                .contentType(MediaType.APPLICATION_JSON).content(destroy)).andReturn();
        assertThat(callback.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(callback.getResponse().getContentAsString(), "$.ignored"))
                .isEqualTo("provider disabled");
        Thread.sleep(500);
        assertThat(connectionDao.findById(id).orElseThrow().getStatus())
                .as("the ignored callback changed nothing").isEqualTo(BankConnectionStatus.ACTIVE);

        // --- manual provider actions are refused (provider not available)
        assertThat(mockMvc.perform(post("/api/bank/connections/" + id + "/reconnect").header("Authorization", auth))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        // --- disable works locally, without a provider call
        String disabled = body(mockMvc.perform(post("/api/bank/connections/" + id + "/disable")
                .header("Authorization", auth)).andReturn(), 200);
        assertThat(JsonPath.<String>read(disabled, "$.status")).isEqualTo("DISABLED");
        assertThat(connectionDao.findById(id).orElseThrow().getStatus()).isEqualTo(BankConnectionStatus.DISABLED);
    }

    private BankConnectionEntity activeSaltEdgeConnection(String externalId) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setProvider(FeedProvider.SALT_EDGE);
        c.setProviderMode(SaltEdgeMode.CLIENT);
        c.setBankName("Fake Bank " + runId);
        c.setDisplayName("Salt Edge off " + runId);
        c.setExternalConnectionId(externalId);
        c.setStatus(BankConnectionStatus.ACTIVE);
        return connectionDao.save(c);
    }

    private static String body(MvcResult result, int expectedStatus) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("%s %s → %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), content)
                .isEqualTo(expectedStatus);
        return content;
    }
}
