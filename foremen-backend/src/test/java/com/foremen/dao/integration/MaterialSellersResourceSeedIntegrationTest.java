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
 * (Testcontainers) and verifies the {@code MATERIAL_SELLERS} ABAC resource seed and the default
 * seller rows introduced by changeset {@code 056-seed-material-sellers-resource}:
 * <ul>
 *     <li>the {@code MATERIAL_SELLERS} resource row exists;</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER/FINANCIER {@code READ} only, with CLIENT having no
 *         {@code role_resources} row (deny-by-default);</li>
 *     <li>the seeded placeholder seller codes ({@code leroy_merlin, castorama}) are present;</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code material_sellers} seed rows.</li>
 * </ul>
 *
 * <p>Mirrors {@code MaterialTypesResourceSeedIntegrationTest} (changeset 047): run the real
 * changelog against a real database (Liquibase enabled) and assert against the live catalog via raw
 * JDBC. FOR-04-17: {@code MATERIAL_SELLERS} is a plain dictionary — FINANCIER receives a READ
 * grant; CLIENT receives no grant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaterialSellersResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "MATERIAL_SELLERS";
    private static final List<String> SAMPLE_SELLER_CODES =
            List.of("leroy_merlin", "castorama");

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

    // --- the MATERIAL_SELLERS resource row is seeded ---
    @Test
    @DisplayName("Changeset 056 seeds the MATERIAL_SELLERS resource row")
    void seedsResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("MATERIAL_SELLERS resource row exists").isTrue();
    }

    // --- per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on MATERIAL_SELLERS are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER/FINANCIER READ, and none for CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on MATERIAL_SELLERS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER, FINANCIER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on MATERIAL_SELLERS")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on MATERIAL_SELLERS")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on MATERIAL_SELLERS")
                .containsExactly("READ");
        assertThat(hasRoleResource("FINANCIER"))
                .as("FINANCIER must have a role_resources row on MATERIAL_SELLERS").isTrue();
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on MATERIAL_SELLERS")
                .containsExactly("READ");

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on MATERIAL_SELLERS").isFalse();
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on MATERIAL_SELLERS").isEmpty();
    }

    // --- the seeded placeholder seller codes are present ---
    @Test
    @DisplayName("Changeset 056 seeds the placeholder seller codes")
    void seedsPlaceholderSellerCodes() throws Exception {
        for (String code : SAMPLE_SELLER_CODES) {
            assertThat(rowExists("material_sellers", code))
                    .as("material seller '%s' exists", code).isTrue();
        }
    }

    // --- re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the seeded placeholder sellers")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int placeholderSellersBefore = countRows("material_sellers");
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 056 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countRows("material_sellers"))
                .as("seeded material_sellers count unchanged after re-run")
                .isEqualTo(placeholderSellersBefore);
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

    private boolean rowExists(String table, String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private int countRows(String table) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + table;
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
