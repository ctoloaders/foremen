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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-06-03 task 9.2 — migration-run smoke confirming the procurement changesets
 * {@code 167-create-procurement-documents}, {@code 168-create-procurement-lines}, and
 * {@code 169-seed-purchases-resource} apply cleanly when the full Liquibase changelog runs against a
 * real PostgreSQL (Testcontainers), and that re-running the changelog is idempotent (R11.3).
 *
 * <p>It is the companion of the sibling {@code *ResourceSeedIntegrationTest}s (e.g.
 * {@code DeliveryStatusesResourceSeedIntegrationTest}): it runs the <em>real</em> changelog against a
 * real database (Liquibase enabled) and asserts against the live schema + catalog via raw JDBC,
 * rather than the {@code integration-test} Hibernate {@code create-drop} profile which disables
 * Liquibase. This is the only FOR-06-03 check that the migrations themselves apply end-to-end.
 *
 * <p>Verified here:
 * <ul>
 *   <li>167/168 create the {@code procurement_documents} and {@code procurement_lines} tables;</li>
 *   <li>the FK + cascade wiring is present ({@code procurement_documents.project_id} →
 *       {@code projects} ON DELETE CASCADE; {@code procurement_lines.document_id} →
 *       {@code procurement_documents} ON DELETE CASCADE, R1.4) and the
 *       {@code (document_id, line_no)} uniqueness (R1.2);</li>
 *   <li>169 seeds the {@code PURCHASES} resource row and the exact per-role grant matrix
 *       (ADMIN/FINANCIER/MANAGER/FOREMAN → CRUD, ESTIMATOR → READ, WORKER/CLIENT → none);</li>
 *   <li>re-applying the whole changelog inserts no duplicate table / resource / grant rows
 *       (the 167–169 changesets are guarded by {@code NOT tableExists} / {@code COUNT(*)=0} +
 *       {@code onFail="MARK_RAN"} preconditions), R11.3.</li>
 * </ul>
 *
 * <p>The OCR provider is NOT involved in a migration run, so no external service is contacted.
 *
 * <p><b>Validates: Requirements 11.3</b> (and the schema side of 1.2, 1.3, 1.4, 7.2, 7.4, 7.5).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcurementMigrationSmokeIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "PURCHASES";

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startContainerAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        // A bare container has no bootstrap ADMIN user (that row is created at app startup via
        // FOREMEN_ADMIN_CREATE, not by a changeset). Changeset 160-seed-document-templates (FOR-05-08)
        // inserts a row whose uploaded_by_id resolves to "the lowest-id ADMIN-role user", so it fails
        // against an empty users table. Liquibase commits each changeset as it goes, so the first run
        // creates the full schema + ABAC seed (through 159 — including `users` and the ADMIN role
        // seeded by 004/006) before it stops at 160. We then seed a single bootstrap ADMIN user and
        // resume the changelog, which now applies 160 through our target 167–169 cleanly. This keeps
        // the smoke self-contained without touching the out-of-scope FOR-05-08 changeset.
        try {
            runLiquibase();
        } catch (Exception firstRunStoppedAtTemplateSeed) {
            // Expected on a bare container: the structural + ABAC changesets have committed; only the
            // ADMIN-dependent template seed could not run. Prime the bootstrap ADMIN and resume.
        }
        ensureBootstrapAdminUser();
        runLiquibase();
    }

    /**
     * Inserts a single bootstrap ADMIN-role user if none exists, so changeset 160's
     * {@code uploaded_by_id} subselect resolves and the changelog can finish. Idempotent.
     */
    private void ensureBootstrapAdminUser() throws Exception {
        try (Connection c = newConnection()) {
            boolean adminUserExists;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                adminUserExists = rs.getInt(1) > 0;
            }
            if (!adminUserExists) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                                + "SELECT 'Bootstrap Admin', 'bootstrap-admin@foremen.test', r.id, true, 'ru', "
                                + "NOW(), 'test' FROM roles r WHERE r.code = 'ADMIN' ORDER BY r.id LIMIT 1")) {
                    ps.executeUpdate();
                }
            }
        }
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

    // --- 167/168: the procurement tables are created -------------------------------------

    @Test
    @DisplayName("Changesets 167/168 create the procurement_documents and procurement_lines tables")
    void createsProcurementTables() throws Exception {
        assertThat(tableExists("procurement_documents"))
                .as("167 creates procurement_documents").isTrue();
        assertThat(tableExists("procurement_lines"))
                .as("168 creates procurement_lines").isTrue();

        // A representative column from each table proves the createTable applied with its shape.
        assertThat(columnExists("procurement_documents", "raw_object_key"))
                .as("procurement_documents.raw_object_key exists (R2.1)").isTrue();
        assertThat(columnExists("procurement_documents", "status"))
                .as("procurement_documents.status exists").isTrue();
        assertThat(columnExists("procurement_lines", "match_tier"))
                .as("procurement_lines.match_tier exists").isTrue();
        assertThat(columnExists("procurement_lines", "matched_material_id"))
                .as("procurement_lines.matched_material_id exists (soft FK, R1.5)").isTrue();
    }

    @Test
    @DisplayName("168 wires the owner FK procurement_lines.document_id → procurement_documents "
            + "ON DELETE CASCADE (R1.4) and the (document_id, line_no) uniqueness (R1.2)")
    void wiresLineFkCascadeAndUniqueness() throws Exception {
        assertThat(foreignKeyDeleteRule(
                "procurement_lines", "document_id", "procurement_documents"))
                .as("procurement_lines.document_id → procurement_documents is ON DELETE CASCADE (R1.4)")
                .isEqualTo("CASCADE");

        assertThat(foreignKeyDeleteRule(
                "procurement_documents", "project_id", "projects"))
                .as("procurement_documents.project_id → projects is ON DELETE CASCADE")
                .isEqualTo("CASCADE");

        assertThat(uniqueConstraintColumns("procurement_lines"))
                .as("procurement_lines carries a UNIQUE(document_id, line_no) constraint (R1.2)")
                .anySatisfy(cols -> assertThat(cols).containsExactlyInAnyOrder("document_id", "line_no"));
    }

    // --- 169: the PURCHASES resource + grants are seeded ---------------------------------

    @Test
    @DisplayName("Changeset 169 seeds the PURCHASES resource row")
    void seedsPurchasesResource() throws Exception {
        assertThat(countResource(RESOURCE_CODE))
                .as("PURCHASES resource row exists after 169").isEqualTo(1);
    }

    @Test
    @DisplayName("Per-role grants on PURCHASES are exactly ADMIN/FINANCIER/MANAGER/FOREMAN CRUD, "
            + "ESTIMATOR READ, and none for WORKER or CLIENT (R7.4, R7.5)")
    void grantsPerRoleAreExact() throws Exception {
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN CRUD on PURCHASES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER CRUD on PURCHASES (R7.4)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER CRUD on PURCHASES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN CRUD on PURCHASES")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        assertThat(operationsFor("ESTIMATOR"))
                .as("ESTIMATOR READ only on PURCHASES")
                .containsExactly("READ");

        // WORKER and CLIENT: no role_resources row at all (deny-by-default, R7.5).
        assertThat(hasRoleResource("WORKER"))
                .as("WORKER must have no role_resources row on PURCHASES (R7.5)").isFalse();
        assertThat(hasRoleResource("CLIENT"))
                .as("CLIENT must have no role_resources row on PURCHASES (R7.5)").isFalse();
    }

    // --- re-running the changelog is idempotent (R11.3) ----------------------------------

    @Test
    @DisplayName("Re-running the full changelog inserts no duplicate procurement tables, "
            + "PURCHASES resource, or grants (167–169 are idempotent, R11.3)")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 167–169 changesets are guarded by NOT tableExists /
        // COUNT(*)=0 + MARK_RAN preconditions, so a second application makes no change.
        runLiquibase();

        // The tables still exist exactly once (a second createTable would have failed, not silently
        // duplicated — proving MARK_RAN skipped it).
        assertThat(tableExists("procurement_documents"))
                .as("procurement_documents still present after re-run").isTrue();
        assertThat(tableExists("procurement_lines"))
                .as("procurement_lines still present after re-run").isTrue();

        assertThat(countResource(RESOURCE_CODE))
                .as("PURCHASES resource row count unchanged after re-run")
                .isEqualTo(resourceBefore).isEqualTo(1);
        assertThat(countRoleResources(RESOURCE_CODE))
                .as("role_resources count on PURCHASES unchanged after re-run")
                .isEqualTo(roleResourcesBefore);
        assertThat(countRoleResourceOperations(RESOURCE_CODE))
                .as("role_resource_operations count on PURCHASES unchanged after re-run")
                .isEqualTo(roleResourceOperationsBefore);
        assertThat(snapshotGrants())
                .as("per-role grant set on PURCHASES unchanged after re-run")
                .isEqualTo(grantsBefore);
    }

    // ---------------------------------------------------------------------
    // schema query helpers
    // ---------------------------------------------------------------------

    private boolean tableExists(String table) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * The ON DELETE rule (e.g. {@code CASCADE}, {@code RESTRICT}, {@code NO ACTION}) of the single
     * FK on {@code table.column} referencing {@code referencedTable}, or {@code null} when absent.
     */
    private String foreignKeyDeleteRule(String table, String column, String referencedTable)
            throws Exception {
        String sql = "SELECT rc.delete_rule "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "JOIN information_schema.referential_constraints rc "
                + "  ON tc.constraint_name = rc.constraint_name "
                + " AND tc.table_schema = rc.constraint_schema "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON rc.unique_constraint_name = ccu.constraint_name "
                + " AND rc.unique_constraint_schema = ccu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                + "  AND tc.table_schema = 'public' "
                + "  AND tc.table_name = ? "
                + "  AND kcu.column_name = ? "
                + "  AND ccu.table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ps.setString(3, referencedTable);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The column sets of every UNIQUE constraint on the given table. */
    private List<List<String>> uniqueConstraintColumns(String table) throws Exception {
        String sql = "SELECT tc.constraint_name, kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'UNIQUE' "
                + "  AND tc.table_schema = 'public' "
                + "  AND tc.table_name = ? "
                + "ORDER BY tc.constraint_name, kcu.ordinal_position";
        Map<String, List<String>> byConstraint = new java.util.LinkedHashMap<>();
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byConstraint
                            .computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
        }
        return new ArrayList<>(byConstraint.values());
    }

    // ---------------------------------------------------------------------
    // ABAC catalog query helpers (mirror the sibling seed integration tests)
    // ---------------------------------------------------------------------

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

    /** A stable snapshot of role_code -> sorted operation codes on PURCHASES. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new TreeMap<>();
        for (String roleCode : List.of("ADMIN", "FINANCIER", "MANAGER", "FOREMAN", "ESTIMATOR",
                "WORKER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
