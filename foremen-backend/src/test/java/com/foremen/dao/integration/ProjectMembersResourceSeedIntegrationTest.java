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
 * (Testcontainers) and verifies the {@code PROJECT_MEMBERS} ABAC resource seed introduced by
 * changeset {@code 015-seed-project-members-resource}:
 * <ul>
 *     <li>the {@code PROJECT_MEMBERS} resource row exists (Requirement 11.1);</li>
 *     <li>the per-system-role grants are exactly ADMIN/MANAGER full CRUD
 *         ({@code CREATE, READ, UPDATE, DELETE}) and FOREMAN/FINANCIER {@code READ} only
 *         (Requirement 11.3);</li>
 *     <li>the WORKER and CLIENT system roles have NO {@code role_resources} row on the resource,
 *         expressing deny-by-default (Requirement 11.4, 11.7);</li>
 *     <li>re-running the changelog does not duplicate the resource, {@code role_resources}, or
 *         {@code role_resource_operations} rows, because every INSERT is guarded by a
 *         {@code MARK_RAN} + {@code sqlCheck expectedResult="0"} precondition (Requirement 11.5).</li>
 * </ul>
 *
 * <p>Mirrors {@code ProjectMemberMigrationIntegrationTest} (changeset 014) and
 * {@code AuthMigrationIntegrationTest} (010/011/012): run the real changelog against a real
 * database and assert against the live catalog, rather than relying on Hibernate DDL.
 *
 * <p>Validates: Requirements 11.1, 11.3, 11.4, 11.5, 11.7
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMembersResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "PROJECT_MEMBERS";

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

    // --- Requirement 11.1 : the PROJECT_MEMBERS resource row is seeded ---
    @Test
    @DisplayName("Changeset 015 seeds the PROJECT_MEMBERS resource row")
    void seedsProjectMembersResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("PROJECT_MEMBERS resource row exists").isTrue();
    }

    // --- Requirement 11.3 / 11.4 / 11.7 : per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on PROJECT_MEMBERS are exactly ADMIN/MANAGER CRUD, "
            + "FOREMAN/FINANCIER READ, and none for WORKER/CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN and MANAGER: full CRUD (Requirement 11.3).
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on PROJECT_MEMBERS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on PROJECT_MEMBERS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // FOREMAN and FINANCIER: READ only (Requirement 11.3).
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on PROJECT_MEMBERS")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on PROJECT_MEMBERS")
                .containsExactly("READ");

        // WORKER and CLIENT: no role_resources row at all (deny-by-default, Requirement 11.4, 11.7).
        assertThat(hasRoleResource("WORKER"))
                .as("WORKER must have no role_resources row on PROJECT_MEMBERS").isFalse();
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on PROJECT_MEMBERS").isFalse();
    }

    // --- Requirement 11.5 : re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, role_resources, "
            + "or role_resource_operations rows")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 015 changesets are guarded by MARK_RAN + sqlCheck
        // expectedResult="0" preconditions, so a second application inserts nothing new
        // (Requirement 11.5).
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

    private boolean resourceExists(String code) throws Exception {
        return countResource(code) > 0;
    }

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

    /** Whether the given role has a role_resources row on the PROJECT_MEMBERS resource. */
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

    /** The set of operation codes granted to the given role on the PROJECT_MEMBERS resource. */
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

    /** A stable snapshot of role_code -> sorted operation codes on PROJECT_MEMBERS. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "FINANCIER", "WORKER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
