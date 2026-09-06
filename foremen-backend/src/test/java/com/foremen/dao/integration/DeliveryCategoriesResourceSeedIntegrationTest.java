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
 * (Testcontainers) and verifies the {@code DELIVERY_CATEGORIES} ABAC resource seed and the default
 * delivery-category rows introduced by changeset {@code 029-seed-delivery-categories-resource}:
 * <ul>
 *     <li>the {@code DELIVERY_CATEGORIES} resource row exists;</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER {@code READ} only, with FINANCIER and CLIENT having no
 *         {@code role_resources} row (deny-by-default);</li>
 *     <li>the nine default delivery-category codes {@code bathroom_equipment, accessories, decor,
 *         tiles, paints, lighting, flooring, doors, windows} are seeded;</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code delivery_categories} seed rows.</li>
 * </ul>
 *
 * <p>Mirrors {@code RoomTypesResourceSeedIntegrationTest} (changeset 025): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC. The ONLY
 * grant difference vs MEASUREMENT_UNITS is that FINANCIER receives NO grant on DELIVERY_CATEGORIES.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryCategoriesResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "DELIVERY_CATEGORIES";
    private static final List<String> DEFAULT_DELIVERY_CATEGORY_CODES =
            List.of("bathroom_equipment", "accessories", "decor", "tiles", "paints",
                    "lighting", "flooring", "doors", "windows");

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

    // --- the DELIVERY_CATEGORIES resource row is seeded ---
    @Test
    @DisplayName("Changeset 029 seeds the DELIVERY_CATEGORIES resource row")
    void seedsDeliveryCategoriesResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("DELIVERY_CATEGORIES resource row exists").isTrue();
    }

    // --- per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on DELIVERY_CATEGORIES are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER READ, and none for FINANCIER or CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on DELIVERY_CATEGORIES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on DELIVERY_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on DELIVERY_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on DELIVERY_CATEGORIES")
                .containsExactly("READ");

        // FINANCIER: no role_resources row at all (deny-by-default) — differs from MEASUREMENT_UNITS.
        assertThat(hasRoleResource("FINANCIER"))
                .as("FINANCIER must have no role_resources row on DELIVERY_CATEGORIES").isFalse();
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on DELIVERY_CATEGORIES").isEmpty();

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on DELIVERY_CATEGORIES").isFalse();
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on DELIVERY_CATEGORIES").isEmpty();
    }

    // --- the nine default delivery-category codes are seeded ---
    @Test
    @DisplayName("Changeset 029 seeds the nine default delivery-category codes")
    void seedsDefaultDeliveryCategoryCodes() throws Exception {
        for (String code : DEFAULT_DELIVERY_CATEGORY_CODES) {
            assertThat(deliveryCategoryExists(code))
                    .as("default delivery category '%s' exists", code).isTrue();
        }
        assertThat(countSeededDeliveryCategories())
                .as("all nine default delivery categories are present")
                .isEqualTo(DEFAULT_DELIVERY_CATEGORY_CODES.size());
    }

    // --- re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default delivery categories")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededDeliveryCategoriesBefore = countSeededDeliveryCategories();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 029 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countSeededDeliveryCategories())
                .as("seeded delivery_categories count unchanged after re-run")
                .isEqualTo(seededDeliveryCategoriesBefore)
                .isEqualTo(DEFAULT_DELIVERY_CATEGORY_CODES.size());
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

    /** Whether the given role has a role_resources row on the DELIVERY_CATEGORIES resource. */
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

    /** The set of operation codes granted to the given role on the DELIVERY_CATEGORIES resource. */
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

    /** Whether the delivery_categories table contains a row with the given code. */
    private boolean deliveryCategoryExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM delivery_categories WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Count of delivery_categories rows whose code is one of the nine seeded defaults. */
    private int countSeededDeliveryCategories() throws Exception {
        String sql = "SELECT COUNT(*) FROM delivery_categories "
                + "WHERE code IN ('bathroom_equipment', 'accessories', 'decor', 'tiles', 'paints', "
                + "'lighting', 'flooring', 'doors', 'windows')";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on DELIVERY_CATEGORIES. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
