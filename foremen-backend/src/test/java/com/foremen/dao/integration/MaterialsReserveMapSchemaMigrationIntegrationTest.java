package com.foremen.dao.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the FOR-05-05b list-of-materials schema addition introduced by
 * changeset {@code 124-add-estimate-materials-reserve-map}:
 * <ul>
 *     <li><b>Column present, JSONB, nullable (R4.2).</b> After migrate the shipped
 *         {@code estimates} table carries a {@code materials_reserve_map} column whose data type is
 *         {@code jsonb} and which is nullable (a fresh estimate, or one with no reserves set, is
 *         {@code NULL} — which reads as identity, no reserve).</li>
 *     <li><b>Idempotent re-migrate.</b> Re-running the whole changelog is a no-op for changeset
 *         {@code 124}: it is guarded by {@code NOT columnExists} + {@code MARK_RAN}, so a second
 *         application makes no schema change — the column stays a single {@code jsonb}, nullable
 *         column (no duplicate, no type or nullability drift).</li>
 * </ul>
 *
 * <p>Mirrors the {@code EstimateSchemaMigrationIntegrationTest} /
 * {@code BillOfMaterialsSchemaMigrationIntegrationTest} convention: run the real changelog against a
 * real database via raw Liquibase and assert against the live schema through
 * {@code information_schema} using raw JDBC.
 *
 * <p>Validates: Requirements 4.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaterialsReserveMapSchemaMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final String ESTIMATES_TABLE = "estimates";
    private static final String RESERVE_MAP_COLUMN = "materials_reserve_map";

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

    // ------------------------------------------------------------------
    // 124 : estimates.materials_reserve_map exists, is jsonb and nullable (R4.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Changeset 124 adds estimates.materials_reserve_map as a nullable jsonb column (R4.2)")
    void addsMaterialsReserveMapJsonbNullableColumn() throws Exception {
        assertThat(columnExists(ESTIMATES_TABLE, RESERVE_MAP_COLUMN))
                .as("estimates.materials_reserve_map exists after migrate (R4.2)")
                .isTrue();

        assertThat(dataType(ESTIMATES_TABLE, RESERVE_MAP_COLUMN))
                .as("estimates.materials_reserve_map data type is jsonb (R4.2)")
                .isEqualTo("jsonb");

        assertThat(isNullable(ESTIMATES_TABLE, RESERVE_MAP_COLUMN))
                .as("estimates.materials_reserve_map is nullable — NULL reads as identity/no reserve (R4.2)")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Re-migrate is a no-op for the reserve-map column (idempotent 124)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the changelog is a no-op for estimates.materials_reserve_map (idempotent 124)")
    void reRunningChangelogIsNoOpForReserveMapColumn() throws Exception {
        // Re-apply the entire changelog. Changeset 124 is guarded by NOT columnExists + MARK_RAN, so
        // a second application makes no further schema change.
        runLiquibase();

        // Exactly one materials_reserve_map column on estimates — no duplicate from the re-run.
        assertThat(columnCount(ESTIMATES_TABLE, RESERVE_MAP_COLUMN))
                .as("exactly one estimates.materials_reserve_map column after re-migrate (idempotent)")
                .isEqualTo(1);

        // Type and nullability unchanged after the second application.
        assertThat(dataType(ESTIMATES_TABLE, RESERVE_MAP_COLUMN))
                .as("estimates.materials_reserve_map data type still jsonb after re-migrate")
                .isEqualTo("jsonb");
        assertThat(isNullable(ESTIMATES_TABLE, RESERVE_MAP_COLUMN))
                .as("estimates.materials_reserve_map still nullable after re-migrate")
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // information_schema query helpers
    // ---------------------------------------------------------------------

    /** Whether the given (table, column) exists in the public schema. */
    private boolean columnExists(String tableName, String columnName) throws Exception {
        return columnCount(tableName, columnName) > 0;
    }

    /** The number of columns with the given name on the given table in the public schema. */
    private int columnCount(String tableName, String columnName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The {@code data_type} of the given (table, column) in the public schema, or {@code null}. */
    private String dataType(String tableName, String columnName) throws Exception {
        String sql = "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
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

    /**
     * Whether the given (table, column) is nullable in the public schema (reads
     * {@code is_nullable = 'YES'}). Returns {@code false} when the column is absent.
     */
    private boolean isNullable(String tableName, String columnName) throws Exception {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "YES".equalsIgnoreCase(rs.getString(1));
                }
                return false;
            }
        }
    }
}
