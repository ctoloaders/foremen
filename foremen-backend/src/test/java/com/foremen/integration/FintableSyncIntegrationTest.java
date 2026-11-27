package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.UserEntity;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-07-04a task 4.2 — the Fintable connector end to end over HTTP: a local {@link FakeFintableServer}
 * (connections, accounts, two transaction pages, {@code /sync} states), real beans and the real Liquibase
 * schema (Testcontainers PostgreSQL). Flow: {@code POST /api/bank/connections} (Fintable link) →
 * {@code POST /{id}/complete} with the return parameters → asynchronous CONNECT sync on
 * {@code bankSyncExecutor} → manual sync.
 *
 * <p>Asserts the account, the three ledger rows (both pages), no pending rows, the stored {@code u:}
 * resume cursor ({@code max updated_at}), and an idempotent second run (no new rows, same cursor, the
 * request carries {@code updated_since = cursor − overlap}).
 *
 * <p>{@code GET /sync} semantics are unconfirmed; the fake follows what {@code FintableConnector} reads
 * today ({@code connections.<id>.sync_status.state / finished_at}).
 *
 * <p>Repeatability: a fresh container and a fresh fake server per run; connection names and the admin user
 * carry a per-run id.
 *
 * <p>Validates: Requirements 16.3, 16.6 (R4–R9)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=false",
        "foremen.bank-feed.saltedge.enabled=false",
        "foremen.bank-feed.fintable.enabled=true",
        "foremen.bank-feed.fintable.allow-insecure-base-url=true",
        "foremen.bank-feed.fintable.api-token=fake-it-token-0123456789",
        "foremen.bank-feed.fintable.sync-poll-interval=100ms",
        "foremen.public-base-url=http://localhost:3000",
        "foremen.bank-feed.sync.cron=-",
        "foremen.bank-feed.consent.sweep-cron=-"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04a-fintable-integration, task 4.2: Fintable sync end to end")
class FintableSyncIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static final FakeFintableServer FAKE = new FakeFintableServer();
    private static boolean migrated;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (FintableSyncIntegrationTest.class) {
            if (!migrated) {
                BankLedgerItSupport.migrate(POSTGRES);
                migrated = true;
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("foremen.bank-feed.fintable.base-url", FAKE::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        FAKE.close();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private BankConnectionDao connectionDao;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);

    @Test
    @DisplayName("connect → complete → sync: account, 3 ledger rows from 2 pages, u: cursor, idempotent re-run, no pending rows")
    void connectCompleteSync() throws Exception {
        UserEntity admin = new TransactionTemplate(transactionManager).execute(s -> {
            UserEntity u = new UserEntity();
            u.setName("Fintable IT admin");
            u.setEmail("fintable-it+" + runId + "@example.com");
            u.setRole(roleDao.findByCode("ADMIN").orElseThrow());
            u.setActive(true);
            return userDao.save(u);
        });
        String auth = "Bearer " + jwt.generateAccessToken(admin.getId(), "ADMIN", admin.getEmail());

        // --- the provider is offered, search-only
        String providers = body(mockMvc.perform(get("/api/bank/providers").header("Authorization", auth)).andReturn(), 200);
        assertThat(JsonPath.<List<String>>read(providers, "$[*].provider")).containsExactly("FINTABLE");
        assertThat(JsonPath.<List<Boolean>>read(providers, "$[?(@.provider=='FINTABLE')].searchOnly")).containsExactly(true);

        // --- connect: a Fintable link is minted for the chosen slug
        String created = body(perform(auth, post("/api/bank/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"FINTABLE\",\"providerBankCode\":\"pko-bp-enablebanking\","
                        + "\"displayName\":\"Fintable " + runId + "\"}")), 201);
        assertThat(JsonPath.<String>read(created, "$.connection.status")).isEqualTo("PENDING_AUTH");
        assertThat(JsonPath.<String>read(created, "$.authorizationUrl")).isEqualTo("https://fintable.test/link/abc123");
        long connectionId = JsonPath.<Number>read(created, "$.connection.id").longValue();
        assertThat(FAKE.requests("POST " + FakeFintableServer.BASE_PATH + "/connections/link")).isNotEmpty();

        // --- complete with the return parameters; the CONNECT run is queued
        String completion = body(perform(auth, post("/api/bank/connections/" + connectionId + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{\"connection_id\":\"" + FakeFintableServer.CONNECTION_ID
                        + "\",\"status\":\"connected\"}}")), 200);
        assertThat(JsonPath.<Boolean>read(completion, "$.completed")).isTrue();
        String firstRun = awaitRun(auth, JsonPath.<Number>read(completion, "$.syncRunId").longValue());
        assertThat(JsonPath.<String>read(firstRun, "$.status")).as(firstRun).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<String>read(firstRun, "$.trigger")).isEqualTo("CONNECT");
        assertThat(JsonPath.<Integer>read(firstRun, "$.accountsSeen")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(firstRun, "$.transactionsNew")).isEqualTo(3);

        BankConnectionEntity connection = connectionDao.findById(connectionId).orElseThrow();
        assertThat(connection.getProvider()).isEqualTo(FeedProvider.FINTABLE);
        assertThat(connection.getExternalConnectionId()).isEqualTo(FakeFintableServer.CONNECTION_ID);
        assertThat(connection.getStatus().name()).isEqualTo("ACTIVE");

        // --- the account
        BankAccountEntity account = accountDao
                .findByConnectionIdAndExternalId(connectionId, FakeFintableServer.ACCOUNT_ID).orElseThrow();
        assertThat(account.getIban()).isEqualTo(FakeFintableServer.IBAN);
        assertThat(account.getCurrency()).isEqualTo("PLN");
        assertThat(accountDao.findByConnectionIdOrderByIdAsc(connectionId)).hasSize(1);

        // --- the ledger rows (both pages), no pending rows
        List<BankTransactionEntity> posted = transactionDao.findByAccountIdAndStatus(account.getId(), BankTxnStatus.POSTED);
        assertThat(posted).extracting(BankTransactionEntity::getExternalId)
                .containsExactlyInAnyOrder("tx_fake_1", "tx_fake_2", "tx_fake_3");
        assertThat(posted).filteredOn(t -> t.getExternalId().equals("tx_fake_1")).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo(new BigDecimal("-100.00")));
        assertThat(posted).filteredOn(t -> t.getExternalId().equals("tx_fake_2")).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo(new BigDecimal("250.50")));
        assertThat(transactionDao.findByAccountIdAndStatus(account.getId(), BankTxnStatus.PENDING)).isEmpty();

        // --- the stored resume cursor is the max updated_at
        String expectedCursor = "u:" + FakeFintableServer.UPDATED_3;
        assertThat(accountDao.findById(account.getId()).orElseThrow().getTransactionsCursor()).isEqualTo(expectedCursor);

        // first run: first page without a cursor, then the page cursor; no updated_since yet
        List<String> firstRunTxnRequests = FAKE.requests("GET " + FakeFintableServer.BASE_PATH + "/accounts/"
                + FakeFintableServer.ACCOUNT_ID + "/transactions");
        assertThat(firstRunTxnRequests).hasSize(2);
        assertThat(firstRunTxnRequests.get(0)).doesNotContain("cursor=").doesNotContain("updated_since=");
        assertThat(firstRunTxnRequests.get(1)).contains("cursor=page2");

        // --- second run: idempotent
        String accepted = body(perform(auth, post("/api/bank/connections/" + connectionId + "/sync")), 202);
        String secondRun = awaitRun(auth, JsonPath.<Number>read(accepted, "$.runId").longValue());
        assertThat(JsonPath.<String>read(secondRun, "$.status")).as(secondRun).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(secondRun, "$.transactionsNew")).as("no new rows").isZero();

        assertThat(transactionDao.findByAccountIdAndStatus(account.getId(), BankTxnStatus.POSTED)).hasSize(3);
        assertThat(transactionDao.findByAccountIdAndStatus(account.getId(), BankTxnStatus.PENDING)).isEmpty();
        assertThat(accountDao.findById(account.getId()).orElseThrow().getTransactionsCursor())
                .as("the cursor never moves backwards").isEqualTo(expectedCursor);

        List<String> allTxnRequests = FAKE.requests("GET " + FakeFintableServer.BASE_PATH + "/accounts/"
                + FakeFintableServer.ACCOUNT_ID + "/transactions");
        assertThat(allTxnRequests).hasSize(4);
        // resume: updated_since = cursor (12:00:00Z) minus the default 1 h overlap, repeated with the page cursor
        assertThat(allTxnRequests.get(2)).contains("updated_since=2026-11-18T11:00:00Z").doesNotContain("cursor=");
        assertThat(allTxnRequests.get(3)).contains("updated_since=2026-11-18T11:00:00Z").contains("cursor=page2");

        // the provider-side sync state was consulted before pulling, and every call was authenticated
        assertThat(FAKE.requests("GET " + FakeFintableServer.BASE_PATH + "/sync")).isNotEmpty();
        assertThat(FAKE.allAuthenticatedRequestsCarriedToken()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult perform(String auth, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth)).andReturn();
    }

    private static String body(MvcResult result, int expectedStatus) throws Exception {
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("%s %s → %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), content)
                .isEqualTo(expectedStatus);
        return content;
    }

    /** Polls the run until it leaves QUEUED / RUNNING (it executes on {@code bankSyncExecutor}). */
    private String awaitRun(String auth, long runId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        String run = null;
        while (System.currentTimeMillis() < deadline) {
            run = body(perform(auth, get("/api/bank/sync-runs/" + runId)), 200);
            String status = JsonPath.read(run, "$.status");
            if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("sync run " + runId + " did not finish: " + run);
    }
}
