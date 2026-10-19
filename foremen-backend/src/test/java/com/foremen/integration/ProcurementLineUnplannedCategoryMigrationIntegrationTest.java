package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-06-04 task 8.2 — the additive changeset {@code 170-add-procurement-line-unplanned-category}
 * migration integration test (design §4.1, §4.5; Requirements 1.1, 1.3, 7.2).
 *
 * <p>Applies the <em>real</em> Liquibase changelog ({@code database_files/changelog.xml}, which
 * registers changeset {@code 170} last) against a Testcontainers PostgreSQL — Liquibase enabled,
 * asserting against the live schema + data via raw JDBC — rather than the {@code integration-test}
 * Hibernate {@code create-drop} profile (which disables Liquibase). It is the only FOR-06-04 check
 * that the migration itself applies end-to-end. The harness mirrors
 * {@code com.foremen.dao.integration.ProcurementMigrationSmokeIntegrationTest} (the FOR-06-03
 * sibling) / {@link WorkReportMigrationIntegrationTest}: a bare container has no bootstrap ADMIN user
 * (created at app startup via {@code FOREMEN_ADMIN_CREATE}, not by a changeset), so the first apply
 * stops at changeset {@code 160-seed-document-templates} whose {@code uploaded_by_id} subselect needs
 * an ADMIN; we then seed a single bootstrap ADMIN and resume so {@code 170} is reached.
 *
 * <p>Verified here:
 * <ul>
 *   <li><b>R1.1</b> — {@code 170} adds the nullable {@code procurement_lines.unplanned_category_id}
 *       column (a line is classified only when a user tags it, so the column must be NULLABLE);</li>
 *   <li><b>R1.3</b> — the FK {@code procurement_lines.unplanned_category_id} →
 *       {@code unplanned_expense_categories(id)} is {@code ON DELETE SET NULL}, asserted both from the
 *       catalog and by the <em>runtime</em> behaviour: deleting a referenced dictionary row clears the
 *       classification on the line (the line survives, its FK is nulled) rather than cascading;</li>
 *   <li><b>R1.1 idempotency</b> — re-running the full changelog adds no second column and errors
 *       nowhere (the changeset is guarded by {@code NOT columnExists} + {@code onFail="MARK_RAN"});</li>
 *   <li><b>R7.2 / no new resource</b> — {@code 170} seeds <em>no</em> new ABAC resource: the
 *       {@code PURCHASES} resource exists exactly once (from the FOR-06-03 changeset {@code 169}) and
 *       its grant matrix is unchanged; no resource was introduced by this spec.</li>
 * </ul>
 *
 * <p>The OCR provider is NOT involved in a migration run, so no external service is contacted. Each
 * JVM run gets a fresh container, so the test is inherently repeatable with no manual cleanup.
 *
 * <p>Validates: Requirements 1.1, 1.3, 7.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("Feature: FOR-06-04-purchases-list, task 8.2: changeset 170 migration + ON DELETE SET NULL")
class ProcurementLineUnplannedCategoryMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startContainerAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        // A bare container has no bootstrap ADMIN user; changeset 160-seed-document-templates
        // (FOR-05-08) inserts a row whose uploaded_by_id subselect resolves to the lowest-id
        // ADMIN-role user and so fails against an empty users table. Liquibase commits each changeset
        // as it goes, so the first run builds the full schema + ABAC seed (through 159) before it
        // stops at 160. Seed a single bootstrap ADMIN and resume: 160 through our target 170 then
        // apply cleanly. This keeps the test self-contained without touching FOR-05-08's changeset.
        try {
            runLiquibase();
        } catch (Exception firstRunStoppedAtTemplateSeed) {
            // Expected on a bare container: the structural + ABAC changesets have committed.
        }
        ensureBootstrapAdminUser();
        runLiquibase();
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    // --- 170: the nullable classification column --------------------------------------------------

    @Test
    @DisplayName("170 adds the nullable procurement_lines.unplanned_category_id column (R1.1)")
    void addsNullableClassificationColumn() throws Exception {
        assertThat(columnExists("procurement_lines", "unplanned_category_id"))
                .as("170 must add procurement_lines.unplanned_category_id (R1.1)").isTrue();
        assertThat(isNullable("procurement_lines", "unplanned_category_id"))
                .as("unplanned_category_id must be NULLABLE — a line is classified only when a user "
                        + "tags a MANUAL_UNMATCHED/UNMATCHED line (R1.1/R1.3)").isTrue();
    }

    @Test
    @DisplayName("170 wires the FK procurement_lines.unplanned_category_id → "
            + "unplanned_expense_categories ON DELETE SET NULL (R1.3)")
    void wiresUnplannedCategoryFkOnDeleteSetNull() throws Exception {
        assertThat(foreignKeyDeleteRule(
                "procurement_lines", "unplanned_category_id", "unplanned_expense_categories"))
                .as("procurement_lines.unplanned_category_id → unplanned_expense_categories must be "
                        + "ON DELETE SET NULL (R1.3) — deleting a dictionary row clears the "
                        + "classification rather than cascading to the line")
                .isEqualTo("SET NULL");
    }

    // --- R1.3: the ON DELETE SET NULL runtime behaviour -------------------------------------------

    @Test
    @DisplayName("Deleting a referenced UnplannedExpenseCategory NULLs the line's classification and "
            + "keeps the line (ON DELETE SET NULL, R1.3)")
    void deletingReferencedCategorySetsLineFkNullAndKeepsLine() throws Exception {
        try (Connection c = newConnection()) {
            c.setAutoCommit(true);
            long adminUserId = lowestAdminUserId(c);

            // Seed the minimal owning chain: a project, a procurement document (uploaded by the
            // bootstrap ADMIN), a dictionary category, and a MANUAL_UNMATCHED line classified with it.
            long projectId = insertProject(c, "set-null-mig-project");
            long documentId = insertDocument(c, projectId, adminUserId);
            long categoryId = insertCategory(c, "SETNULL_MIG");
            long lineId = insertClassifiedLine(c, documentId, categoryId);

            assertThat(lineUnplannedCategoryId(c, lineId))
                    .as("the seeded line is classified with the category before the delete")
                    .isEqualTo(categoryId);

            // Delete the referenced dictionary row.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM unplanned_expense_categories WHERE id = ?")) {
                ps.setLong(1, categoryId);
                ps.executeUpdate();
            }

            // R1.3 — the line survives (NOT cascaded away) and its classification is NULLed.
            assertThat(lineExists(c, lineId))
                    .as("the line must survive the category delete (ON DELETE SET NULL, never CASCADE)")
                    .isTrue();
            assertThat(lineUnplannedCategoryId(c, lineId))
                    .as("the line's unplanned_category_id must be NULLed by ON DELETE SET NULL (R1.3)")
                    .isNull();
        }
    }

    // --- R1.1: re-running the changelog is idempotent ---------------------------------------------

    @Test
    @DisplayName("Re-running the full changelog adds no second column and errors nowhere "
            + "(170 is NOT columnExists + MARK_RAN guarded, R1.1)")
    void reRunningChangelogIsIdempotent() throws Exception {
        assertThat(columnExists("procurement_lines", "unplanned_category_id")).isTrue();

        // Re-apply the entire changelog; 170 is guarded by NOT columnExists + MARK_RAN.
        runLiquibase();

        assertThat(columnExists("procurement_lines", "unplanned_category_id"))
                .as("the column still exists exactly once after a re-run").isTrue();
        assertThat(isNullable("procurement_lines", "unplanned_category_id"))
                .as("the column stays nullable after a re-run").isTrue();
        assertThat(foreignKeyDeleteRule(
                "procurement_lines", "unplanned_category_id", "unplanned_expense_categories"))
                .as("the FK delete rule is unchanged after a re-run").isEqualTo("SET NULL");
    }

    // --- R7.2: no new ABAC resource was seeded by this spec ---------------------------------------

    @Test
    @DisplayName("170 seeds NO new ABAC resource: PURCHASES exists once (from 169) and its matrix is "
            + "unchanged (R7.2)")
    void noNewAbacResourceSeeded() throws Exception {
        try (Connection c = newConnection()) {
            // The PURCHASES resource is present exactly once — seeded by FOR-06-03 changeset 169, not
            // re-seeded or duplicated by FOR-06-04.
            assertThat(countResource(c, "PURCHASES"))
                    .as("PURCHASES must exist exactly once (FOR-06-03 169; FOR-06-04 rides it)")
                    .isEqualTo(1);

            // The reconciliation surface reuses PURCHASES (UPDATE / READ): its grant matrix is the
            // FOR-06-03 matrix, untouched by this spec. ADMIN/FINANCIER/MANAGER/FOREMAN hold CRUD.
            assertThat(operationsFor(c, "ADMIN", "PURCHASES"))
                    .as("ADMIN CRUD on PURCHASES unchanged")
                    .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
            assertThat(operationsFor(c, "FINANCIER", "PURCHASES"))
                    .as("FINANCIER CRUD on PURCHASES unchanged")
                    .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

            // There is no "new" purchases-reconciliation resource: no resource code other than the
            // shipped PURCHASES references the reconciliation surface. Assert PURCHASES is the single
            // procurement resource (a FOR-06-04-introduced resource would show up as a second one).
            assertThat(countResourceLike(c, "PURCHAS%"))
                    .as("FOR-06-04 introduces no new procurement/purchases ABAC resource (R7.2)")
                    .isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------------
    // migration + bootstrap helpers
    // ---------------------------------------------------------------------

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

    /** Inserts a single bootstrap ADMIN-role user if none exists so changeset 160 resolves. Idempotent. */
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

    private long lowestAdminUserId(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT u.id FROM users u JOIN roles r ON u.role_id = r.id "
                        + "WHERE r.code = 'ADMIN' ORDER BY u.id LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("a bootstrap ADMIN user must exist").isTrue();
            return rs.getLong(1);
        }
    }

    // ---------------------------------------------------------------------
    // fixture seeding (raw JDBC — observes exactly the migrated schema)
    // ---------------------------------------------------------------------

    private long insertProject(Connection c, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO projects (name, status, created_date, created_by) "
                        + "VALUES (?, 'ACTIVE', NOW(), 'test') RETURNING id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertDocument(Connection c, long projectId, long uploadedById) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO procurement_documents "
                        + "(project_id, uploaded_by_id, raw_object_key, source_mime_type, status, "
                        + " uploaded_at, created_date, created_by) "
                        + "VALUES (?, ?, 'raw/mig.png', 'image/png', 'PARSED', NOW(), NOW(), 'test') "
                        + "RETURNING id")) {
            ps.setLong(1, projectId);
            ps.setLong(2, uploadedById);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertCategory(Connection c, String code) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO unplanned_expense_categories (code, name_ru, name_pl, active, created_date, created_by) "
                        + "VALUES (?, 'рег', 'reg', true, NOW(), 'test') RETURNING id")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** A MANUAL_UNMATCHED line (tier NONE, no material) classified with the given category. */
    private long insertClassifiedLine(Connection c, long documentId, long categoryId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO procurement_lines "
                        + "(document_id, line_no, raw_name, match_tier, match_status, unplanned_category_id, "
                        + " created_date, created_by) "
                        + "VALUES (?, 1, 'Цемент 25кг', 'NONE', 'MANUAL_UNMATCHED', ?, NOW(), 'test') "
                        + "RETURNING id")) {
            ps.setLong(1, documentId);
            ps.setLong(2, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private boolean lineExists(Connection c, long lineId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM procurement_lines WHERE id = ?")) {
            ps.setLong(1, lineId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** The line's {@code unplanned_category_id}, or {@code null} when the column is SQL NULL. */
    private Long lineUnplannedCategoryId(Connection c, long lineId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT unplanned_category_id FROM procurement_lines WHERE id = ?")) {
            ps.setLong(1, lineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the line row must exist").isTrue();
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    // ---------------------------------------------------------------------
    // schema + catalog query helpers
    // ---------------------------------------------------------------------

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private boolean isNullable(String table, String column) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return "YES".equalsIgnoreCase(rs.getString(1));
            }
        }
    }

    /**
     * The ON DELETE rule (e.g. {@code CASCADE}, {@code SET NULL}, {@code NO ACTION}) of the single FK
     * on {@code table.column} referencing {@code referencedTable}, or {@code null} when absent.
     */
    private String foreignKeyDeleteRule(String table, String column, String referencedTable)
            throws Exception {
        String sql = "SELECT rc.delete_rule "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                + "JOIN information_schema.referential_constraints rc "
                + "  ON tc.constraint_name = rc.constraint_name AND tc.table_schema = rc.constraint_schema "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON rc.unique_constraint_name = ccu.constraint_name "
                + " AND rc.unique_constraint_schema = ccu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' "
                + "  AND tc.table_name = ? AND kcu.column_name = ? AND ccu.table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ps.setString(3, referencedTable);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private int countResource(Connection c, String code) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM resources WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countResourceLike(Connection c, String pattern) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM resources WHERE code LIKE ?")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private java.util.List<String> operationsFor(Connection c, String roleCode, String resourceCode)
            throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? ORDER BY o.code";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> ops = new java.util.ArrayList<>();
                while (rs.next()) {
                    ops.add(rs.getString(1));
                }
                return ops;
            }
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
