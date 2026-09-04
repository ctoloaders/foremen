package com.foremen.dao.integration;

import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.model.OtpTokenEntity;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code otp_tokens} schema introduced by changeset
 * {@code 016-create-otp-tokens} (Requirements 2.1, 2.2), re-applies the changelog to confirm the
 * {@code preConditions onFail="MARK_RAN"} guard makes the migration idempotent (Requirement 2.5),
 * and then drives the mapped {@link OtpTokenEntity} through {@link OtpTokenDao} against the migrated
 * schema to prove the entity/column mapping and JPA auditing populate {@code created_date}
 * (Requirement 1.6) and that the {@code NOT NULL} constraints on {@code email}, {@code code}, and
 * {@code expires_at} reject a row missing any of them (Requirements 1.1, 1.2, 1.3 / 2.1).
 *
 * <p>The database schema and seeded roles are provisioned by applying the full Liquibase changelog
 * against the Testcontainers PostgreSQL instance before the Spring context starts (Spring Boot does
 * not auto-run Liquibase without the dedicated autoconfiguration module, so the changelog is applied
 * explicitly here — the same pattern used by {@code AuthFlowIntegrationTest} /
 * {@code AuthMigrationIntegrationTest}). {@code ddl-auto=none} keeps the mapped entities bound to the
 * real migrated schema rather than a Hibernate-generated one, so the persistence assertions exercise
 * the columns the migration actually creates.
 *
 * <p>Mirrors {@code InviteTokenMigrationIntegrationTest} (changeset 013) for the structural/MARK_RAN
 * checks and {@code ProjectMemberDaoIntegrationTest} for the DAO-level persistence checks.
 *
 * <p>Validates: Requirements 1.6, 2.1, 2.2, 2.5
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
class OtpTokenMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column, matching the deployed app.
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated = false;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        // Ensure the container is up and the schema is applied before the Spring context — and its
        // JPA repositories — start querying the database.
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applyMigrationsOnce();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static synchronized void applyMigrationsOnce() throws Exception {
        if (migrated) {
            return;
        }
        runLiquibase();
        migrated = true;
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void runLiquibase() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    @Autowired
    private OtpTokenDao otpTokenDao;

    // --- Requirement 2.1 / 2.2 : otp_tokens columns, defaults, and the email index ---
    @Test
    @DisplayName("Changeset 016 builds the otp_tokens schema with its columns, defaults, and email index")
    void migrationCreatesOtpTokensSchema() throws Exception {
        assertThat(tableExists("otp_tokens")).as("otp_tokens table").isTrue();

        // Columns with exact types and nullability (2.1).
        assertColumnType("otp_tokens", "id", "bigint", null, false);
        assertColumnType("otp_tokens", "email", "character varying", 255, false);
        assertColumnType("otp_tokens", "code", "character varying", 6, false);
        assertColumnType("otp_tokens", "expires_at", "timestamp without time zone", null, false);
        assertColumnType("otp_tokens", "used", "boolean", null, false);
        assertColumnType("otp_tokens", "attempts", "integer", null, false);
        assertColumnType("otp_tokens", "created_date", "timestamp without time zone", null, false);
        assertColumnType("otp_tokens", "created_by", "character varying", 255, true);
        assertColumnType("otp_tokens", "updated_date", "timestamp without time zone", null, true);
        assertColumnType("otp_tokens", "updated_by", "character varying", 255, true);

        // Defaults (2.1): used defaults to false, attempts to 0, created_date to NOW().
        assertThat(columnDefault("otp_tokens", "used"))
                .as("otp_tokens.used default should be false")
                .containsIgnoringCase("false");
        assertThat(columnDefault("otp_tokens", "attempts"))
                .as("otp_tokens.attempts default should be 0")
                .contains("0");
        assertThat(columnDefault("otp_tokens", "created_date"))
                .as("otp_tokens.created_date default should be NOW()")
                .containsIgnoringCase("now()");

        // PK on id and the email index that backs per-email lookup and rate-limit counting (2.2).
        assertThat(hasPrimaryKey("otp_tokens", "id")).as("otp_tokens PK on id").isTrue();
        assertThat(indexExists("idx_otp_tokens_email"))
                .as("idx_otp_tokens_email index on otp_tokens").isTrue();
    }

    // --- Requirement 2.5 : re-applying the changelog is idempotent (MARK_RAN) ---
    @Test
    @DisplayName("Re-applying the changelog leaves the otp_tokens table and its rows unchanged (MARK_RAN)")
    void migrationIsIdempotent() throws Exception {
        int changeSetsBefore = countAppliedChangeSets();

        // Seed one row so we can prove the MARK_RAN re-run does not drop/recreate the table.
        String email = "otp-mig+" + System.nanoTime() + "@example.com";
        insertOtpTokenRow(email);

        // Re-apply: the MARK_RAN precondition (otp_tokens already exists) must make changeset 016 a
        // no-op — no createTable is re-executed.
        runLiquibase();

        // The schema is unchanged and no changeset was applied twice.
        assertThat(tableExists("otp_tokens")).as("otp_tokens still present after re-run").isTrue();
        assertThat(countAppliedChangeSets())
                .as("re-running the changelog must not add or duplicate changesets")
                .isEqualTo(changeSetsBefore);

        // The columns must still exist exactly once (no duplicate columns from a re-create).
        assertThat(countColumn("otp_tokens", "email")).isEqualTo(1);
        assertThat(countColumn("otp_tokens", "code")).isEqualTo(1);
        assertThat(countColumn("otp_tokens", "expires_at")).isEqualTo(1);

        // The pre-existing row survives the re-run (the table was not dropped/recreated).
        assertThat(otpTokenRowExists(email))
                .as("existing otp_tokens row must survive the MARK_RAN re-run")
                .isTrue();
    }

    // --- Requirement 1.6 : persisting through the mapped entity populates created_date ---
    @Test
    @DisplayName("Persisting an OtpTokenEntity maps to otp_tokens and populates the audited created_date (1.6)")
    void persistingEntityPopulatesCreatedDate() {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail("otp-entity+" + System.nanoTime() + "@example.com");
        token.setCode("007413");
        token.setExpiresAt(Instant.now().plusSeconds(900));

        OtpTokenEntity saved = otpTokenDao.save(token);

        assertThat(saved.getId()).as("generated id").isNotNull();
        assertThat(saved.getCreatedDate())
                .as("created_date populated by JPA auditing on first persist (1.6)")
                .isNotNull();
        // Defaults carried through the mapping (1.4, 1.5).
        assertThat(saved.isUsed()).as("used defaults to false").isFalse();
        assertThat(saved.getAttempts()).as("attempts defaults to 0").isZero();

        OtpTokenEntity reloaded = otpTokenDao.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCreatedDate())
                .as("created_date is persisted and reloaded")
                .isNotNull();
        assertThat(reloaded.getCode()).isEqualTo("007413");
    }

    // --- Requirement 2.1 : the NOT NULL constraints reject rows missing email/code/expires_at ---
    @Test
    @DisplayName("A null email is rejected by the otp_tokens.email NOT NULL constraint")
    void nullEmailIsRejected() {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(null);
        token.setCode("123456");
        token.setExpiresAt(Instant.now().plusSeconds(900));

        // The test is not @Transactional, so save() commits in its own transaction and the INSERT is
        // flushed to the database, surfacing the NOT NULL violation as a DataIntegrityViolationException.
        assertThatThrownBy(() -> otpTokenDao.save(token))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("A null code is rejected by the otp_tokens.code NOT NULL constraint")
    void nullCodeIsRejected() {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail("otp-nullcode+" + System.nanoTime() + "@example.com");
        token.setCode(null);
        token.setExpiresAt(Instant.now().plusSeconds(900));

        assertThatThrownBy(() -> otpTokenDao.save(token))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("A null expiresAt is rejected by the otp_tokens.expires_at NOT NULL constraint")
    void nullExpiresAtIsRejected() {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail("otp-nullexp+" + System.nanoTime() + "@example.com");
        token.setCode("123456");
        token.setExpiresAt(null);

        assertThatThrownBy(() -> otpTokenDao.save(token))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // seeding helpers
    // ---------------------------------------------------------------------

    private void insertOtpTokenRow(String email) throws Exception {
        String sql = "INSERT INTO otp_tokens (email, code, expires_at, used, attempts, created_date) "
                + "VALUES (?, ?, ?, false, 0, NOW())";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, "654321");
            ps.setObject(3, Instant.now().plusSeconds(900)
                    .atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
            ps.executeUpdate();
        }
    }

    private boolean otpTokenRowExists(String email) throws Exception {
        String sql = "SELECT 1 FROM otp_tokens WHERE email = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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

    private boolean indexExists(String indexName) throws Exception {
        String sql = "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countAppliedChangeSets() throws Exception {
        String sql = "SELECT COUNT(*) FROM databasechangelog";
        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
