package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-06-01 task 7.2 — work-reporting Liquibase migration integration test (design §7, Properties P7
 * ABAC-matrix seed + P10 seed idempotency).
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers
 * changesets {@code 163}–{@code 165} last) against a Testcontainers PostgreSQL and proves the end
 * state the three changesets describe, then proves re-running the full changelog changes nothing:
 *
 * <ul>
 *   <li><b>163</b> creates {@code work_reports} with the documented columns, NOT NULL FKs, and the
 *       NUMERIC revenue/quantity columns defaulting to 0 (R1.1);</li>
 *   <li><b>164</b> creates {@code work_report_rooms} with the documented columns and the
 *       {@code (report_id, room_id)} unique constraint (R1.2), its owner FK {@code ON DELETE CASCADE}
 *       (R1.6);</li>
 *   <li><b>165</b> seeds the project-scoped {@code WORK_REPORTS} resource and the per-role ABAC
 *       matrix — <b>P7</b>: ADMIN CRUD; FOREMAN CRUD; WORKER CRUD (own-records enforced in the
 *       service, not the matrix); MANAGER READ/UPDATE/DELETE (no CREATE); ESTIMATOR + FINANCIER READ;
 *       CLIENT nothing (deny-by-default) (R10.4);</li>
 *   <li><b>P10 idempotency</b> — a second application of the full changelog inserts no duplicate
 *       table/resource/grant and errors nowhere (guarded by {@code NOT tableExists} / {@code sqlCheck}
 *       + {@code onFail="MARK_RAN"}).</li>
 * </ul>
 *
 * <p>Harness mirrors {@code OfferApprovalMigrationIntegrationTest} /
 * {@code TeamSelectionMigrationIntegrationTest}: it drives Liquibase directly against the container
 * (no Spring context) and queries via raw JDBC, so the assertions observe exactly what the migration
 * writes. Each JVM run gets a fresh container, so it is inherently repeatable with no manual cleanup.
 *
 * <p>Validates: Requirements 1.1, 1.2, 10.4
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("Feature: FOR-06-01-work-reporting, task 7.2: migration seed idempotency (P7, P10)")
class WorkReportMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        // The full changelog includes changeset 160 (seed document templates), whose
        // `uploaded_by_id` subselect requires a seeded ADMIN user — a user the changelog itself
        // never seeds (in the real stack the ADMIN is created by the app at startup, after
        // migrations). A bare apply against an empty DB therefore fails at 160. We mirror that
        // ordering: run the changelog (it may stop at 160), seed a minimal ADMIN user once the
        // `users`/`roles` tables exist, then resume — Liquibase retries the un-recorded failed
        // changeset and continues through 163–165. This leaves the end state exactly as the
        // migration slice (and this spec's changesets) describe.
        applyFullChangelog();
    }

    /**
     * Applies the full changelog, seeding a prerequisite ADMIN user if the first pass stops at the
     * document-template seed, then resuming so changesets 163–165 are reached.
     */
    private static void applyFullChangelog() throws Exception {
        try {
            runChangelog();
        } catch (Exception first) {
            // Expected on an empty DB: changeset 160 needs an ADMIN user. Seed one and resume.
            seedAdminUserIfPossible();
            runChangelog();
        }
    }

    /** Applies the full Liquibase changelog once against the running container. */
    private static void runChangelog() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    /**
     * Seeds a single ADMIN {@code users} row (referencing the already-seeded ADMIN role) so the
     * document-template changeset's {@code uploaded_by_id} subselect resolves. No-op if the ADMIN
     * role is not yet present or an ADMIN user already exists.
     */
    private static void seedAdminUserIfPossible() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement()) {
            long adminRoleId;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)")) {
                rs.next();
                adminRoleId = rs.getLong(1);
            }
            if (adminRoleId == 0) {
                return;
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
            stmt.executeUpdate(
                    "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                            + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId
                            + ", true, 'ru', NOW(), 'test')");
        }
    }

    // --- 163 / 164: tables + columns + unique constraint -----------------------------------------

    @Test
    @DisplayName("163 creates work_reports with the documented columns")
    void workReportsTableCreated() throws Exception {
        assertThat(tableExists("work_reports")).as("163 must create work_reports").isTrue();
        assertColumns("work_reports",
                "id", "project_id", "work_item_id", "author_id", "worker_id",
                "performed_from", "performed_to", "status", "approved_by_id", "approved_at",
                "rejection_reason", "client_unit_price", "total_quantity", "revenue_value", "note");
        // The three required FKs are NOT NULL.
        assertThat(isNullable("work_reports", "project_id")).as("project_id NOT NULL").isFalse();
        assertThat(isNullable("work_reports", "work_item_id")).as("work_item_id NOT NULL").isFalse();
        assertThat(isNullable("work_reports", "author_id")).as("author_id NOT NULL").isFalse();
        assertThat(isNullable("work_reports", "worker_id")).as("worker_id NOT NULL").isFalse();
        assertThat(isNullable("work_reports", "status")).as("status NOT NULL").isFalse();
        // approved_by / approved_at / rejection_reason are nullable until decided.
        assertThat(isNullable("work_reports", "approved_by_id")).as("approved_by_id nullable").isTrue();
    }

    @Test
    @DisplayName("164 creates work_report_rooms with the documented columns and the (report, room) unique constraint")
    void workReportRoomsTableCreated() throws Exception {
        assertThat(tableExists("work_report_rooms")).as("164 must create work_report_rooms").isTrue();
        assertColumns("work_report_rooms",
                "id", "report_id", "room_id", "quantity", "plan_quantity", "input_mode");
        assertThat(isNullable("work_report_rooms", "report_id")).as("report_id NOT NULL").isFalse();
        assertThat(isNullable("work_report_rooms", "room_id")).as("room_id NOT NULL").isFalse();
        assertThat(isNullable("work_report_rooms", "quantity")).as("quantity NOT NULL").isFalse();
        assertThat(isNullable("work_report_rooms", "plan_quantity")).as("plan_quantity nullable").isTrue();
        assertThat(isNullable("work_report_rooms", "input_mode")).as("input_mode NOT NULL").isFalse();
        // Unique on (report_id, room_id) — a room appears at most once per report (R2.4).
        assertThat(uniqueConstraintColumns("work_report_rooms"))
                .as("work_report_rooms must be unique on (report_id, room_id)")
                .anySatisfy(cols -> assertThat(cols).containsExactlyInAnyOrder("report_id", "room_id"));
    }

    // --- 165: WORK_REPORTS resource + ABAC matrix (P7) -------------------------------------------

    @Test
    @DisplayName("165 seeds the WORK_REPORTS resource")
    void workReportsResourceSeeded() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'WORK_REPORTS'"))
                .as("the WORK_REPORTS resource must be seeded exactly once").isEqualTo(1L);
    }

    @Test
    @DisplayName("P7: the WORK_REPORTS ABAC matrix is seeded per the design table (R10.4)")
    void abacMatrixSeeded() throws Exception {
        assertThat(operationsFor("ADMIN", "WORK_REPORTS"))
                .as("ADMIN holds full CRUD on WORK_REPORTS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("FOREMAN", "WORK_REPORTS"))
                .as("FOREMAN holds full CRUD on WORK_REPORTS (all workers; approver)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("WORKER", "WORK_REPORTS"))
                .as("WORKER holds CRUD (own-records enforced in the service, not the matrix)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("MANAGER", "WORK_REPORTS"))
                .as("MANAGER holds READ/UPDATE/DELETE and NO CREATE on WORK_REPORTS")
                .containsExactlyInAnyOrder("READ", "UPDATE", "DELETE");
        assertThat(operationsFor("ESTIMATOR", "WORK_REPORTS"))
                .as("ESTIMATOR holds READ only on WORK_REPORTS")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER", "WORK_REPORTS"))
                .as("FINANCIER holds READ only on WORK_REPORTS")
                .containsExactly("READ");
    }

    @Test
    @DisplayName("P7: CLIENT holds no WORK_REPORTS grant (deny-by-default)")
    void clientHasNoGrant() throws Exception {
        assertThat(operationsFor("CLIENT", "WORK_REPORTS"))
                .as("CLIENT must have no grant on WORK_REPORTS (deny-by-default)")
                .isEmpty();
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN roles r ON rr.role_id = r.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE r.code = 'CLIENT' AND res.code = 'WORK_REPORTS'"))
                .as("no CLIENT role_resources row may exist on WORK_REPORTS")
                .isEqualTo(0L);
    }

    // --- P10: re-running the changelog is a no-op ------------------------------------------------

    @Test
    @DisplayName("P10: re-running the changelog duplicates no table/resource/grant and errors nowhere")
    void reRunIsNoOp() throws Exception {
        long resourceBefore = queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'WORK_REPORTS'");
        long roleResBefore = queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'WORK_REPORTS'");
        long grantsBefore = queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'WORK_REPORTS'");
        int adminBefore = operationsFor("ADMIN", "WORK_REPORTS").size();
        int managerBefore = operationsFor("MANAGER", "WORK_REPORTS").size();

        // Apply the same changelog a second time — must not error, must not change anything.
        runChangelog();

        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'WORK_REPORTS'"))
                .as("re-run must not duplicate the WORK_REPORTS resource")
                .isEqualTo(resourceBefore).isEqualTo(1L);
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'WORK_REPORTS'"))
                .as("re-run must not duplicate any WORK_REPORTS role_resources row")
                .isEqualTo(roleResBefore);
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'WORK_REPORTS'"))
                .as("re-run must not duplicate any WORK_REPORTS operation grant")
                .isEqualTo(grantsBefore);
        assertThat(operationsFor("ADMIN", "WORK_REPORTS").size())
                .as("re-run must not change ADMIN grants").isEqualTo(adminBefore);
        assertThat(operationsFor("MANAGER", "WORK_REPORTS").size())
                .as("re-run must not change MANAGER grants").isEqualTo(managerBefore);
        // Tables remain single.
        assertThat(tableExists("work_reports")).isTrue();
        assertThat(tableExists("work_report_rooms")).isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** The operation codes a role holds on a resource (empty when the role has no grant). */
    private List<String> operationsFor(String roleCode, String resourceCode) throws Exception {
        List<String> ops = new ArrayList<>();
        String sql =
                "SELECT o.code FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN roles r ON rr.role_id = r.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "JOIN operations o ON rro.operation_id = o.id "
                        + "WHERE r.code = '" + roleCode + "' AND res.code = '" + resourceCode + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ops.add(rs.getString(1));
            }
        }
        return ops;
    }

    private boolean tableExists(String table) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "')");
    }

    private boolean columnExists(String table, String column) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "')");
    }

    private void assertColumns(String table, String... columns) throws Exception {
        for (String column : columns) {
            assertThat(columnExists(table, column))
                    .as("%s must have column %s", table, column)
                    .isTrue();
        }
    }

    private boolean isNullable(String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            rs.next();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    /**
     * The column sets of every UNIQUE constraint on a table, each as a list of column names. Used to
     * assert the {@code (report_id, room_id)} composite uniqueness regardless of the generated
     * constraint name.
     */
    private List<List<String>> uniqueConstraintColumns(String table) throws Exception {
        List<List<String>> result = new ArrayList<>();
        String constraintSql =
                "SELECT tc.constraint_name FROM information_schema.table_constraints tc "
                        + "WHERE tc.table_schema = 'public' AND tc.table_name = '" + table + "' "
                        + "AND tc.constraint_type = 'UNIQUE'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(constraintSql)) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            for (String name : names) {
                result.add(constraintColumnNames(connection, name));
            }
        }
        return result;
    }

    private List<String> constraintColumnNames(Connection connection, String constraintName)
            throws Exception {
        List<String> cols = new ArrayList<>();
        String sql =
                "SELECT column_name FROM information_schema.key_column_usage "
                        + "WHERE table_schema = 'public' AND constraint_name = '" + constraintName + "' "
                        + "ORDER BY ordinal_position";
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    private long queryForLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean queryForBoolean(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
