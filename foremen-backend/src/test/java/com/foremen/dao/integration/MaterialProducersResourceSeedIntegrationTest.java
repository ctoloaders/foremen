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
 * (Testcontainers) and verifies the {@code MATERIAL_PRODUCERS} ABAC resource seed and the default
 * material-producer rows introduced by changeset {@code 049-seed-material-producers-resource}:
 * <ul>
 *     <li>the {@code MATERIAL_PRODUCERS} resource row exists;</li>
 *     <li>the per-system-role grants are exactly ADMIN {@code CREATE, READ, UPDATE, DELETE} and
 *         MANAGER/FOREMAN/WORKER/FINANCIER {@code READ} only, with CLIENT having no
 *         {@code role_resources} row (deny-by-default);</li>
 *     <li>the seeded material-producer codes (e.g. {@code egger, villeroy_boch}) are present;</li>
 *     <li>the bogus {@code Podkład} producer is ABSENT (no row whose name is {@code Podkład});</li>
 *     <li>re-running the changelog is idempotent for the resource, {@code role_resources},
 *         {@code role_resource_operations}, and {@code material_producers} seed rows.</li>
 * </ul>
 *
 * <p>Mirrors {@code MaterialCategoriesResourceSeedIntegrationTest} (changeset 033): run the real
 * changelog against a real database and assert against the live catalog via raw JDBC. Like
 * WORK_CATEGORIES / MEASUREMENT_UNITS / MATERIAL_CATEGORIES, FINANCIER receives a READ grant on
 * MATERIAL_PRODUCERS; CLIENT receives no grant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaterialProducersResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "MATERIAL_PRODUCERS";
    private static final List<String> SEEDED_MATERIAL_PRODUCER_CODES = List.of(
            "afirmax", "arbiton", "barlinek", "cersanit", "dre", "deante", "domino", "eclisse",
            "egger", "excellent", "geberit", "grohe", "hagser", "hansgrohe", "infinity", "marazzi",
            "metamorphose", "oltens", "omnires", "paradyz", "pol_skone", "porcelanosa", "porta",
            "radaway", "ravak", "roca", "salag", "tubadzin", "tupai", "vox", "viega",
            "villeroy_boch");

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

    // --- the MATERIAL_PRODUCERS resource row is seeded ---
    @Test
    @DisplayName("Changeset 049 seeds the MATERIAL_PRODUCERS resource row")
    void seedsMaterialProducersResource() throws Exception {
        assertThat(resourceExists(RESOURCE_CODE))
                .as("MATERIAL_PRODUCERS resource row exists").isTrue();
    }

    // --- per-system-role grants are exactly as specified ---
    @Test
    @DisplayName("Per-system-role grants on MATERIAL_PRODUCERS are exactly ADMIN CRUD, "
            + "MANAGER/FOREMAN/WORKER/FINANCIER READ, and none for CLIENT")
    void grantsPerSystemRoleAreExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on MATERIAL_PRODUCERS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER, FOREMAN, WORKER: READ only.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on MATERIAL_PRODUCERS")
                .containsExactly("READ");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on MATERIAL_PRODUCERS")
                .containsExactly("READ");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on MATERIAL_PRODUCERS")
                .containsExactly("READ");

        // FINANCIER: READ grant present (matches WORK_CATEGORIES / MEASUREMENT_UNITS).
        assertThat(hasRoleResource("FINANCIER"))
                .as("FINANCIER must have a role_resources row on MATERIAL_PRODUCERS").isTrue();
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on MATERIAL_PRODUCERS")
                .containsExactly("READ");

        // CLIENT: no role_resources row at all (deny-by-default).
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on MATERIAL_PRODUCERS").isFalse();
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on MATERIAL_PRODUCERS").isEmpty();
    }

    // --- the seeded material-producer codes are present ---
    @Test
    @DisplayName("Changeset 049 seeds the default material-producer codes")
    void seedsDefaultMaterialProducerCodes() throws Exception {
        for (String code : SEEDED_MATERIAL_PRODUCER_CODES) {
            assertThat(materialProducerExists(code))
                    .as("default material producer '%s' exists", code).isTrue();
        }
        assertThat(countSeededMaterialProducers())
                .as("all seeded default material producers are present")
                .isEqualTo(SEEDED_MATERIAL_PRODUCER_CODES.size());
    }

    // --- the bogus "Podkład" producer is absent ---
    @Test
    @DisplayName("The bogus 'Podkład' producer is NOT seeded into material_producers")
    void bogusPodkladProducerIsAbsent() throws Exception {
        assertThat(countMaterialProducersByName("Podkład"))
                .as("no material_producers row is named 'Podkład'").isZero();
    }

    // --- re-running the changelog does not duplicate rows ---
    @Test
    @DisplayName("Re-running the changelog does not duplicate the resource, grants, "
            + "or the default material producers")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        int seededMaterialProducersBefore = countSeededMaterialProducers();
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 049 changesets are guarded by MARK_RAN + sqlCheck
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
        assertThat(countSeededMaterialProducers())
                .as("seeded material_producers count unchanged after re-run")
                .isEqualTo(seededMaterialProducersBefore)
                .isEqualTo(SEEDED_MATERIAL_PRODUCER_CODES.size());
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

    /** Whether the given role has a role_resources row on the MATERIAL_PRODUCERS resource. */
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

    /** The set of operation codes granted to the given role on the MATERIAL_PRODUCERS resource. */
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

    /** Whether the material_producers table contains a row with the given code. */
    private boolean materialProducerExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM material_producers WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Count of material_producers rows whose name (ru or pl) matches the given value. */
    private int countMaterialProducersByName(String name) throws Exception {
        String sql = "SELECT COUNT(*) FROM material_producers WHERE name_ru = ? OR name_pl = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Count of material_producers rows whose code is one of the seeded defaults. */
    private int countSeededMaterialProducers() throws Exception {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < SEEDED_MATERIAL_PRODUCER_CODES.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        String sql = "SELECT COUNT(*) FROM material_producers WHERE code IN (" + placeholders + ")";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < SEEDED_MATERIAL_PRODUCER_CODES.size(); i++) {
                ps.setString(i + 1, SEEDED_MATERIAL_PRODUCER_CODES.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on MATERIAL_PRODUCERS. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
