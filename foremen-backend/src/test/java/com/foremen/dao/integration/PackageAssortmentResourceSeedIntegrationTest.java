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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code PACKAGE_ASSORTMENT} ABAC resource seed introduced by
 * changeset {@code 089-seed-package-assortment-resource} (FOR-05-04):
 * <ul>
 *     <li>the {@code PACKAGE_ASSORTMENT} resource row exists after migrate (Requirement 6.1);</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE};
 *         MANAGER/FOREMAN/FINANCIER {@code READ}; WORKER/CLIENT no {@code role_resources} row
 *         (deny-by-default) (Requirement 6.1);</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources}, and
 *         {@code role_resource_operations} rows — every 089 changeset is guarded by
 *         {@code MARK_RAN} + {@code sqlCheck expectedResult="0"}, so a second application
 *         inserts nothing (row/grant counts unchanged) (Requirement 6.1).</li>
 * </ul>
 *
 * <p>Mirrors {@code EstimateResourceSeedIntegrationTest} (changeset 081): run the real changelog
 * against a real database via raw Liquibase and assert against the live catalog through raw JDBC
 * on the {@code resources} / {@code role_resources} / {@code role_resource_operations} /
 * {@code roles} / {@code operations} tables.
 *
 * <p>Validates: Requirements 6.1
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageAssortmentResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "PACKAGE_ASSORTMENT";

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

    // --- Requirement 6.1 : the PACKAGE_ASSORTMENT resource row is seeded exactly once ---
    @Test
    @DisplayName("Changeset 089 seeds exactly one PACKAGE_ASSORTMENT resource row")
    void seedsPackageAssortmentResource() throws Exception {
        assertThat(countResource(RESOURCE_CODE))
                .as("PACKAGE_ASSORTMENT resource row count").isEqualTo(1);
    }

    // --- Requirement 6.1 : per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on PACKAGE_ASSORTMENT are ADMIN CRUD, "
            + "MANAGER/FOREMAN/FINANCIER READ, and none for WORKER/CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on PACKAGE_ASSORTMENT")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, FINANCIER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on PACKAGE_ASSORTMENT")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on PACKAGE_ASSORTMENT")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on PACKAGE_ASSORTMENT")
                .containsExactly("READ");

        // WORKER, CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("WORKER"))
                .as("WORKER must have no role_resources row on PACKAGE_ASSORTMENT").isFalse();
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on PACKAGE_ASSORTMENT").isFalse();
    }

    // --- Requirement 6.1 : re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the PACKAGE_ASSORTMENT resource or grants")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 089 changesets are guarded by MARK_RAN + sqlCheck
        // expectedResult="0" preconditions, so a second application inserts nothing new.
        runLiquibase();

        assertThat(countResource(RESOURCE_CODE))
                .as("resource row count unchanged after re-run")
                .isEqualTo(resourceCountBefore)
                .isEqualTo(1);
        assertThat(countRoleResources(RESOURCE_CODE))
                .as("role_resources row count unchanged after re-run")
                .isEqualTo(roleResourcesBefore);
        assertThat(countRoleResourceOperations(RESOURCE_CODE))
                .as("role_resource_operations row count unchanged after re-run")
                .isEqualTo(roleResourceOperationsBefore);
        assertThat(snapshotGrants())
                .as("per-role grant set unchanged after re-run")
                .isEqualTo(grantsBefore);
    }

    // ---------------------------------------------------------------------
    // catalog query helpers
    // ---------------------------------------------------------------------

    private int countResource(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM resources WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Whether the given role has a role_resources row on the PACKAGE_ASSORTMENT resource. */
    private boolean hasRoleResource(String roleCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE r.code = ? AND res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, RESOURCE_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** The set of operation codes granted to the given role on the PACKAGE_ASSORTMENT resource. */
    private List<String> operationsFor(String roleCode) throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? "
                + "ORDER BY o.code";
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

    private int countRoleResources(String resourceCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resources rr "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countRoleResourceOperations(String resourceCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on PACKAGE_ASSORTMENT. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
