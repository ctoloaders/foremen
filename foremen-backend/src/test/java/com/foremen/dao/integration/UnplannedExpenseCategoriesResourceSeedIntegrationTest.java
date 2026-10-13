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
 * (Testcontainers) and verifies the {@code UNPLANNED_EXPENSE_CATEGORIES} ABAC resource seed and the
 * default category rows introduced by changeset {@code 158-seed-unplanned-expense-categories-resource}
 * (table created by {@code 157-create-unplanned-expense-categories}):
 * <ul>
 *     <li>the {@code UNPLANNED_EXPENSE_CATEGORIES} resource row exists;</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/FINANCIER {@code READ} only, with WORKER and CLIENT having no
 *         {@code role_resources} row (deny-by-default) — the ABAC matrix of design property P4;</li>
 *     <li>the five default category codes {@code reclamation, unforeseen, materials, fuel, other}
 *         are seeded;</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code unplanned_expense_categories} seed rows —
 *         design property P5.</li>
 * </ul>
 *
 * <p>Mirrors {@code DeliveryStatusesResourceSeedIntegrationTest} (changeset 031): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC. The grant
 * difference vs DELIVERY_STATUSES is that here FINANCIER receives READ while WORKER receives NO
 * grant (deny-by-default), matching the FOR-06-00 admin-staff matrix.
 *
 * <p>Validates: Requirements 3.4, 3.5, 3.6, 2.3 (design properties P4 and P5).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnplannedExpenseCategoriesResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "UNPLANNED_EXPENSE_CATEGORIES";
    private static final List<String> DEFAULT_CATEGORY_CODES =
            List.of("reclamation", "unforeseen", "materials", "fuel", "other");

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

    // --- the UNPLANNED_EXPENSE_CATEGORIES resource row is seeded ---
    @Test
    @DisplayName("Changeset 158 seeds the UNPLANNED_EXPENSE_CATEGORIES resource row")
    void seedsUnplannedExpenseCategoriesResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("UNPLANNED_EXPENSE_CATEGORIES resource row exists").isTrue();
    }

    // --- per-system-role grants are exactly the FOR-06-00 ABAC matrix (P4) ---
    @Test
    @DisplayName("Per-system-role grants on UNPLANNED_EXPENSE_CATEGORIES are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/FINANCIER READ, and none for WORKER or CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on UNPLANNED_EXPENSE_CATEGORIES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, FINANCIER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on UNPLANNED_EXPENSE_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on UNPLANNED_EXPENSE_CATEGORIES")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on UNPLANNED_EXPENSE_CATEGORIES")
                .containsExactly("READ");

        // WORKER: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("WORKER"))
                .as("WORKER must have no role_resources row on UNPLANNED_EXPENSE_CATEGORIES").isFalse();
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on UNPLANNED_EXPENSE_CATEGORIES").isEmpty();

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on UNPLANNED_EXPENSE_CATEGORIES").isFalse();
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on UNPLANNED_EXPENSE_CATEGORIES").isEmpty();
    }

    // --- the five default category codes are seeded ---
    @Test
    @DisplayName("Changeset 158 seeds the five default unplanned-expense category codes")
    void seedsDefaultCategoryCodes() throws Exception {
        for (String code : DEFAULT_CATEGORY_CODES) {
            assertThat(categoryExists(code))
                    .as("default unplanned-expense category '%s' exists", code).isTrue();
        }
        assertThat(countSeededCategories())
                .as("all five default unplanned-expense categories are present")
                .isEqualTo(DEFAULT_CATEGORY_CODES.size());
    }

    // --- re-running the changelog does not duplicate rows (P5) ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default categories")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededCategoriesBefore = countSeededCategories();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 158 changesets are guarded by MARK_RAN + sqlCheck
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
                .as("seeded unplanned_expense_categories count unchanged after re-run")
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

    /** Whether the given role has a role_resources row on the UNPLANNED_EXPENSE_CATEGORIES resource. */
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

    /** The set of operation codes granted to the given role on the UNPLANNED_EXPENSE_CATEGORIES resource. */
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

    /** Whether the unplanned_expense_categories table contains a row with the given code. */
    private boolean categoryExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM unplanned_expense_categories WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Count of unplanned_expense_categories rows whose code is one of the five seeded defaults. */
    private int countSeededCategories() throws Exception {
        String sql = "SELECT COUNT(*) FROM unplanned_expense_categories "
                + "WHERE code IN ('reclamation', 'unforeseen', 'materials', 'fuel', 'other')";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on UNPLANNED_EXPENSE_CATEGORIES. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
