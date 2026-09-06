package com.foremen.controller.integration;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12.3 — Seed completeness and idempotency integration test.
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}) against a
 * throwaway PostgreSQL container ({@code postgres:16-alpine}) using the same
 * {@code ClassLoaderResourceAccessor} + {@code DatabaseFactory} pattern as
 * {@link UnguardedRequiresAuthIntegrationTest}'s {@code applyMigrationsOnce()} helper, then
 * verifies the seed's <b>completeness</b> and <b>idempotency</b> at the raw-SQL level:
 *
 * <ol>
 *   <li><b>Completeness</b> — after the first migration, the six matrix resource codes
 *       ({@code USERS}, {@code ROLES}, {@code AUDIT}, {@code RESOURCES}, {@code OPERATIONS},
 *       {@code PROJECT_MEMBERS}) all exist, and the {@code ADMIN} role holds the full
 *       {@code CREATE}/{@code READ}/{@code UPDATE}/{@code DELETE} grant on {@code USERS}
 *       (Requirements 11.1, 11.2, 11.3, 11.4).</li>
 *   <li><b>Idempotency</b> — running {@code liquibase.update} a second time against the
 *       already-seeded database does not insert a duplicate {@code USERS} resource row and does
 *       not add extra {@code ADMIN}/{@code USERS} grants; the counts are identical before and
 *       after the re-run, proving the changeset {@code preConditions} guards (Requirement 11.4).</li>
 * </ol>
 *
 * <p>The test queries via plain JDBC and does not boot a Spring context. It creates no persistent
 * state outside its own disposable container, so it is fully repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3, 11.4
 */
@Testcontainers
class SeedCompletenessIdempotencyIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> EXPECTED_RESOURCE_CODES = List.of(
            "USERS", "ROLES", "AUDIT", "RESOURCES", "OPERATIONS", "PROJECT_MEMBERS");

    private static final List<String> ADMIN_USERS_OPERATIONS = List.of(
            "CREATE", "READ", "UPDATE", "DELETE");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    static void applyMigrations() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        runChangelog();
    }

    private static void runChangelog() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // --- Completeness (Req 11.1, 11.2, 11.3) ---

    @Test
    @DisplayName("All six matrix resource codes exist after migration")
    void sixResourceCodesExist() throws Exception {
        try (Connection connection = newConnection()) {
            for (String code : EXPECTED_RESOURCE_CODES) {
                assertThat(countResourcesByCode(connection, code))
                        .as("resource code '%s' must exist exactly once after seeding", code)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("ADMIN holds the full CREATE/READ/UPDATE/DELETE grant on USERS after migration")
    void adminHoldsUsersCrudGrants() throws Exception {
        try (Connection connection = newConnection()) {
            assertThat(adminUsersRoleResourceCount(connection))
                    .as("ADMIN must have exactly one role_resources row for USERS")
                    .isEqualTo(1);

            for (String op : ADMIN_USERS_OPERATIONS) {
                assertThat(adminUsersOperationCount(connection, op))
                        .as("ADMIN must hold the '%s' operation grant on USERS", op)
                        .isEqualTo(1);
            }
        }
    }

    // --- Idempotency (Req 11.4) ---

    @Test
    @DisplayName("Re-running the changelog against a seeded DB inserts no duplicate USERS resource or ADMIN grants")
    void rerunningChangelogIsIdempotent() throws Exception {
        long usersResourcesBefore;
        long adminUsersRoleResourcesBefore;
        long adminUsersOperationsBefore;

        try (Connection connection = newConnection()) {
            usersResourcesBefore = countResourcesByCode(connection, "USERS");
            adminUsersRoleResourcesBefore = adminUsersRoleResourceCount(connection);
            adminUsersOperationsBefore = adminUsersOperationTotalCount(connection);
        }

        // Apply the entire changelog a second time against the already-seeded database.
        runChangelog();

        try (Connection connection = newConnection()) {
            assertThat(countResourcesByCode(connection, "USERS"))
                    .as("re-running the changelog must not insert a duplicate USERS resource row")
                    .isEqualTo(usersResourcesBefore)
                    .isEqualTo(1);

            assertThat(adminUsersRoleResourceCount(connection))
                    .as("re-running the changelog must not add a duplicate ADMIN/USERS role_resources row")
                    .isEqualTo(adminUsersRoleResourcesBefore)
                    .isEqualTo(1);

            assertThat(adminUsersOperationTotalCount(connection))
                    .as("re-running the changelog must not add duplicate ADMIN/USERS operation grants")
                    .isEqualTo(adminUsersOperationsBefore)
                    .isEqualTo(ADMIN_USERS_OPERATIONS.size());
        }
    }

    // --- JDBC query helpers ---

    private static long countResourcesByCode(Connection connection, String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM resources WHERE code = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, code);
            return singleLong(ps);
        }
    }

    private static long adminUsersRoleResourceCount(Connection connection) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resources rr
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                WHERE r.code = 'ADMIN' AND res.code = 'USERS'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            return singleLong(ps);
        }
    }

    private static long adminUsersOperationCount(Connection connection, String operationCode) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resource_operations rro
                JOIN role_resources rr ON rro.role_resource_id = rr.id
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                JOIN operations o ON rro.operation_id = o.id
                WHERE r.code = 'ADMIN' AND res.code = 'USERS' AND o.code = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationCode);
            return singleLong(ps);
        }
    }

    private static long adminUsersOperationTotalCount(Connection connection) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resource_operations rro
                JOIN role_resources rr ON rro.role_resource_id = rr.id
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                WHERE r.code = 'ADMIN' AND res.code = 'USERS'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            return singleLong(ps);
        }
    }

    private static long singleLong(PreparedStatement ps) throws Exception {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
