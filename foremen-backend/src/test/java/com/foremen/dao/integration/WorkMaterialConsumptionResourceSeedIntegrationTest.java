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
 * (Testcontainers) and verifies the {@code WORK_MATERIAL_CONSUMPTION} ABAC resource + grant-matrix
 * seed introduced by changeset {@code 068-seed-work-material-consumptions-resource} (FOR-04-19,
 * Requirement 6).
 *
 * <p>{@code WORK_MATERIAL_CONSUMPTION} is an operational entity mirroring
 * {@code MATERIALS_CONSTRUCTION} / {@code MATERIALS_FINISHING}:
 * <ul>
 *     <li>the {@code WORK_MATERIAL_CONSUMPTION} resource row exists (Requirement 6.1);</li>
 *     <li>ADMIN grants are exactly {@code CREATE, READ, UPDATE, DELETE} (Requirement 6.2);</li>
 *     <li>MANAGER grants are exactly {@code CREATE, READ, UPDATE} — <b>NO DELETE</b>
 *         (Requirement 6.2, 6.3);</li>
 *     <li>FOREMAN/WORKER/FINANCIER grants are {@code READ} only (Requirement 6.2);</li>
 *     <li>CLIENT has no {@code role_resources} row (deny-by-default, Requirement 6.2);</li>
 *     <li>{@code DELETE} on this resource is granted to ADMIN <b>only</b> — no other system role
 *         holds a DELETE grant (Requirement 6.3);</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources}, and
 *         {@code role_resource_operations} seed rows — each guarded by {@code MARK_RAN} +
 *         {@code sqlCheck expectedResult="0"} (Requirement 6.6).</li>
 * </ul>
 *
 * <p>Mirrors the harness of {@code MaterialsConstructionResourceSeedIntegrationTest} (Liquibase
 * enabled, Testcontainers Postgres, raw-JDBC assertions).
 *
 * <p>Validates: Requirements 6.1, 6.2, 6.3, 6.6, 10.6.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkMaterialConsumptionResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "WORK_MATERIAL_CONSUMPTION";
    private static final List<String> SYSTEM_ROLES =
            List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");

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

    // --- the WORK_MATERIAL_CONSUMPTION resource row is seeded (Requirement 6.1) ---
    @Test
    @DisplayName("Changeset 068 seeds the WORK_MATERIAL_CONSUMPTION resource row")
    void seedsResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("WORK_MATERIAL_CONSUMPTION resource row exists").isTrue();
    }

    // --- per-system-role grants are exactly as specified (Requirement 6.2, 6.3) ---
    @Test
    @DisplayName("Per-system-role grants on WORK_MATERIAL_CONSUMPTION are exactly ADMIN CRUD, "
            + "MANAGER CREATE/READ/UPDATE (no DELETE), FOREMAN/WORKER/FINANCIER READ, none for CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on WORK_MATERIAL_CONSUMPTION")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER: CREATE/READ/UPDATE but explicitly NO DELETE.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on WORK_MATERIAL_CONSUMPTION")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE")
                .doesNotContain("DELETE");

        // FOREMAN, WORKER, FINANCIER: READ only.
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on WORK_MATERIAL_CONSUMPTION")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on WORK_MATERIAL_CONSUMPTION")
                .containsExactly("READ");
        assertThat(hasRoleResource("FINANCIER"))
                .as("FINANCIER must have a role_resources row on WORK_MATERIAL_CONSUMPTION").isTrue();
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on WORK_MATERIAL_CONSUMPTION")
                .containsExactly("READ");

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on WORK_MATERIAL_CONSUMPTION").isFalse();
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on WORK_MATERIAL_CONSUMPTION").isEmpty();
    }

    // --- DELETE on WORK_MATERIAL_CONSUMPTION is ADMIN-only (Requirement 6.3) ---
    @Test
    @DisplayName("DELETE on WORK_MATERIAL_CONSUMPTION is granted to ADMIN only")
    void deleteIsAdminOnly() throws Exception {
        for (String roleCode : SYSTEM_ROLES) {
            boolean hasDelete = operationsFor(roleCode).contains("DELETE");
            if ("ADMIN".equals(roleCode)) {
                assertThat(hasDelete)
                        .as("ADMIN must hold DELETE on WORK_MATERIAL_CONSUMPTION").isTrue();
            } else {
                assertThat(hasDelete)
                        .as("%s must NOT hold DELETE on WORK_MATERIAL_CONSUMPTION", roleCode).isFalse();
            }
        }
    }

    // --- re-running the changelog does not duplicate rows (Requirement 6.6) ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource or grants")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 068 changesets are guarded by MARK_RAN + sqlCheck
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

    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : SYSTEM_ROLES) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
