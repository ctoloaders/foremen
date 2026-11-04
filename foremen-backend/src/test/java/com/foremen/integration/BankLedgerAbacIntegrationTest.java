package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-07-04 task 8.1 — the ABAC matrix of the two bank resources over the real HTTP + Spring Security +
 * {@code PermissionInterceptor} stack, with the matrix exactly as the Liquibase changelog seeds it
 * (changesets 205 / 206 on Testcontainers PostgreSQL), plus seed idempotency.
 *
 * <ul>
 *   <li>{@code BANK_CONNECTIONS}: ADMIN, FINANCIER CRUD; MANAGER, FOREMAN none (R21.1);</li>
 *   <li>{@code BANK_LEDGER}: ADMIN, FINANCIER, MANAGER CRUD; FOREMAN none (R21.2);</li>
 *   <li>re-applying the changesets (changelog rows removed) changes nothing; a plain re-run records
 *       nothing new.</li>
 * </ul>
 * An allowed call reaches the handler (2xx, or a business 400 / 404 on a non-existent id); a denied call
 * is {@code 403} before the handler runs.
 *
 * <p>Repeatability: a fresh container per run; users carry a per-run id; the HTTP probes only read or
 * target non-existent ids (no data is created by the matrix test).
 *
 * <p>Validates: Requirements 21.1, 21.2, 21.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=true",
        "foremen.public-base-url=http://localhost:3000"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 8.1: ABAC matrix + seeds")
class BankLedgerAbacIntegrationTest {

    private static final List<String> CRUD = List.of("CREATE", "DELETE", "READ", "UPDATE");
    private static final long MISSING = 987_654_321L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (BankLedgerAbacIntegrationTest.class) {
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
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);

    // --- seeded matrix ---------------------------------------------------------------------------

    @Test
    @DisplayName("R21.1 / R21.2: seeded grants — BANK_CONNECTIONS ADMIN+FINANCIER, BANK_LEDGER ADMIN+FINANCIER+MANAGER, FOREMAN none")
    void seededMatrix() throws Exception {
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES,
                "SELECT COUNT(*) FROM resources WHERE code IN ('BANK_CONNECTIONS','BANK_LEDGER') "
                        + "AND name_ru <> '' AND name_pl <> ''")).isEqualTo(2);
        for (String role : List.of("ADMIN", "FINANCIER")) {
            assertThat(ops(role, "BANK_CONNECTIONS")).as("%s on BANK_CONNECTIONS", role).isEqualTo(CRUD);
        }
        for (String role : List.of("MANAGER", "FOREMAN")) {
            assertThat(ops(role, "BANK_CONNECTIONS")).as("%s on BANK_CONNECTIONS", role).isEmpty();
        }
        for (String role : List.of("ADMIN", "FINANCIER", "MANAGER")) {
            assertThat(ops(role, "BANK_LEDGER")).as("%s on BANK_LEDGER", role).isEqualTo(CRUD);
        }
        assertThat(ops("FOREMAN", "BANK_LEDGER")).as("FOREMAN on BANK_LEDGER").isEmpty();
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code IN ('BANK_CONNECTIONS','BANK_LEDGER') "
                + "AND r.code NOT IN ('ADMIN','FINANCIER','MANAGER')"))
                .as("deny-by-default: no other role has a bank grant row").isZero();
    }

    @Test
    @DisplayName("seeds are idempotent: re-applying 205/206 (changelog rows removed) inserts nothing; a plain re-run records nothing")
    void seedsIdempotent() throws Exception {
        String slice = "id LIKE '205-seed-bank-%' OR id LIKE '206-seed-bank-%'";
        long sliceRows = BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM databasechangelog WHERE " + slice);
        assertThat(sliceRows).as("four seed changesets recorded").isEqualTo(4);
        String before = matrixFingerprint();

        BankLedgerItSupport.execute(POSTGRES, "DELETE FROM databasechangelog WHERE " + slice);
        BankLedgerItSupport.runChangelog(POSTGRES);

        assertThat(BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM databasechangelog WHERE " + slice))
                .isEqualTo(sliceRows);
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM databasechangelog WHERE ("
                + "id = '205-seed-bank-connections-resource' OR id = '206-seed-bank-ledger-resource') "
                + "AND exectype = 'MARK_RAN'")).as("resource inserts skipped as MARK_RAN").isEqualTo(2);
        assertThat(matrixFingerprint()).as("resources + grants unchanged").isEqualTo(before);

        long all = BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM databasechangelog");
        BankLedgerItSupport.runChangelog(POSTGRES);
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM databasechangelog")).isEqualTo(all);
        assertThat(matrixFingerprint()).isEqualTo(before);
    }

    // --- HTTP matrix -----------------------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated calls to both resources are 401")
    void unauthenticated() throws Exception {
        assertThat(status(get("/api/bank/connections"))).isEqualTo(401);
        assertThat(status(get("/api/bank/transactions"))).isEqualTo(401);
    }

    @Test
    @DisplayName("R21.1 / R21.2: ADMIN, FINANCIER, MANAGER, FOREMAN against every operation of BANK_CONNECTIONS and BANK_LEDGER")
    void httpMatrix() throws Exception {
        Map<String, Set<String>> grants = Map.of(
                "ADMIN", Set.of("BANK_CONNECTIONS", "BANK_LEDGER"),
                "FINANCIER", Set.of("BANK_CONNECTIONS", "BANK_LEDGER"),
                "MANAGER", Set.of("BANK_LEDGER"),
                "FOREMAN", Set.of());
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Set<String>> role : grants.entrySet()) {
            String auth = bearer(role.getKey());
            for (Probe probe : probes()) {
                int status = status(probe.request().header("Authorization", auth));
                boolean allowed = role.getValue().contains(probe.resource());
                boolean ok = allowed
                        ? status != 401 && status != 403 && (probe.expectedWhenAllowed() == 0
                                || status == probe.expectedWhenAllowed())
                        : status == 403;
                if (!ok) {
                    failures.add(role.getKey() + " " + probe.name() + " → " + status
                            + (allowed ? " (expected allowed" + (probe.expectedWhenAllowed() == 0 ? ""
                                    : " " + probe.expectedWhenAllowed()) + ")" : " (expected 403)"));
                }
            }
        }
        assertThat(failures).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("create payout from a ledger line also needs PAYROLL/CREATE: MANAGER / FINANCIER reach the handler, FOREMAN 403")
    void createPayoutNeedsPayrollToo() throws Exception {
        String body = "{\"workerId\":1}";
        for (String role : List.of("MANAGER", "FINANCIER")) {
            int s = status(post("/api/bank/transactions/" + MISSING + "/payout")
                    .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer(role)));
            assertThat(s).as("%s create payout on a missing line", role).isEqualTo(404);
        }
        assertThat(status(post("/api/bank/transactions/" + MISSING + "/payout")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer("FOREMAN"))))
                .isEqualTo(403);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Probe(String name, String resource, MockHttpServletRequestBuilder request, int expectedWhenAllowed) {
    }

    /** Fresh request builders (a builder cannot be reused across performs once headers are added). */
    private List<Probe> probes() {
        String json = MediaType.APPLICATION_JSON_VALUE;
        String c = "BANK_CONNECTIONS";
        String l = "BANK_LEDGER";
        return List.of(
                new Probe("GET connections", c, get("/api/bank/connections"), 200),
                new Probe("GET providers", c, get("/api/bank/providers"), 200),
                new Probe("GET STUB banks", c, get("/api/bank/providers/STUB/banks"), 200),
                new Probe("GET accounts", c, get("/api/bank/accounts"), 200),
                new Probe("GET cash-accounts", c, get("/api/bank/cash-accounts"), 200),
                new Probe("GET sync-runs", c, get("/api/bank/sync-runs"), 200),
                new Probe("GET counterparty-rules", c, get("/api/bank/counterparty-rules"), 200),
                new Probe("POST connection (no provider)", c,
                        post("/api/bank/connections").contentType(json).content("{}"), 400),
                new Probe("PATCH missing account", c,
                        patch("/api/bank/accounts/" + MISSING).contentType(json).content("{\"includedInLedger\":true}"), 404),
                new Probe("POST sync of missing connection", c, post("/api/bank/connections/" + MISSING + "/sync"), 404),
                new Probe("DELETE missing connection", c, delete("/api/bank/connections/" + MISSING), 404),
                new Probe("DELETE missing rule", c, delete("/api/bank/counterparty-rules/" + MISSING), 404),
                new Probe("POST stub seed of missing connection", c,
                        post("/api/bank/stub/connections/" + MISSING + "/data").contentType(json).content("{}"), 404),
                new Probe("GET transactions", l, get("/api/bank/transactions"), 200),
                new Probe("GET missing transaction", l, get("/api/bank/transactions/" + MISSING), 404),
                new Probe("POST cash entry (empty)", l,
                        post("/api/bank/transactions/cash").contentType(json).content("{}"), 400),
                new Probe("PUT missing cash entry", l,
                        put("/api/bank/transactions/" + MISSING).contentType(json).content("{}"), 404),
                new Probe("DELETE missing cash entry", l, delete("/api/bank/transactions/" + MISSING), 404),
                new Probe("POST link on missing line", l, post("/api/bank/transactions/" + MISSING + "/links")
                        .contentType(json).content("{\"kind\":\"OTHER_EXPENSE\",\"amount\":1}"), 404),
                new Probe("POST ignore missing line", l, post("/api/bank/transactions/" + MISSING + "/ignore")
                        .contentType(json).content("{\"reason\":\"x\"}"), 404),
                new Probe("DELETE missing link", l, delete("/api/bank/links/" + MISSING), 404));
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private final Map<String, String> tokens = new LinkedHashMap<>();

    /** A real user of the seeded role (created once per role) and its access token. */
    private String bearer(String roleCode) {
        return tokens.computeIfAbsent(roleCode, code -> {
            UserEntity user = new TransactionTemplate(transactionManager).execute(s -> {
                UserEntity u = new UserEntity();
                u.setName("Bank ABAC " + code + " " + runId);
                u.setEmail("bank-abac-" + code.toLowerCase(Locale.ROOT) + "+" + runId + "@example.com");
                u.setRole(roleDao.findByCode(code).orElseThrow());
                u.setActive(true);
                return userDao.save(u);
            });
            return "Bearer " + jwt.generateAccessToken(user.getId(), code, user.getEmail());
        });
    }

    private List<String> ops(String role, String resource) throws Exception {
        return BankLedgerItSupport.operationsFor(POSTGRES, role, resource);
    }

    private String matrixFingerprint() throws Exception {
        List<String> rows = BankLedgerItSupport.queryForStrings(POSTGRES,
                "SELECT res.code || ':' || res.name_ru || ':' || res.name_pl || ':' || "
                        + "COALESCE((SELECT string_agg(r.code || '=' || o.code, ',' ORDER BY r.code, o.code) "
                        + "FROM role_resources rr JOIN roles r ON rr.role_id = r.id "
                        + "JOIN role_resource_operations rro ON rro.role_resource_id = rr.id "
                        + "JOIN operations o ON rro.operation_id = o.id WHERE rr.resource_id = res.id), '') || ':' || "
                        + "(SELECT COUNT(*) FROM role_resources rr WHERE rr.resource_id = res.id) "
                        + "FROM resources res WHERE res.code IN ('BANK_CONNECTIONS','BANK_LEDGER') ORDER BY res.code");
        return String.join("|", rows);
    }
}
