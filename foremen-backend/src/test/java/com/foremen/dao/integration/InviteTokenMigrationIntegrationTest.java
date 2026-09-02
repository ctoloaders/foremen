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
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code invite_tokens} schema introduced by changeset
 * {@code 013-create-invite-tokens}, then applies the changelog a second time to confirm the
 * {@code preConditions onFail="MARK_RAN"} guard makes the migration idempotent — the table and
 * any pre-existing data are left unchanged.
 *
 * <p>Mirrors {@code AuthMigrationIntegrationTest} (changesets 010/011/012), which established the
 * pattern for structural migration verification in this module.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InviteTokenMigrationIntegrationTest {

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
    @DisplayName("Changeset 013 builds the invite_tokens schema, and re-applying leaves the table and data unchanged")
    void migrationCreatesInviteTokensTableAndIsIdempotent() throws Exception {
        // First application: full migration.
        runLiquibase();

        assertInviteTokensTable();

        int changeSetsAfterFirstRun = countAppliedChangeSets();
        assertThat(changeSetsAfterFirstRun)
                .as("changelog should record all executed/marked changesets")
                .isPositive();

        // Seed one user and one invite-token row so we can prove the MARK_RAN re-run does not
        // drop/recreate the table (which would discard the data).
        long userId = insertUser();
        String token = insertInviteToken(userId);

        // Second application: the MARK_RAN precondition (invite_tokens already exists) must make
        // changeset 013 a no-op — no createTable is re-executed.
        runLiquibase();

        // Idempotency: the schema is unchanged and no changeset was applied twice.
        assertInviteTokensTable();

        int changeSetsAfterSecondRun = countAppliedChangeSets();
        assertThat(changeSetsAfterSecondRun)
                .as("re-running the changelog must not add or duplicate changesets")
                .isEqualTo(changeSetsAfterFirstRun);

        // The columns must still exist exactly once (no duplicate columns from a re-create).
        assertThat(countColumn("invite_tokens", "token")).isEqualTo(1);
        assertThat(countColumn("invite_tokens", "used")).isEqualTo(1);
        assertThat(countColumn("invite_tokens", "expires_at")).isEqualTo(1);

        // The pre-existing invite-token row survives the re-run (table was not dropped/recreated).
        assertThat(inviteTokenExists(token))
                .as("existing invite-token row must survive the MARK_RAN re-run")
                .isTrue();
        assertThat(countInviteTokens())
                .as("MARK_RAN re-run must not alter row count")
                .isEqualTo(1);
    }

    // --- Requirement 2.1 / 2.2 : invite_tokens table, columns and constraints ---
    private void assertInviteTokensTable() throws Exception {
        assertThat(tableExists("invite_tokens")).as("invite_tokens table").isTrue();

        // Columns with exact types and nullability (2.1).
        assertColumnType("invite_tokens", "id", "bigint", null, false);
        assertColumnType("invite_tokens", "token", "character varying", 255, false);
        assertColumnType("invite_tokens", "user_id", "bigint", null, false);
        assertColumnType("invite_tokens", "expires_at", "timestamp without time zone", null, false);
        assertColumnType("invite_tokens", "used", "boolean", null, false);
        assertColumnType("invite_tokens", "created_date", "timestamp without time zone", null, false);
        assertColumnType("invite_tokens", "created_by", "character varying", 255, true);
        assertColumnType("invite_tokens", "updated_date", "timestamp without time zone", null, true);
        assertColumnType("invite_tokens", "updated_by", "character varying", 255, true);

        // Defaults (2.1): used defaults to false; created_date defaults to NOW().
        assertThat(columnDefault("invite_tokens", "used"))
                .as("invite_tokens.used default should be false")
                .containsIgnoringCase("false");
        assertThat(columnDefault("invite_tokens", "created_date"))
                .as("invite_tokens.created_date default should be NOW()")
                .containsIgnoringCase("now()");

        // Constraints (2.1): PK on id, unique uk_invite_tokens_token on token, FK to users.
        assertThat(hasPrimaryKey("invite_tokens", "id")).as("invite_tokens PK on id").isTrue();
        assertThat(hasUniqueConstraintOn("invite_tokens", "token"))
                .as("invite_tokens.token unique").isTrue();
        assertThat(constraintExists("uk_invite_tokens_token"))
                .as("named unique constraint uk_invite_tokens_token").isTrue();
        assertThat(hasForeignKey("invite_tokens", "user_id", "users"))
                .as("invite_tokens.user_id FK -> users").isTrue();
        assertThat(constraintExists("fk_invite_tokens_user"))
                .as("named foreign key fk_invite_tokens_user").isTrue();
    }

    // ---------------------------------------------------------------------
    // seeding helpers
    // ---------------------------------------------------------------------

    private long insertUser() throws Exception {
        // Reuse the CLIENT role seeded by changeset 006; a valid role_id keeps the FK satisfied.
        String sql = "INSERT INTO users (name, email, role_id, status, created_date) "
                + "VALUES (?, ?, (SELECT id FROM roles WHERE code = 'CLIENT' LIMIT 1), 'INVITED', NOW()) "
                + "RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "Invite Migration Test User");
            ps.setString(2, "invite-migration+" + System.nanoTime() + "@example.com");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String insertInviteToken(long userId) throws Exception {
        String token = "mig-test-" + System.nanoTime();
        String sql = "INSERT INTO invite_tokens (token, user_id, expires_at, used, created_date) "
                + "VALUES (?, ?, ?, false, NOW())";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.setObject(3, Instant.now().plusSeconds(3600).atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
            ps.executeUpdate();
        }
        return token;
    }

    private boolean inviteTokenExists(String token) throws Exception {
        String sql = "SELECT 1 FROM invite_tokens WHERE token = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countInviteTokens() throws Exception {
        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM invite_tokens")) {
            rs.next();
            return rs.getInt(1);
        }
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

    private boolean constraintExists(String constraintName) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND constraint_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
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
