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
 * (Testcontainers) and verifies the {@code WORK_CATEGORIES} ABAC resource seed and the default
 * category rows introduced by changeset {@code 027-seed-work-categories-resource}:
 * <ul>
 *     <li>the {@code WORK_CATEGORIES} resource row exists (Requirement 3.2);</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER/FINANCIER {@code READ} only, with CLIENT having no
 *         {@code role_resources} row (deny-by-default) (Requirement 3.3);</li>
 *     <li>the thirteen default category codes are seeded with orderNo 1..13 (Requirement 4.1);</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code work_categories} seed rows
 *         (Requirements 3.4, 4.2).</li>
 * </ul>
 *
 * <p>Mirrors {@code MeasurementUnitsResourceSeedIntegrationTest} (changeset 019): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC.
 *
 * <p>Validates: Requirements 3.2, 3.3, 3.4, 4.1, 4.2, 5.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkCategoriesResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "WORK_CATEGORIES";
    private static final List<String> DEFAULT_CATEGORY_CODES = List.of(
            "PRELIMINARY", "CONSTRUCTIONS_GK", "PLUMBING_ROUGH", "PLUMBING_FINISH",
            "ELECTRICAL_ROUGH", "ELECTRICAL_FINISH", "TILING", "PLASTERING",
            "PAINTING_DECOR", "FLOORS", "CARPENTRY", "EXTRAS", "OTHER");

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

    // --- Requirement 3.2 : the WORK_CATEGORIES resource row is seeded ---
    @Test
    @DisplayName("Changeset 027 seeds the WORK_CATEGORIES resource row")
    void seedsWorkCategoriesResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("WORK_CATEGORIES resource row exists").isTrue();
    }

    // --- Requirement 3.3 : per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on WORK_CATEGORIES are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER/FINANCIER READ, and none for CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on WORK_CATEGORIES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER, FINANCIER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on WORK_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on WORK_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on WORK_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on WORK_CATEGORIES")
                .containsExactly("READ");

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on WORK_CATEGORIES").isFalse();
    }

    // --- Requirement 4.1 : the thirteen default category codes are seeded with orderNo 1..13 ---
    @Test
    @DisplayName("Changeset 027 seeds the thirteen default work category codes")
    void seedsDefaultCategoryCodes() throws Exception {
        for (String code : DEFAULT_CATEGORY_CODES) {
            assertThat(workCategoryExists(code))
                    .as("default work category '%s' exists", code).isTrue();
        }
        assertThat(countSeededCategories())
                .as("all thirteen default categories are present")
                .isEqualTo(DEFAULT_CATEGORY_CODES.size());
    }

    @Test
    @DisplayName("Default work categories carry the expected orderNo values (PRELIMINARY=1, OTHER=13)")
    void seedsDefaultCategoryOrderNos() throws Exception {
        assertThat(orderNoFor("PRELIMINARY")).as("PRELIMINARY orderNo").isEqualTo(1);
        assertThat(orderNoFor("OTHER")).as("OTHER orderNo").isEqualTo(13);
    }

    // --- Requirements 3.4 / 4.2 : re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default work categories")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededCategoriesBefore = countSeededCategories();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 027 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countSeededCategories())
                .as("seeded work_categories count unchanged after re-run")
                .isEqualTo(seededCategoriesBefore)
                .isEqualTo(DEFAULT_CATEGORY_CODES.size());
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

    /** Whether the given role has a role_resources row on the WORK_CATEGORIES resource. */
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

    /** The set of operation codes granted to the given role on the WORK_CATEGORIES resource. */
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

    /** Whether the work_categories table contains a row with the given code. */
    private boolean workCategoryExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM work_categories WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** The order_no of the work_categories row with the given code. */
    private int orderNoFor(String code) throws Exception {
        String sql = "SELECT order_no FROM work_categories WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Count of work_categories rows whose code is one of the thirteen seeded defaults. */
    private int countSeededCategories() throws Exception {
        String sql = "SELECT COUNT(*) FROM work_categories "
                + "WHERE code IN ('PRELIMINARY', 'CONSTRUCTIONS_GK', 'PLUMBING_ROUGH', "
                + "'PLUMBING_FINISH', 'ELECTRICAL_ROUGH', 'ELECTRICAL_FINISH', 'TILING', "
                + "'PLASTERING', 'PAINTING_DECOR', 'FLOORS', 'CARPENTRY', 'EXTRAS', 'OTHER')";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on WORK_CATEGORIES. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
