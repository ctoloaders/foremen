package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * FOR-06-11 task 8.2 — payroll Liquibase migration + seed-idempotency + ABAC-matrix integration test
 * (design §Testing strategy / §Security; Properties <b>P9 RoleMatrix</b>, <b>P11 SeedIdempotency</b>).
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers the
 * three FOR-06-11 changesets {@code 180}–{@code 182} last) against a Testcontainers PostgreSQL and
 * proves the end state the changesets describe, then proves re-running the full changelog changes
 * nothing (P11):
 *
 * <ul>
 *   <li><b>180</b> creates {@code worker_payouts} with the documented columns, NOT NULL FKs to
 *       {@code projects} / {@code users}, the numeric/enum/date columns, the {@code status} default
 *       {@code PLANNED}, the {@code advance_applied} default 0, and the {@code period_to >=
 *       period_from} CHECK (R1.1, R1.3, R4.5);</li>
 *   <li><b>181</b> adds the nullable {@code work_report_rooms.paid_by_payout_id} FK column — the
 *       paid-by marker — WITHOUT touching any other FOR-06-01 column (R1.3, R6.1; regression R12.2:
 *       the FOR-06-01 {@code work_report_rooms} shape + {@code work_reports.status} machine are
 *       otherwise unchanged);</li>
 *   <li><b>182</b> seeds the project-scoped {@code PAYROLL} resource and the per-role ABAC matrix —
 *       <b>P9</b>: ADMIN / MANAGER / FINANCIER CRUD; FOREMAN READ only; CLIENT + WORKER no grant
 *       (deny-by-default) (R7.4, R10.4);</li>
 *   <li><b>P11 idempotency</b> — a second application of the full changelog inserts no duplicate
 *       table / column / resource / grant and errors nowhere (guarded by {@code NOT tableExists} /
 *       {@code NOT columnExists} / {@code sqlCheck COUNT(*)=0} + {@code onFail="MARK_RAN"} and the
 *       per-row {@code NOT EXISTS} self-guards) (R1.3, R10.4).</li>
 * </ul>
 *
 * <p>Harness mirrors {@link WorkReportMigrationIntegrationTest} /
 * {@code OfferApprovalMigrationIntegrationTest}: it drives Liquibase directly against the container
 * (no Spring context) and queries via raw JDBC, so the assertions observe exactly what the migration
 * writes. The full changelog includes changeset {@code 160} (seed document templates) whose
 * {@code uploaded_by_id} subselect needs a seeded ADMIN user (created by the app at startup in the
 * real stack, not by the changelog); a bare apply against an empty DB therefore stops at 160, so the
 * harness seeds a minimal ADMIN once the {@code users}/{@code roles} tables exist and resumes, exactly
 * as {@code WorkReportMigrationIntegrationTest} does, leaving the end state the FOR-06-11 slice
 * describes. Each JVM run gets a fresh container, so it is inherently repeatable with no manual
 * cleanup.
 *
 * <p>Validates: Requirements 1.3, 6.1, 7.4, 10.4, 12.2
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-06-11-payroll, task 8.2: migration seed idempotency + ABAC matrix (P9, P11)")
class PayrollMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applyFullChangelog();
    }

    /**
     * Applies the full changelog, seeding a prerequisite ADMIN user if the first pass stops at the
     * document-template seed (changeset 160), then resuming so the FOR-06-11 changesets 180–182 are
     * reached.
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

    // --- 180: worker_payouts table + columns + defaults -----------------------------------------

    @Test
    @DisplayName("180 creates worker_payouts with the documented columns and NOT NULL FKs")
    void workerPayoutsTableCreated() throws Exception {
        assertThat(tableExists("worker_payouts")).as("180 must create worker_payouts").isTrue();
        assertColumns("worker_payouts",
                "id", "project_id", "worker_id", "period_from", "period_to", "amount",
                "suggested_amount", "advance_applied", "payment_channel", "status", "is_advance",
                "paid_at", "note");
        // The two owner FKs are NOT NULL (R1.1).
        assertThat(isNullable("worker_payouts", "project_id")).as("project_id NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "worker_id")).as("worker_id NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "period_from")).as("period_from NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "period_to")).as("period_to NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "amount")).as("amount NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "suggested_amount")).as("suggested_amount NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "advance_applied")).as("advance_applied NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "payment_channel")).as("payment_channel NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "status")).as("status NOT NULL").isFalse();
        assertThat(isNullable("worker_payouts", "is_advance")).as("is_advance NOT NULL").isFalse();
        // paid_at + note remain nullable until decided / optional.
        assertThat(isNullable("worker_payouts", "paid_at")).as("paid_at nullable").isTrue();
        assertThat(isNullable("worker_payouts", "note")).as("note nullable").isTrue();
    }

    @Test
    @DisplayName("180 defaults status to PLANNED and advance_applied to 0, and enforces period_to >= period_from")
    void workerPayoutsDefaultsAndCheck() throws Exception {
        assertThat(columnDefault("worker_payouts", "status"))
                .as("status column defaults to 'PLANNED'")
                .contains("PLANNED");
        assertThat(columnDefault("worker_payouts", "advance_applied"))
                .as("advance_applied column defaults to 0")
                .contains("0");
        // The period CHECK exists (period_to >= period_from) — assert a CHECK constraint referencing
        // both period columns is present (R4.5 at the DB level).
        assertThat(checkConstraintClauses("worker_payouts"))
                .as("worker_payouts must carry a CHECK over period_to/period_from")
                .anySatisfy(clause -> assertThat(clause)
                        .containsIgnoringCase("period_to")
                        .containsIgnoringCase("period_from"));
    }

    // --- 181: the paid_by_payout_id FK column on the FOR-06-01 report line (regression R12.2) ---

    @Test
    @DisplayName("181 adds the nullable work_report_rooms.paid_by_payout_id FK column, leaving FOR-06-01 columns intact")
    void paidByPayoutColumnAdded() throws Exception {
        assertThat(tableExists("work_report_rooms")).as("the FOR-06-01 report-line table must exist").isTrue();
        assertThat(columnExists("work_report_rooms", "paid_by_payout_id"))
                .as("181 must add the paid_by_payout_id FK column").isTrue();
        assertThat(isNullable("work_report_rooms", "paid_by_payout_id"))
                .as("the paid-by marker is nullable (a line is payable iff null, R6.1)").isTrue();

        // Regression (R12.2): the FOR-06-01 work_report_rooms columns are otherwise unchanged — the
        // original columns are all still present and the status machine lives on work_reports.status,
        // a column payroll never touches.
        assertColumns("work_report_rooms",
                "id", "report_id", "room_id", "quantity", "plan_quantity", "input_mode");
        assertThat(columnExists("work_reports", "status"))
                .as("the FOR-06-01 work_reports.status machine column is untouched").isTrue();
    }

    // --- 182: PAYROLL resource + ABAC matrix (P9) ------------------------------------------------

    @Test
    @DisplayName("182 seeds the PAYROLL resource exactly once")
    void payrollResourceSeeded() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'PAYROLL'"))
                .as("the PAYROLL resource must be seeded exactly once").isEqualTo(1L);
    }

    @Test
    @DisplayName("P9: the PAYROLL ABAC matrix is seeded per the design table (R7.4, R10.4)")
    void abacMatrixSeeded() throws Exception {
        assertThat(operationsFor("ADMIN", "PAYROLL"))
                .as("ADMIN holds full CRUD on PAYROLL (matrix completeness)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("MANAGER", "PAYROLL"))
                .as("MANAGER holds full CRUD on PAYROLL (issue, mark paid = UPDATE, delete-planned = DELETE)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("FINANCIER", "PAYROLL"))
                .as("FINANCIER holds full CRUD on PAYROLL (issue, mark paid = UPDATE, delete-planned = DELETE)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("FOREMAN", "PAYROLL"))
                .as("FOREMAN holds READ only on PAYROLL (visibility, no issuing)")
                .containsExactly("READ");
    }

    @Test
    @DisplayName("P9: CLIENT and WORKER hold no PAYROLL grant (deny-by-default)")
    void clientAndWorkerHaveNoGrant() throws Exception {
        assertThat(operationsFor("CLIENT", "PAYROLL"))
                .as("CLIENT must have no grant on PAYROLL (deny-by-default)")
                .isEmpty();
        assertThat(operationsFor("WORKER", "PAYROLL"))
                .as("WORKER must have no grant on PAYROLL (deny-by-default)")
                .isEmpty();
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN roles r ON rr.role_id = r.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE r.code IN ('CLIENT', 'WORKER') AND res.code = 'PAYROLL'"))
                .as("no CLIENT/WORKER role_resources row may exist on PAYROLL")
                .isEqualTo(0L);
    }

    // --- P11: re-running the changelog is a no-op ------------------------------------------------

    @Test
    @DisplayName("P11: re-running changesets 180-182 duplicates no table/column/resource/grant and errors nowhere")
    void reRunIsNoOp() throws Exception {
        long resourceBefore = queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'PAYROLL'");
        long roleResBefore = queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'PAYROLL'");
        long grantsBefore = queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'PAYROLL'");
        int adminBefore = operationsFor("ADMIN", "PAYROLL").size();
        int foremanBefore = operationsFor("FOREMAN", "PAYROLL").size();

        // Apply the same changelog a second time — must not error, must not change anything.
        runChangelog();

        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'PAYROLL'"))
                .as("re-run must not duplicate the PAYROLL resource")
                .isEqualTo(resourceBefore).isEqualTo(1L);
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'PAYROLL'"))
                .as("re-run must not duplicate any PAYROLL role_resources row")
                .isEqualTo(roleResBefore);
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = 'PAYROLL'"))
                .as("re-run must not duplicate any PAYROLL operation grant")
                .isEqualTo(grantsBefore);
        assertThat(operationsFor("ADMIN", "PAYROLL").size())
                .as("re-run must not change ADMIN grants").isEqualTo(adminBefore);
        assertThat(operationsFor("FOREMAN", "PAYROLL").size())
                .as("re-run must not change FOREMAN grants").isEqualTo(foremanBefore);
        // The table + the added FK column remain single.
        assertThat(tableExists("worker_payouts")).isTrue();
        assertThat(columnExists("work_report_rooms", "paid_by_payout_id")).isTrue();
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

    /** The column's DEFAULT clause text (empty string when none). */
    private String columnDefault(String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COALESCE(column_default, '') FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** The CHECK clauses declared on a table (each the raw {@code check_clause} text). */
    private List<String> checkConstraintClauses(String table) throws Exception {
        List<String> clauses = new ArrayList<>();
        String sql =
                "SELECT cc.check_clause FROM information_schema.check_constraints cc "
                        + "JOIN information_schema.constraint_column_usage ccu "
                        + "ON cc.constraint_name = ccu.constraint_name "
                        + "WHERE ccu.table_schema = 'public' AND ccu.table_name = '" + table + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                clauses.add(rs.getString(1));
            }
        }
        return clauses;
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
