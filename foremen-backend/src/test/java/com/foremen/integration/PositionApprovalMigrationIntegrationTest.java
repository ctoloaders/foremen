package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-11-02 task 8.1 - changesets {@code 234} (tables, partial unique index) and {@code 235} (resource and
 * grants) applied through the real Liquibase changelog against a Testcontainers PostgreSQL, then applied a
 * second time to prove idempotency. Harness mirrors {@link ClientMessagesSeedMigrationIntegrationTest}.
 *
 * <p>Repeatability: a fresh container per JVM run.
 *
 * <p>Validates Requirements 1.1, 1.2, 1.3, 1.4, 2.1, 2.5, 2.8.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-11-02-position-approval, task 8.1: changesets 234/235 apply and are idempotent")
class PositionApprovalMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE = "POSITION_APPROVALS";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try {
            runChangelog();
        } catch (Exception first) {
            // Changeset 160 needs an ADMIN user on an empty DB: seed one and resume.
            seedAdminUserIfPossible();
            runChangelog();
        }
    }

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

    private static void seedAdminUserIfPossible() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement()) {
            long adminRoleId;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)")) {
                rs.next();
                adminRoleId = rs.getLong(1);
            }
            if (adminRoleId == 0) {
                return;
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
            stmt.executeUpdate(
                    "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                            + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId
                            + ", true, 'pl', NOW(), 'test')");
        }
    }

    @Test
    @DisplayName("234 creates both tables, the partial unique index and the plain indexes")
    void tablesAndIndexes() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN "
                + "('position_approval_rounds','position_approval_options')")).isEqualTo(2L);
        assertThat(queryForString("SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_par_open_per_position'"))
                .contains("UNIQUE").contains("OPEN").contains("CLIENT_SUGGESTION");
        assertThat(queryForLong("SELECT COUNT(*) FROM pg_indexes WHERE indexname IN "
                + "('ix_par_position','ix_par_status','ix_pao_round','uq_par_position_round')")).isEqualTo(4L);
        assertThat(queryForLong("SELECT COUNT(*) FROM information_schema.columns WHERE "
                + "table_name = 'position_approval_options' AND column_name = 'internal_unit_purchase_price'"))
                .isEqualTo(1L);
        assertThat(queryForLong("SELECT COUNT(*) FROM information_schema.table_constraints WHERE "
                + "constraint_name = 'fk_par_chosen_option'")).isEqualTo(1L);
    }

    @Test
    @DisplayName("235 seeds POSITION_APPROVALS once with the ABAC matrix of the design")
    void resourceAndMatrix() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = '" + RESOURCE + "'")).isEqualTo(1L);
        assertThat(operationsFor("ADMIN", RESOURCE))
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE", "APPROVE");
        assertThat(operationsFor("MANAGER", RESOURCE))
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE", "APPROVE");
        assertThat(operationsFor("BUYER", RESOURCE)).containsExactly("READ");
        assertThat(operationsFor("CLIENT", RESOURCE)).containsExactlyInAnyOrder("READ", "APPROVE");
        assertThat(operationsFor("WORKER", RESOURCE)).isEmpty();
    }

    @Test
    @DisplayName("re-running the changelog changes nothing and errors nowhere")
    void reRunIsNoOp() throws Exception {
        String grants = "SELECT COUNT(*) FROM role_resource_operations rro JOIN role_resources rr "
                + "ON rro.role_resource_id = rr.id JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code = '" + RESOURCE + "'";
        String indexes = "SELECT COUNT(*) FROM pg_indexes WHERE tablename LIKE 'position_approval_%'";
        long grantsBefore = queryForLong(grants);
        long indexesBefore = queryForLong(indexes);
        runChangelog();
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = '" + RESOURCE + "'")).isEqualTo(1L);
        assertThat(queryForLong(grants)).isEqualTo(grantsBefore);
        assertThat(queryForLong(indexes)).isEqualTo(indexesBefore);
    }

    private List<String> operationsFor(String roleCode, String resourceCode) throws Exception {
        List<String> ops = new ArrayList<>();
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = '" + roleCode + "' AND res.code = '" + resourceCode + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ops.add(rs.getString(1));
            }
        }
        return ops;
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

    private String queryForString(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
