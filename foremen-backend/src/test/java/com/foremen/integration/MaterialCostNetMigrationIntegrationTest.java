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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-05-06 task 3.3 — material {@code cost_net} migration integration test.
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers
 * changesets {@code 126-add-construction-material-cost-net.xml} and
 * {@code 127-add-finishing-material-cost-net.xml} last) against a Testcontainers PostgreSQL and
 * proves the end state produced by changesets {@code 126}/{@code 127}:
 *
 * <ul>
 *   <li>the {@code cost_net} column exists on both {@code construction_materials} and
 *       {@code finishing_materials} — Requirement 3.1;</li>
 *   <li>the column is {@code NOT NULL} on both tables — Requirement 3.3;</li>
 *   <li>every seeded row with a non-null {@code retail_net} has
 *       {@code cost_net = round(retail_net * 0.9, 2)} — Requirement 3.2;</li>
 *   <li>every seeded row with a null {@code retail_net} has {@code cost_net = 0} (the sentinel that
 *       lets the column be NOT NULL) — Requirement 3.3;</li>
 *   <li>re-running the full changelog is a no-op: the {@code cost_net} values are unchanged and no
 *       error is raised (idempotency via {@code NOT columnExists} on the add and a COUNT-guarded
 *       one-shot back-fill) — Requirements 3.1, 3.2, 3.3.</li>
 * </ul>
 *
 * <p>Harness mirrors {@code WorkerTypeMigrationSeedIntegrationTest}: it drives Liquibase directly
 * against the container (no Spring context) via the Liquibase API and queries via raw JDBC, so the
 * assertions observe exactly what the migration writes. This is inherently repeatable — each JVM run
 * gets a fresh container — so no manual cleanup is required.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaterialCostNetMigrationIntegrationTest {

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

    // --- schema: cost_net column exists on both catalogs (R3.1) ---

    @Test
    @DisplayName("cost_net column exists on construction_materials and finishing_materials")
    void costNetColumnExistsOnBothCatalogs() throws Exception {
        assertThat(columnExists("construction_materials", "cost_net"))
                .as("changeset 126 must add cost_net to construction_materials")
                .isTrue();
        assertThat(columnExists("finishing_materials", "cost_net"))
                .as("changeset 127 must add cost_net to finishing_materials")
                .isTrue();
    }

    // --- schema: cost_net is NOT NULL on both catalogs (R3.3) ---

    @Test
    @DisplayName("cost_net is NOT NULL on both catalogs")
    void costNetIsNotNullOnBothCatalogs() throws Exception {
        assertThat(isNullable("construction_materials", "cost_net"))
                .as("cost_net on construction_materials must be NOT NULL")
                .isFalse();
        assertThat(isNullable("finishing_materials", "cost_net"))
                .as("cost_net on finishing_materials must be NOT NULL")
                .isFalse();
    }

    // --- back-fill: cost_net = round(retail_net * 0.9, 2) for non-null retail (R3.2) ---

    @Test
    @DisplayName("construction_materials cost_net back-filled to round(retail_net*0.9,2) where retail is non-null")
    void constructionCostNetBackfilledForNonNullRetail() throws Exception {
        assertNonNullRetailBackfilled("construction_materials");
    }

    @Test
    @DisplayName("finishing_materials cost_net back-filled to round(retail_net*0.9,2) where retail is non-null")
    void finishingCostNetBackfilledForNonNullRetail() throws Exception {
        assertNonNullRetailBackfilled("finishing_materials");
    }

    // --- back-fill: cost_net = 0 for null retail (R3.3) ---

    @Test
    @DisplayName("construction_materials cost_net is 0 where retail_net is null")
    void constructionCostNetZeroForNullRetail() throws Exception {
        assertNullRetailIsZero("construction_materials");
    }

    @Test
    @DisplayName("finishing_materials cost_net is 0 where retail_net is null")
    void finishingCostNetZeroForNullRetail() throws Exception {
        assertNullRetailIsZero("finishing_materials");
    }

    // --- idempotency: re-running the changelog leaves cost_net unchanged, no error (R3.1-3.3) ---

    @Test
    @DisplayName("re-running the changelog is a no-op (cost_net values unchanged, no error)")
    void reRunIsNoOp() throws Exception {
        Map<Long, BigDecimal> constructionBefore = costNetById("construction_materials");
        Map<Long, BigDecimal> finishingBefore = costNetById("finishing_materials");

        // Apply the same changelog a second time — must not error, must not change values.
        runChangelog();

        Map<Long, BigDecimal> constructionAfter = costNetById("construction_materials");
        Map<Long, BigDecimal> finishingAfter = costNetById("finishing_materials");

        assertCostNetUnchanged("construction_materials", constructionBefore, constructionAfter);
        assertCostNetUnchanged("finishing_materials", finishingBefore, finishingAfter);
    }

    // --- shared assertions ---

    /**
     * Every row with a non-null retail_net must have cost_net = round(retail_net*0.9, 2). Asserted in
     * SQL (COUNT of violations = 0) and guarded against vacuity by requiring at least one such row.
     */
    private void assertNonNullRetailBackfilled(String table) throws Exception {
        long nonNullRetailRows = queryForLong(
                "SELECT COUNT(*) FROM " + table + " WHERE retail_net IS NOT NULL");
        assertThat(nonNullRetailRows)
                .as("%s must have seeded rows with a non-null retail_net for a meaningful back-fill check", table)
                .isGreaterThan(0L);

        long mismatches = queryForLong(
                "SELECT COUNT(*) FROM " + table + " "
                        + "WHERE retail_net IS NOT NULL AND cost_net <> round(retail_net * 0.9, 2)");
        assertThat(mismatches)
                .as("every %s row with a non-null retail_net must be back-filled to round(retail_net*0.9,2)", table)
                .isEqualTo(0L);
    }

    /** Every row with a null retail_net must have cost_net = 0 (the sentinel). */
    private void assertNullRetailIsZero(String table) throws Exception {
        long nullRetailNonZero = queryForLong(
                "SELECT COUNT(*) FROM " + table + " "
                        + "WHERE retail_net IS NULL AND cost_net <> 0");
        assertThat(nullRetailNonZero)
                .as("every %s row with a null retail_net must keep cost_net = 0", table)
                .isEqualTo(0L);
    }

    private void assertCostNetUnchanged(
            String table, Map<Long, BigDecimal> before, Map<Long, BigDecimal> after) {
        assertThat(after.keySet())
                .as("re-run must not add or remove %s rows", table)
                .containsExactlyInAnyOrderElementsOf(before.keySet());
        for (Map.Entry<Long, BigDecimal> entry : before.entrySet()) {
            assertThat(after.get(entry.getKey()))
                    .as("re-run must not change cost_net for %s row id=%d", table, entry.getKey())
                    .isEqualByComparingTo(entry.getValue());
        }
    }

    // --- helpers ---

    private boolean columnExists(String table, String column) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "')");
    }

    private boolean isNullable(String table, String column) throws Exception {
        return queryForBoolean(
                "SELECT (is_nullable = 'YES') FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "'");
    }

    private Map<Long, BigDecimal> costNetById(String table) throws Exception {
        Map<Long, BigDecimal> byId = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, cost_net FROM " + table)) {
            while (rs.next()) {
                byId.put(rs.getLong("id"), rs.getBigDecimal("cost_net"));
            }
        }
        return byId;
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
