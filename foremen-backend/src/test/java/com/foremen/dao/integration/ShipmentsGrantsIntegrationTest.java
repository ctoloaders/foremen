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
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-11-04 task 5.4: applies the full Liquibase changelog against PostgreSQL (Testcontainers) and
 * verifies the {@code SHIPMENTS} grants seeded by changeset 242 (Requirement 1.2):
 * ADMIN CRUD, BUYER CRUD, MANAGER READ+UPDATE, FOREMAN READ, FINANCIER READ, CLIENT/WORKER none.
 * Also verifies that re-executing the 242 changesets (their databasechangelog rows removed) inserts
 * no duplicates.
 *
 * <p>Mirrors {@code DeliveryStatusesResourceSeedIntegrationTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShipmentsGrantsIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "SHIPMENTS";
    private static final List<String> ROLES =
            List.of("ADMIN", "BUYER", "MANAGER", "FOREMAN", "FINANCIER", "CLIENT", "WORKER");

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

    /** Changeset 160 needs an ADMIN user the changelog never seeds: seed one and resume. */
    private void runLiquibase() throws Exception {
        try {
            runChangelogOnce();
        } catch (Exception first) {
            try (Connection connection = newConnection();
                 var stmt = connection.createStatement()) {
                stmt.executeUpdate(
                        "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                                + "SELECT 'mig-admin', 'mig-admin@example.com', id, true, 'ru', NOW(), 'test' "
                                + "FROM roles WHERE code = 'ADMIN'");
            }
            runChangelogOnce();
        }
    }

    private void runChangelogOnce() throws Exception {
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
    @DisplayName("Changeset 242 seeds exactly one SHIPMENTS resource row")
    void seedsShipmentsResource() throws Exception {
        assertThat(countResource()).isEqualTo(1);
    }

    @Test
    @DisplayName("SHIPMENTS grants: ADMIN/BUYER CRUD, MANAGER READ+UPDATE, FOREMAN/FINANCIER READ, "
            + "CLIENT/WORKER none")
    void grantsPerRoleAreExact() throws Exception {
        assertThat(operationsFor("ADMIN")).containsExactly("CREATE", "DELETE", "READ", "UPDATE");
        assertThat(operationsFor("BUYER")).containsExactly("CREATE", "DELETE", "READ", "UPDATE");
        assertThat(operationsFor("MANAGER")).containsExactly("READ", "UPDATE");
        assertThat(operationsFor("FOREMAN")).containsExactly("READ");
        assertThat(operationsFor("FINANCIER")).containsExactly("READ");

        for (String roleCode : List.of("CLIENT", "WORKER")) {
            assertThat(hasRoleResource(roleCode))
                    .as("%s must have no role_resources row on SHIPMENTS", roleCode).isFalse();
            assertThat(operationsFor(roleCode)).isEmpty();
        }
    }

    @Test
    @DisplayName("Re-running changeset 242 does not duplicate resource or grants")
    void reRunningChangeset242IsIdempotent() throws Exception {
        int resourcesBefore = countResource();
        int roleResourcesBefore = countRoleResources();
        int operationsBefore = countRoleResourceOperations();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Force Liquibase to re-evaluate the 242 changesets; preconditions must MARK_RAN.
        try (Connection c = newConnection(); var stmt = c.createStatement()) {
            int removed = stmt.executeUpdate("DELETE FROM databasechangelog WHERE id LIKE '242-%'");
            assertThat(removed).as("242 changesets were recorded in databasechangelog").isEqualTo(2);
        }
        runLiquibase();

        assertThat(countResource()).isEqualTo(resourcesBefore).isEqualTo(1);
        assertThat(countRoleResources()).isEqualTo(roleResourcesBefore);
        assertThat(countRoleResourceOperations()).isEqualTo(operationsBefore);
        assertThat(snapshotGrants()).isEqualTo(grantsBefore);
    }

    private int scalar(String sql, String... params) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countResource() throws Exception {
        return scalar("SELECT COUNT(*) FROM resources WHERE code = ?", RESOURCE_CODE);
    }

    private boolean hasRoleResource(String roleCode) throws Exception {
        return scalar("SELECT COUNT(*) FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE r.code = ? AND res.code = ?", roleCode, RESOURCE_CODE) > 0;
    }

    private int countRoleResources() throws Exception {
        return scalar("SELECT COUNT(*) FROM role_resources rr "
                + "JOIN resources res ON rr.resource_id = res.id WHERE res.code = ?", RESOURCE_CODE);
    }

    private int countRoleResourceOperations() throws Exception {
        return scalar("SELECT COUNT(*) FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN resources res ON rr.resource_id = res.id WHERE res.code = ?", RESOURCE_CODE);
    }

    private List<String> operationsFor(String roleCode) throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? ORDER BY o.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, RESOURCE_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ops = new ArrayList<>();
                while (rs.next()) {
                    ops.add(rs.getString(1));
                }
                return ops;
            }
        }
    }

    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new TreeMap<>();
        for (String roleCode : ROLES) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
