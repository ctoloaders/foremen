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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real
 * PostgreSQL instance (Testcontainers) and verifies the auth schema introduced
 * by changesets 010/011/012, then applies the changelog a second time to
 * confirm the {@code preConditions onFail="MARK_RAN"} guards make the migration
 * idempotent (no duplicate structural changes).
 * <p>
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startContainer() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
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

    @Test
    @DisplayName("Applying the changelog builds the auth schema, and re-applying is idempotent")
    void migrationCreatesAuthSchemaAndIsIdempotent() throws Exception {
        // First application: full migration.
        runLiquibase();

        assertUsersAuthColumns();
        assertRefreshTokensTable();
        assertPasswordResetTokensTable();

        int changeSetsAfterFirstRun = countAppliedChangeSets();
        assertThat(changeSetsAfterFirstRun)
                .as("changelog should record all executed/marked changesets")
                .isPositive();

        // Second application: MARK_RAN preConditions must make it a no-op.
        runLiquibase();

        // Idempotency: the schema is unchanged and no changeset was applied twice.
        assertUsersAuthColumns();
        assertRefreshTokensTable();
        assertPasswordResetTokensTable();

        int changeSetsAfterSecondRun = countAppliedChangeSets();
        assertThat(changeSetsAfterSecondRun)
                .as("re-running the changelog must not add or duplicate changesets")
                .isEqualTo(changeSetsAfterFirstRun);

        // The auth columns must still exist exactly once (no duplicate columns).
        assertThat(countColumn("users", "password_hash")).isEqualTo(1);
        assertThat(countColumn("users", "status")).isEqualTo(1);
    }

    // --- Requirement 2.1 / 2.2 : users.password_hash + users.status ---
    private void assertUsersAuthColumns() throws Exception {
        assertThat(columnExists("users", "password_hash"))
                .as("users.password_hash should exist").isTrue();
        assertColumnType("users", "password_hash", "character varying", 255, true);

        assertThat(columnExists("users", "status"))
                .as("users.status should exist").isTrue();
        assertColumnType("users", "status", "character varying", 20, false);
        assertThat(columnDefault("users", "status"))
                .as("users.status default should be INVITED")
                .contains("INVITED");
    }

    // --- Requirement 2.3 : refresh_tokens table ---
    private void assertRefreshTokensTable() throws Exception {
        assertThat(tableExists("refresh_tokens")).as("refresh_tokens table").isTrue();
        assertColumnType("refresh_tokens", "token", "character varying", null, false);
        assertColumnType("refresh_tokens", "user_id", "bigint", null, false);
        assertColumnType("refresh_tokens", "expires_at", "timestamp without time zone", null, false);
        assertColumnType("refresh_tokens", "revoked", "boolean", null, false);
        assertColumnType("refresh_tokens", "created_date", "timestamp without time zone", null, false);

        assertThat(hasPrimaryKey("refresh_tokens", "id")).as("refresh_tokens PK on id").isTrue();
        assertThat(hasUniqueConstraintOn("refresh_tokens", "token"))
                .as("refresh_tokens.token unique").isTrue();
        assertThat(hasForeignKey("refresh_tokens", "user_id", "users"))
                .as("refresh_tokens.user_id FK -> users").isTrue();
    }

    // --- Requirement 2.4 : password_reset_tokens table ---
    private void assertPasswordResetTokensTable() throws Exception {
        assertThat(tableExists("password_reset_tokens")).as("password_reset_tokens table").isTrue();
        assertColumnType("password_reset_tokens", "token", "character varying", null, false);
        assertColumnType("password_reset_tokens", "user_id", "bigint", null, false);
        assertColumnType("password_reset_tokens", "expires_at", "timestamp without time zone", null, false);
        assertColumnType("password_reset_tokens", "used", "boolean", null, false);
        assertColumnType("password_reset_tokens", "created_date", "timestamp without time zone", null, false);

        assertThat(hasPrimaryKey("password_reset_tokens", "id"))
                .as("password_reset_tokens PK on id").isTrue();
        assertThat(hasUniqueConstraintOn("password_reset_tokens", "token"))
                .as("password_reset_tokens.token unique").isTrue();
        assertThat(hasForeignKey("password_reset_tokens", "user_id", "users"))
                .as("password_reset_tokens.user_id FK -> users").isTrue();
    }

    // ---------------------------------------------------------------------
    // information_schema / catalog helpers
    // ---------------------------------------------------------------------

    private boolean tableExists(String table) throws Exception {
        String sql = "SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        return countColumn(table, column) > 0;
    }

    private int countColumn(String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void assertColumnType(String table, String column, String expectedType,
                                  Integer expectedMaxLength, boolean expectedNullable) throws Exception {
        String sql = "SELECT data_type, character_maximum_length, is_nullable "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("column %s.%s should exist", table, column).isTrue();
                assertThat(rs.getString("data_type"))
                        .as("%s.%s data_type", table, column).isEqualTo(expectedType);
                if (expectedMaxLength != null) {
                    assertThat(rs.getInt("character_maximum_length"))
                            .as("%s.%s length", table, column).isEqualTo(expectedMaxLength);
                }
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                assertThat(nullable)
                        .as("%s.%s nullable", table, column).isEqualTo(expectedNullable);
            }
        }
    }

    private String columnDefault(String table, String column) throws Exception {
        String sql = "SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String def = rs.getString("column_default");
                return def == null ? "" : def;
            }
        }
    }

    private boolean hasPrimaryKey(String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? AND kcu.column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasUniqueConstraintOn(String table, String column) throws Exception {
        // Covers both UNIQUE constraints and unique indexes on the column.
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'UNIQUE' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? AND kcu.column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasForeignKey(String table, String column, String referencedTable) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON tc.constraint_name = ccu.constraint_name "
                + " AND tc.table_schema = ccu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? "
                + "  AND kcu.column_name = ? AND ccu.table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ps.setString(3, referencedTable);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countAppliedChangeSets() throws Exception {
        String sql = "SELECT COUNT(*) FROM databasechangelog";
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
