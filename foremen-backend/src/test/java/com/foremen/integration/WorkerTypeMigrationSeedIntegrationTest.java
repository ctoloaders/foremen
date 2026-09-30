package com.foremen.integration;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-05-06 task 1.3 — WorkerType migration + seed integration test.
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers
 * changeset {@code 125-create-worker-types.xml} last) against a Testcontainers PostgreSQL and proves
 * the end state produced by changeset {@code 125}:
 *
 * <ul>
 *   <li>the {@code worker_types} table exists after migrate;</li>
 *   <li>it holds exactly the four seeded tiers with exactly one {@code is_base = true} (the BASE tier)
 *       and the correct {@code tier_pct} per tier (BASE 0.4000, HIRED_NO_TOOLS 0.1000,
 *       HIRED_SOLE_TRADER 0.2650, FIRM 0.6500) — Requirements 1.4, 6.1, 6.2;</li>
 *   <li>the ABAC {@code WORKER_TYPES} resource row is seeded, and the ADMIN role holds the four CRUD
 *       operation grants ({@code CREATE, READ, UPDATE, DELETE}) on it — Requirement 6.3 (entity-creation
 *       checklist);</li>
 *   <li>re-running the changelog is a no-op: no new {@code worker_types} rows, no duplicate resource,
 *       no duplicate ADMIN grants (idempotency via {@code NOT tableExists} / {@code sqlCheck} +
 *       {@code onFail="MARK_RAN"}) — Requirements 1.4, 6.3.</li>
 * </ul>
 *
 * <p>Harness mirrors {@code UnguardedRequiresAuthIntegrationTest}: it drives Liquibase directly
 * against the container (no Spring context) so the assertions observe exactly what the migration
 * writes. This is inherently repeatable — each JVM run gets a fresh container — so no manual cleanup
 * is required.
 *
 * <p>Validates: Requirements 1.4, 6.3
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerTypeMigrationSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        runChangelog();
    }

    /** Applies the full Liquibase changelog once against the running container. */
    private static void runChangelog() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    // --- schema: table exists (R1.6/R1.4 prerequisite) ---

    @Test
    @DisplayName("worker_types table exists after migrate")
    void workerTypesTableExists() throws Exception {
        boolean exists = queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'worker_types')");
        assertThat(exists)
                .as("changeset 125 must create the worker_types table")
                .isTrue();
    }

    // --- seed: exactly four tiers, exactly one base, correct tier_pct (R1.4, R6.1, R6.2) ---

    @Test
    @DisplayName("worker_types is seeded with exactly the four tiers, one base, correct tier_pct")
    void workerTypesSeededWithFourTiers() throws Exception {
        long rowCount = queryForLong("SELECT COUNT(*) FROM worker_types");
        assertThat(rowCount)
                .as("exactly the four seeded tiers must be present")
                .isEqualTo(4L);

        long baseCount = queryForLong("SELECT COUNT(*) FROM worker_types WHERE is_base = true");
        assertThat(baseCount)
                .as("exactly one worker type must be flagged is_base")
                .isEqualTo(1L);

        Map<String, BigDecimal> tierPctByCode = new HashMap<>();
        Map<String, Boolean> baseByCode = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT code, tier_pct, is_base FROM worker_types")) {
            while (rs.next()) {
                tierPctByCode.put(rs.getString("code"), rs.getBigDecimal("tier_pct"));
                baseByCode.put(rs.getString("code"), rs.getBoolean("is_base"));
            }
        }

        assertThat(tierPctByCode.keySet())
                .as("the four seeded tier codes")
                .containsExactlyInAnyOrder("BASE", "HIRED_NO_TOOLS", "HIRED_SOLE_TRADER", "FIRM");

        assertThat(tierPctByCode.get("BASE")).isEqualByComparingTo("0.4000");
        assertThat(tierPctByCode.get("HIRED_NO_TOOLS")).isEqualByComparingTo("0.1000");
        assertThat(tierPctByCode.get("HIRED_SOLE_TRADER")).isEqualByComparingTo("0.2650");
        assertThat(tierPctByCode.get("FIRM")).isEqualByComparingTo("0.6500");

        assertThat(baseByCode.get("BASE"))
                .as("the BASE tier is the base")
                .isTrue();
        assertThat(baseByCode.get("HIRED_NO_TOOLS")).isFalse();
        assertThat(baseByCode.get("HIRED_SOLE_TRADER")).isFalse();
        assertThat(baseByCode.get("FIRM")).isFalse();
    }

    // --- ABAC: WORKER_TYPES resource + ADMIN CRUD grants (R6.3, entity-creation checklist) ---

    @Test
    @DisplayName("WORKER_TYPES resource is seeded")
    void workerTypesResourceSeeded() throws Exception {
        long resourceCount = queryForLong(
                "SELECT COUNT(*) FROM resources WHERE code = 'WORKER_TYPES'");
        assertThat(resourceCount)
                .as("the ABAC WORKER_TYPES resource must be seeded")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("ADMIN holds CREATE/READ/UPDATE/DELETE grants on WORKER_TYPES")
    void adminHasCrudGrantsOnWorkerTypes() throws Exception {
        List<String> operations = adminOperationsOnWorkerTypes();
        assertThat(operations)
                .as("ADMIN must be granted full CRUD on WORKER_TYPES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
    }

    // --- idempotency: re-running the changelog inserts/duplicates nothing (R1.4, R6.3) ---

    @Test
    @DisplayName("re-running the changelog is a no-op (no new tiers, resource, or ADMIN grants)")
    void reRunIsNoOp() throws Exception {
        long tiersBefore = queryForLong("SELECT COUNT(*) FROM worker_types");
        long resourceBefore = queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'WORKER_TYPES'");
        int grantsBefore = adminOperationsOnWorkerTypes().size();

        // Apply the same changelog a second time.
        runChangelog();

        long tiersAfter = queryForLong("SELECT COUNT(*) FROM worker_types");
        long resourceAfter = queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'WORKER_TYPES'");
        int grantsAfter = adminOperationsOnWorkerTypes().size();

        assertThat(tiersAfter)
                .as("re-run must not insert additional worker_types rows")
                .isEqualTo(tiersBefore)
                .isEqualTo(4L);
        assertThat(resourceAfter)
                .as("re-run must not duplicate the WORKER_TYPES resource")
                .isEqualTo(resourceBefore)
                .isEqualTo(1L);
        assertThat(grantsAfter)
                .as("re-run must not duplicate ADMIN grants on WORKER_TYPES")
                .isEqualTo(grantsBefore)
                .isEqualTo(4);
    }

    // --- helpers ---

    private List<String> adminOperationsOnWorkerTypes() throws Exception {
        List<String> operations = new ArrayList<>();
        String sql = "SELECT o.code "
                + "FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN role_resource_operations rro ON rro.role_resource_id = rr.id "
                + "JOIN operations o ON o.id = rro.operation_id "
                + "WHERE r.code = 'ADMIN' AND res.code = 'WORKER_TYPES'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                operations.add(rs.getString("code"));
            }
        }
        return operations;
    }

    private long queryForLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean queryForBoolean(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
