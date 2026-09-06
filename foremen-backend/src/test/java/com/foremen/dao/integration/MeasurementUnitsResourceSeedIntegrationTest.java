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
 * (Testcontainers) and verifies the {@code MEASUREMENT_UNITS} ABAC resource seed and the default
 * unit rows introduced by changeset {@code 019-seed-measurement-units-resource}:
 * <ul>
 *     <li>the {@code MEASUREMENT_UNITS} resource row exists (Requirement 3.2);</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER/FINANCIER {@code READ} only, with CLIENT having no
 *         {@code role_resources} row (deny-by-default) (Requirement 3.3);</li>
 *     <li>the seven default unit codes {@code m2, mb, szt, kpl, godz, proc, m3} are seeded
 *         (Requirement 4.1);</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code measurement_units} seed rows
 *         (Requirements 3.4, 4.2).</li>
 * </ul>
 *
 * <p>Mirrors {@code ProjectMembersResourceSeedIntegrationTest} (changeset 015): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC.
 *
 * <p>Validates: Requirements 3.2, 3.3, 3.4, 4.1, 4.2, 5.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeasurementUnitsResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "MEASUREMENT_UNITS";
    private static final List<String> DEFAULT_UNIT_CODES =
            List.of("m2", "mb", "szt", "kpl", "godz", "proc", "m3");

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

    // --- Requirement 3.2 : the MEASUREMENT_UNITS resource row is seeded ---
    @Test
    @DisplayName("Changeset 019 seeds the MEASUREMENT_UNITS resource row")
    void seedsMeasurementUnitsResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("MEASUREMENT_UNITS resource row exists").isTrue();
    }

    // --- Requirement 3.3 : per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on MEASUREMENT_UNITS are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER/FINANCIER READ, and none for CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on MEASUREMENT_UNITS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER, FINANCIER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on MEASUREMENT_UNITS")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on MEASUREMENT_UNITS")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on MEASUREMENT_UNITS")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on MEASUREMENT_UNITS")
                .containsExactly("READ");

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on MEASUREMENT_UNITS").isFalse();
    }

    // --- Requirement 4.1 : the seven default unit codes are seeded ---
    @Test
    @DisplayName("Changeset 019 seeds the seven default measurement unit codes")
    void seedsDefaultUnitCodes() throws Exception {
        for (String code : DEFAULT_UNIT_CODES) {
            assertThat(measurementUnitExists(code))
                    .as("default measurement unit '%s' exists", code).isTrue();
        }
        assertThat(countSeededUnits())
                .as("all seven default units are present")
                .isEqualTo(DEFAULT_UNIT_CODES.size());
    }

    // --- Requirements 3.4 / 4.2 : re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default measurement units")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededUnitsBefore = countSeededUnits();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 019 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countSeededUnits())
                .as("seeded measurement_units count unchanged after re-run")
                .isEqualTo(seededUnitsBefore)
                .isEqualTo(DEFAULT_UNIT_CODES.size());
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

    /** Whether the given role has a role_resources row on the MEASUREMENT_UNITS resource. */
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

    /** The set of operation codes granted to the given role on the MEASUREMENT_UNITS resource. */
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

    /** Whether the measurement_units table contains a row with the given code. */
    private boolean measurementUnitExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM measurement_units WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Count of measurement_units rows whose code is one of the seven seeded defaults. */
    private int countSeededUnits() throws Exception {
        String sql = "SELECT COUNT(*) FROM measurement_units "
                + "WHERE code IN ('m2', 'mb', 'szt', 'kpl', 'godz', 'proc', 'm3')";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on MEASUREMENT_UNITS. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
