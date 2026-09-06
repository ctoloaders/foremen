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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code DELIVERY_STATUSES} ABAC resource seed and the default
 * delivery-status rows introduced by changeset {@code 031-seed-delivery-statuses-resource}:
 * <ul>
 *     <li>the {@code DELIVERY_STATUSES} resource row exists;</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER {@code READ} only, with FINANCIER and CLIENT having no
 *         {@code role_resources} row (deny-by-default);</li>
 *     <li>the four default delivery-status codes {@code new, ordered, delivered, cancelled} are
 *         seeded with {@code order_no} 1..4;</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code delivery_statuses} seed rows.</li>
 * </ul>
 *
 * <p>Mirrors {@code DeliveryCategoriesResourceSeedIntegrationTest} (changeset 029): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC. The ONLY
 * grant difference vs MEASUREMENT_UNITS is that FINANCIER receives NO grant on DELIVERY_STATUSES.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryStatusesResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "DELIVERY_STATUSES";
    private static final List<String> DEFAULT_DELIVERY_STATUS_CODES =
            List.of("new", "ordered", "delivered", "cancelled");

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

    // --- the DELIVERY_STATUSES resource row is seeded ---
    @Test
    @DisplayName("Changeset 031 seeds the DELIVERY_STATUSES resource row")
    void seedsDeliveryStatusesResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("DELIVERY_STATUSES resource row exists").isTrue();
    }

    // --- per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on DELIVERY_STATUSES are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER READ, and none for FINANCIER or CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on DELIVERY_STATUSES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on DELIVERY_STATUSES")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on DELIVERY_STATUSES")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on DELIVERY_STATUSES")
                .containsExactly("READ");

        // FINANCIER: no role_resources row at all (deny-by-default) — differs from MEASUREMENT_UNITS.
        assertThat(hasRoleResource("FINANCIER"))
                .as("FINANCIER must have no role_resources row on DELIVERY_STATUSES").isFalse();
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on DELIVERY_STATUSES").isEmpty();

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on DELIVERY_STATUSES").isFalse();
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on DELIVERY_STATUSES").isEmpty();
    }

    // --- the four default delivery-status codes are seeded with order_no 1..4 ---
    @Test
    @DisplayName("Changeset 031 seeds the four default delivery-status codes with order_no 1..4")
    void seedsDefaultDeliveryStatusCodes() throws Exception {
        for (String code : DEFAULT_DELIVERY_STATUS_CODES) {
            assertThat(deliveryStatusExists(code))
                    .as("default delivery status '%s' exists", code).isTrue();
        }
        assertThat(countSeededDeliveryStatuses())
                .as("all four default delivery statuses are present")
                .isEqualTo(DEFAULT_DELIVERY_STATUS_CODES.size());

        Map<String, Integer> orderNos = seededOrderNos();
        assertThat(orderNos)
                .as("each default delivery status is seeded with its expected order_no")
                .containsEntry("new", 1)
                .containsEntry("ordered", 2)
                .containsEntry("delivered", 3)
                .containsEntry("cancelled", 4);
    }

    // --- re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default delivery statuses")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededDeliveryStatusesBefore = countSeededDeliveryStatuses();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 031 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countSeededDeliveryStatuses())
                .as("seeded delivery_statuses count unchanged after re-run")
                .isEqualTo(seededDeliveryStatusesBefore)
                .isEqualTo(DEFAULT_DELIVERY_STATUS_CODES.size());
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

    /** Whether the given role has a role_resources row on the DELIVERY_STATUSES resource. */
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

    /** The set of operation codes granted to the given role on the DELIVERY_STATUSES resource. */
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

    /** Whether the delivery_statuses table contains a row with the given code. */
    private boolean deliveryStatusExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM delivery_statuses WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Count of delivery_statuses rows whose code is one of the four seeded defaults. */
    private int countSeededDeliveryStatuses() throws Exception {
        String sql = "SELECT COUNT(*) FROM delivery_statuses "
                + "WHERE code IN ('new', 'ordered', 'delivered', 'cancelled')";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Map of seeded delivery-status code -> order_no. */
    private Map<String, Integer> seededOrderNos() throws Exception {
        String sql = "SELECT code, order_no FROM delivery_statuses "
                + "WHERE code IN ('new', 'ordered', 'delivered', 'cancelled')";
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("code"), rs.getInt("order_no"));
                }
            }
        }
        return result;
    }

    /** A stable snapshot of role_code -> sorted operation codes on DELIVERY_STATUSES. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
