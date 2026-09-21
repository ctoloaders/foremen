package com.foremen.dao.integration;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the FOR-05-03 estimate schema introduced by changesets
 * {@code 076}–{@code 080}:
 * <ul>
 *     <li>the five estimate tables exist after migrate: {@code estimates}, {@code estimate_lines},
 *         {@code estimate_line_room_qty}, {@code estimate_line_package_prices},
 *         {@code estimate_line_package_price_history} (Requirements 1.1, 2.4, 4.2, 4.4);</li>
 *     <li>the {@code estimates.project_id} UNIQUE constraint enforcing the 1:1 invariant exists
 *         (Requirement 1.1);</li>
 *     <li>the {@code estimate_line_package_prices (line_id, offer_package_id)} UNIQUE constraint
 *         exists (Requirement 4.2);</li>
 *     <li>the two {@code ON DELETE SET NULL} provenance FKs exist:
 *         {@code estimate_lines.work_price_id} and
 *         {@code estimate_line_package_prices.work_package_price_id} (Requirements 2.4, 4.4);</li>
 *     <li>re-running the changelog is a no-op: every changeset is guarded by
 *         {@code tableExists} + {@code MARK_RAN}, so a second application changes no schema.</li>
 * </ul>
 *
 * <p>Mirrors the {@code WorkCatalogResourceSeedIntegrationTest} convention: run the real changelog
 * against a real database via raw Liquibase and assert against the live schema through
 * {@code information_schema} using raw JDBC.
 *
 * <p>Validates: Requirements 1.1, 2.4, 4.2, 4.4
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EstimateSchemaMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> ESTIMATE_TABLES = List.of(
            "estimates",
            "estimate_lines",
            "estimate_line_room_qty",
            "estimate_line_package_prices",
            "estimate_line_package_price_history");

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startContainerAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        runLiquibase();
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void runLiquibase() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    // --- Requirements 1.1, 2.4, 4.2, 4.4 : the five estimate tables exist after migrate ---
    @Test
    @DisplayName("Changesets 076-080 create the five estimate tables")
    void createsTheFiveEstimateTables() throws Exception {
        for (String table : ESTIMATE_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s exists after migrate", table)
                    .isTrue();
        }
    }

    // --- Requirement 1.1 : estimates.project_id UNIQUE enforces the 1:1 invariant ---
    @Test
    @DisplayName("estimates.project_id has a UNIQUE constraint (1:1 invariant)")
    void estimatesProjectIdIsUnique() throws Exception {
        assertThat(uniqueConstraintColumns("estimates"))
                .as("a UNIQUE constraint on estimates covers exactly [project_id]")
                .contains(List.of("project_id"));
    }

    // --- Requirement 4.2 : (line_id, offer_package_id) UNIQUE — one row per (line, package) ---
    @Test
    @DisplayName("estimate_line_package_prices (line_id, offer_package_id) is UNIQUE")
    void packagePriceLinePackageIsUnique() throws Exception {
        assertThat(uniqueConstraintColumns("estimate_line_package_prices"))
                .as("a UNIQUE constraint covers exactly [line_id, offer_package_id]")
                .contains(List.of("line_id", "offer_package_id"));
    }

    // --- Requirement 2.4 : estimate_lines.work_price_id provenance FK is ON DELETE SET NULL ---
    @Test
    @DisplayName("estimate_lines.work_price_id provenance FK is ON DELETE SET NULL")
    void estimateLinesWorkPriceFkIsSetNull() throws Exception {
        assertThat(deleteRuleForForeignKeyColumn("estimate_lines", "work_price_id"))
                .as("estimate_lines.work_price_id FK delete rule")
                .isEqualTo("SET NULL");
    }

    // --- Requirement 4.4 : estimate_line_package_prices.work_package_price_id FK is SET NULL ---
    @Test
    @DisplayName("estimate_line_package_prices.work_package_price_id provenance FK is ON DELETE SET NULL")
    void packagePriceWorkPackagePriceFkIsSetNull() throws Exception {
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_package_prices", "work_package_price_id"))
                .as("estimate_line_package_prices.work_package_price_id FK delete rule")
                .isEqualTo("SET NULL");
    }

    // --- Re-migrate is a no-op : tables + constraints unchanged after a second changelog run ---
    @Test
    @DisplayName("Re-running the changelog is a no-op for the estimate schema")
    void reRunningChangelogIsNoOp() throws Exception {
        // Re-apply the entire changelog. Every 076-080 changeset is guarded by tableExists +
        // MARK_RAN, so a second application makes no schema change.
        runLiquibase();

        for (String table : ESTIMATE_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s still exists after re-migrate", table)
                    .isTrue();
        }
        assertThat(uniqueConstraintColumns("estimates"))
                .as("estimates UNIQUE constraints unchanged after re-migrate")
                .contains(List.of("project_id"));
        assertThat(uniqueConstraintColumns("estimate_line_package_prices"))
                .as("package price UNIQUE constraints unchanged after re-migrate")
                .contains(List.of("line_id", "offer_package_id"));
        assertThat(deleteRuleForForeignKeyColumn("estimate_lines", "work_price_id"))
                .as("estimate_lines.work_price_id FK delete rule unchanged after re-migrate")
                .isEqualTo("SET NULL");
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_package_prices", "work_package_price_id"))
                .as("package price provenance FK delete rule unchanged after re-migrate")
                .isEqualTo("SET NULL");
    }

    // ---------------------------------------------------------------------
    // information_schema query helpers
    // ---------------------------------------------------------------------

    /** Whether a base table with the given name exists in the public schema. */
    private boolean tableExists(String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ? AND table_type = 'BASE TABLE'";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * All UNIQUE constraints on the given table, each as the ordered list of its column names.
     * Reads {@code table_constraints} (constraint_type = 'UNIQUE') joined to
     * {@code key_column_usage} ordered by {@code ordinal_position}.
     */
    private List<List<String>> uniqueConstraintColumns(String tableName) throws Exception {
        String constraintsSql = "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'UNIQUE'";
        List<String> constraintNames = new ArrayList<>();
        try (Connection c = newConnection();
                PreparedStatement ps = c.prepareStatement(constraintsSql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraintNames.add(rs.getString(1));
                }
            }
        }

        List<List<String>> result = new ArrayList<>();
        String columnsSql = "SELECT column_name FROM information_schema.key_column_usage "
                + "WHERE table_schema = 'public' AND constraint_name = ? "
                + "ORDER BY ordinal_position";
        for (String constraintName : constraintNames) {
            try (Connection c = newConnection();
                    PreparedStatement ps = c.prepareStatement(columnsSql)) {
                ps.setString(1, constraintName);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> columns = new ArrayList<>();
                    while (rs.next()) {
                        columns.add(rs.getString(1));
                    }
                    result.add(columns);
                }
            }
        }
        return result;
    }

    /**
     * The {@code ON DELETE} rule of the foreign-key constraint on the given (table, column),
     * e.g. {@code "SET NULL"}, {@code "CASCADE"}, {@code "RESTRICT"} / {@code "NO ACTION"}.
     * Joins {@code key_column_usage} (to find the FK constraint on the column) with
     * {@code referential_constraints} (which carries {@code delete_rule}).
     */
    private String deleteRuleForForeignKeyColumn(String tableName, String columnName)
            throws Exception {
        String sql = "SELECT rc.delete_rule "
                + "FROM information_schema.referential_constraints rc "
                + "JOIN information_schema.table_constraints tc "
                + "  ON rc.constraint_name = tc.constraint_name "
                + " AND rc.constraint_schema = tc.constraint_schema "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON rc.constraint_name = kcu.constraint_name "
                + " AND rc.constraint_schema = kcu.constraint_schema "
                + "WHERE tc.table_schema = 'public' "
                + "  AND tc.table_name = ? "
                + "  AND tc.constraint_type = 'FOREIGN KEY' "
                + "  AND kcu.column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
            }
        }
    }
}
