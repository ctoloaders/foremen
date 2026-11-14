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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-11-00 (task 11.5): verifies changesets 228 (procurement delivery stages), 229 (procurement
 * resources and grants) and 230 (BUYER reference grants) against a real PostgreSQL instance.
 * Requirements 1.*, 12.*, 14.*, 16.3, 18.1.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcurementResourcesSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final List<String> PROCUREMENT_RESOURCES =
            List.of("PURCHASE_POSITIONS", "PURCHASE_REQUESTS", "PROJECT_MATERIALS");
    private static final List<String> ROLES =
            List.of("ADMIN", "MANAGER", "BUYER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");
    private static final List<String> BUYER_REFERENCE_RESOURCES = List.of(
            "MATERIALS_FINISHING", "MATERIALS", "MATERIAL_CATEGORIES", "MATERIAL_TYPES",
            "MATERIAL_PRODUCERS", "MEASUREMENT_UNITS", "VAT_RATES", "CURRENCIES",
            "DELIVERY_CATEGORIES", "DELIVERY_STATUSES");

    private static final List<String> CRUD = List.of("CREATE", "DELETE", "READ", "UPDATE");

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
    @DisplayName("Changeset 229 seeds the three procurement resources")
    void seedsResources() throws Exception {
        for (String code : PROCUREMENT_RESOURCES) {
            assertThat(countResource(code)).as("resource %s", code).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("PURCHASE_POSITIONS grants: ADMIN/MANAGER CRUD, BUYER CRU, FOREMAN R, CLIENT none")
    void purchasePositionsGrants() throws Exception {
        assertMatrix("PURCHASE_POSITIONS", CRUD, CRUD, List.of("CREATE", "READ", "UPDATE"));
    }

    @Test
    @DisplayName("PURCHASE_REQUESTS grants: ADMIN/MANAGER CRUD, BUYER RU, FOREMAN R, CLIENT none")
    void purchaseRequestsGrants() throws Exception {
        assertMatrix("PURCHASE_REQUESTS", CRUD, CRUD, List.of("READ", "UPDATE"));
    }

    @Test
    @DisplayName("PROJECT_MATERIALS grants: ADMIN/MANAGER/BUYER CRUD, FOREMAN R, CLIENT none")
    void projectMaterialsGrants() throws Exception {
        assertMatrix("PROJECT_MATERIALS", CRUD, CRUD, CRUD);
    }

    private void assertMatrix(String resource, List<String> admin, List<String> manager,
                              List<String> buyer) throws Exception {
        assertThat(operationsFor("ADMIN", resource)).as("ADMIN on %s", resource)
                .containsExactlyInAnyOrderElementsOf(admin);
        assertThat(operationsFor("MANAGER", resource)).as("MANAGER on %s", resource)
                .containsExactlyInAnyOrderElementsOf(manager);
        assertThat(operationsFor("BUYER", resource)).as("BUYER on %s", resource)
                .containsExactlyInAnyOrderElementsOf(buyer);
        assertThat(operationsFor("FOREMAN", resource)).as("FOREMAN on %s", resource)
                .containsExactly("READ");
        assertThat(hasRoleResource("CLIENT", resource)).as("CLIENT row on %s", resource).isFalse();
        assertThat(operationsFor("CLIENT", resource)).as("CLIENT on %s", resource).isEmpty();
    }

    @Test
    @DisplayName("BUYER role exists and is a system role")
    void buyerRoleIsSystem() throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT system FROM roles WHERE code = 'BUYER'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("BUYER role exists").isTrue();
            assertThat(rs.getBoolean(1)).as("BUYER is system").isTrue();
        }
    }

    @Test
    @DisplayName("Changeset 230: BUYER READ on reference resources, MATERIAL_SELLERS RCU, NOTIFICATIONS like FOREMAN")
    void buyerReferenceGrants() throws Exception {
        for (String resource : BUYER_REFERENCE_RESOURCES) {
            assertThat(operationsFor("BUYER", resource))
                    .as("BUYER on %s", resource).containsExactly("READ");
        }
        assertThat(operationsFor("BUYER", "MATERIAL_SELLERS"))
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE");
        List<String> foremanNotifications = operationsFor("FOREMAN", "NOTIFICATIONS");
        assertThat(foremanNotifications).isNotEmpty();
        assertThat(operationsFor("BUYER", "NOTIFICATIONS"))
                .containsExactlyInAnyOrderElementsOf(foremanNotifications);
    }

    @Test
    @DisplayName("Changeset 228: procurement stages seeded and the old four re-ordered")
    void seedsDeliveryStages() throws Exception {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("new", 1);
        expected.put("proposed", 2);
        expected.put("accepted", 3);
        expected.put("in_proforma", 4);
        expected.put("client_paid", 5);
        expected.put("with_buyer", 6);
        expected.put("ordered", 7);
        expected.put("supplier_paid", 8);
        expected.put("in_delivery", 9);
        expected.put("partially_received", 10);
        expected.put("delivered", 11);
        expected.put("cancelled", 12);
        Map<String, Integer> actual = new LinkedHashMap<>();
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT code, order_no FROM delivery_statuses WHERE code = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", expected.keySet().toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actual.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("Re-running the changelog leaves procurement seed counts unchanged")
    void reRunIsIdempotent() throws Exception {
        Map<String, Object> before = snapshot();
        runLiquibase();
        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<String, Object> snapshot() throws Exception {
        Map<String, Object> snap = new TreeMap<>();
        List<String> resources = new ArrayList<>(PROCUREMENT_RESOURCES);
        resources.addAll(BUYER_REFERENCE_RESOURCES);
        resources.addAll(List.of("MATERIAL_SELLERS", "NOTIFICATIONS"));
        for (String resource : resources) {
            snap.put("res:" + resource, countResource(resource));
            for (String role : ROLES) {
                snap.put(role + ":" + resource, operationsFor(role, resource));
            }
        }
        snap.put("delivery_statuses", count("SELECT COUNT(*) FROM delivery_statuses"));
        snap.put("roles", count("SELECT COUNT(*) FROM roles"));
        return snap;
    }

    // ---- helpers ----

    private int count(String sql) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countResource(String code) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM resources WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean hasRoleResource(String roleCode, String resourceCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE r.code = ? AND res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private List<String> operationsFor(String roleCode, String resourceCode) throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? ORDER BY o.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ops = new ArrayList<>();
                while (rs.next()) {
                    ops.add(rs.getString(1));
                }
                return ops;
            }
        }
    }
}
