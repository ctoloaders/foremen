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
 * FOR-11-03 task 1.4 - changeset {@code 239} (resources and grants) applied through the real Liquibase
 * changelog against a Testcontainers PostgreSQL, then applied a second time to prove idempotency.
 *
 * <p>Repeatability: a fresh container per JVM run.
 *
 * <p>FOR-11-06 task 2.8: the CLIENT_PROFORMAS grants seeded by 239 are retired by changeset 248 and merged into
 * {@code CLIENT_PAYMENTS}, so the matrix assertions describe the end state of the full changelog.
 *
 * <p>Validates Requirements 1.1, 1.2, 1.3, 1.4, 1.5 (FOR-11-03); 1.4, 1.7, 1.10, 14.2 (FOR-11-06).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-11-03-payments-proformas-external-orders, task 1.4: changeset 239 applies and is idempotent")
class ProformaExternalOrderResourcesMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

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
    @DisplayName("239 seeds both resources once with RU/PL texts")
    void resourcesSeeded() throws Exception {
        for (String code : List.of("CLIENT_PROFORMAS", "EXTERNAL_ORDERS", "CLIENT_PAYMENTS")) {
            assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = '" + code
                    + "' AND name_ru IS NOT NULL AND name_pl IS NOT NULL AND description_ru IS NOT NULL "
                    + "AND description_pl IS NOT NULL")).as(code).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("239 grants follow Requirement 1.2")
    void matrix() throws Exception {
        List<String> crud = List.of("CREATE", "READ", "UPDATE", "DELETE");
        // FOR-11-06 (R1.4, R1.10, R14.2): 248 retires every CLIENT_PROFORMAS role grant (the resource row stays
        // for history) and merges them into CLIENT_PAYMENTS.
        assertThat(queryForLong("SELECT COUNT(*) FROM role_resources rr JOIN resources res "
                + "ON rr.resource_id = res.id WHERE res.code = 'CLIENT_PROFORMAS'")).isZero();
        assertThat(operationsFor("ADMIN", "CLIENT_PAYMENTS")).containsExactlyInAnyOrderElementsOf(crud);
        assertThat(operationsFor("MANAGER", "CLIENT_PAYMENTS")).containsExactlyInAnyOrderElementsOf(crud);
        assertThat(operationsFor("FINANCIER", "CLIENT_PAYMENTS")).containsExactlyInAnyOrderElementsOf(crud);
        assertThat(operationsFor("BUYER", "CLIENT_PAYMENTS")).containsExactly("READ");
        assertThat(operationsFor("FOREMAN", "CLIENT_PAYMENTS")).containsExactly("READ");
        assertThat(operationsFor("CLIENT", "CLIENT_PAYMENTS")).containsExactly("READ");
        for (String role : List.of("WORKER", "ESTIMATOR")) {
            assertThat(operationsFor(role, "CLIENT_PAYMENTS")).as(role).isEmpty();
        }
        assertThat(operationsFor("ADMIN", "EXTERNAL_ORDERS"))
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE", "APPROVE");
        assertThat(operationsFor("BUYER", "EXTERNAL_ORDERS")).containsExactlyInAnyOrderElementsOf(crud);
        assertThat(operationsFor("MANAGER", "EXTERNAL_ORDERS")).containsExactly("READ");
        assertThat(operationsFor("FINANCIER", "EXTERNAL_ORDERS")).containsExactlyInAnyOrder("READ", "APPROVE");
        for (String role : List.of("FOREMAN", "WORKER", "ESTIMATOR")) {
            assertThat(operationsFor(role, "EXTERNAL_ORDERS")).as(role).isEmpty();
        }
        assertThat(operationsFor("CLIENT", "EXTERNAL_ORDERS")).isEmpty();
    }

    @Test
    @DisplayName("239 gives FINANCIER notifications and reference READ only, no procurement grants")
    void financierGrants() throws Exception {
        assertThat(operationsFor("FINANCIER", "NOTIFICATIONS"))
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(operationsFor("BUYER", "NOTIFICATIONS"));
        for (String code : List.of("MATERIAL_SELLERS", "VAT_RATES", "MEASUREMENT_UNITS")) {
            assertThat(operationsFor("FINANCIER", code)).as(code).contains("READ");
        }
        for (String code : List.of("PURCHASE_POSITIONS", "PURCHASE_REQUESTS", "PROJECT_MATERIALS")) {
            assertThat(operationsFor("FINANCIER", code)).as(code).isEmpty();
        }
    }

    @Test
    @DisplayName("re-running the changelog changes nothing")
    void reRunIsNoOp() throws Exception {
        String grants = "SELECT COUNT(*) FROM role_resource_operations rro JOIN role_resources rr "
                + "ON rro.role_resource_id = rr.id JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code IN ('CLIENT_PROFORMAS','EXTERNAL_ORDERS')";
        String financier = "SELECT COUNT(*) FROM role_resource_operations rro JOIN role_resources rr "
                + "ON rro.role_resource_id = rr.id JOIN roles r ON rr.role_id = r.id WHERE r.code = 'FINANCIER'";
        long grantsBefore = queryForLong(grants);
        long financierBefore = queryForLong(financier);
        runChangelog();
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code IN "
                + "('CLIENT_PROFORMAS','EXTERNAL_ORDERS')")).isEqualTo(2L);
        assertThat(queryForLong(grants)).isEqualTo(grantsBefore);
        assertThat(queryForLong(financier)).isEqualTo(financierBefore);
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
}
