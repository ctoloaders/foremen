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
 * (Testcontainers) and verifies the {@code CURRENCIES} ABAC resource seed and the default currency
 * rows introduced by changeset {@code 021-seed-currencies-resource}:
 * <ul>
 *     <li>the {@code CURRENCIES} resource row exists (Requirement 2.2);</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER/FINANCIER {@code READ} only, with CLIENT having no
 *         {@code role_resources} row (deny-by-default) (Requirement 2.3);</li>
 *     <li>the three default currency codes {@code PLN, EUR, USD} are seeded (Requirement 3.1);</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code currencies} seed rows
 *         (Requirements 2.4, 3.2).</li>
 * </ul>
 *
 * <p>Mirrors {@code MeasurementUnitsResourceSeedIntegrationTest} (changeset 019): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC.
 *
 * <p>Validates: Requirements 2.2, 2.3, 2.4, 3.1, 3.2, 5.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CurrenciesResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "CURRENCIES";
    private static final List<String> DEFAULT_CURRENCY_CODES =
            List.of("PLN", "EUR", "USD");

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

    // --- Requirement 2.2 : the CURRENCIES resource row is seeded ---
    @Test
    @DisplayName("Changeset 021 seeds the CURRENCIES resource row")
    void seedsCurrenciesResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("CURRENCIES resource row exists").isTrue();
    }

    // --- Requirement 2.3 : per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on CURRENCIES are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER/FINANCIER READ, and none for CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on CURRENCIES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER, FINANCIER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on CURRENCIES")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on CURRENCIES")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on CURRENCIES")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on CURRENCIES")
                .containsExactly("READ");

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on CURRENCIES").isFalse();
    }

    // --- Requirement 3.1 : the three default currency codes are seeded ---
    @Test
    @DisplayName("Changeset 021 seeds the three default currency codes PLN/EUR/USD")
    void seedsDefaultCurrencyCodes() throws Exception {
        for (String code : DEFAULT_CURRENCY_CODES) {
            assertThat(currencyExists(code))
                    .as("default currency '%s' exists", code).isTrue();
        }
        assertThat(countSeededCurrencies())
                .as("all three default currencies are present")
                .isEqualTo(DEFAULT_CURRENCY_CODES.size());
    }

    // --- Requirements 2.4 / 3.2 : re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default currencies")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededCurrenciesBefore = countSeededCurrencies();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 021 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countSeededCurrencies())
                .as("seeded currencies count unchanged after re-run")
                .isEqualTo(seededCurrenciesBefore)
                .isEqualTo(DEFAULT_CURRENCY_CODES.size());
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

    /** Whether the given role has a role_resources row on the CURRENCIES resource. */
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

    /** The set of operation codes granted to the given role on the CURRENCIES resource. */
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

    /** Whether the currencies table contains a row with the given code. */
    private boolean currencyExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM currencies WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Count of currencies rows whose code is one of the three seeded defaults. */
    private int countSeededCurrencies() throws Exception {
        String sql = "SELECT COUNT(*) FROM currencies "
                + "WHERE code IN ('PLN', 'EUR', 'USD')";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on CURRENCIES. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
