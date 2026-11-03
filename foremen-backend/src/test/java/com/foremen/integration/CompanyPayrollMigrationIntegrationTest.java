package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-06 task 7.2 — Liquibase integration test for the company-payroll changesets
 * {@code 192}–{@code 198} (Requirements 1.1–1.9, 14.3, 17.2).
 *
 * <p>Drives the real changelog against a Testcontainers PostgreSQL in two phases:
 * <ol>
 *   <li>everything <b>up to 191</b> (the FOR-06-11 schema: {@code worker_payouts.project_id},
 *       {@code work_report_rooms.paid_by_payout_id}, FOREMAN {@code PAYROLL}/READ); then FOR-06-11
 *       legacy data is seeded — a project-owned PAID payout and a report line marked paid by it;</li>
 *   <li>the remaining changesets ({@code 192}–{@code 198}).</li>
 * </ol>
 * The tests then pin the end state: dropped/added columns, the new tables with their FKs, uniques and
 * CHECKs, no data conversion (R1.9: the legacy line has {@code paid_amount = 0}, the legacy payout
 * survives without a project), the FOREMAN {@code PAYROLL} grant revoked while ADMIN / MANAGER /
 * FINANCIER keep CRUD (R14.3), and <b>idempotency</b> (R1.8): with the {@code DATABASECHANGELOG}
 * rows of 192–198 removed, a re-run records every one of them as {@code MARK_RAN} and changes no
 * schema or data; a plain re-run records nothing new.
 *
 * <p>Like {@link PayrollMigrationIntegrationTest}, the first pass stops at changeset 160 (it needs an
 * ADMIN user the app seeds at startup), so a minimal ADMIN is inserted and the run resumed. Each JVM
 * run gets a fresh container — repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 14.3
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("Feature: FOR-07-06-company-payroll, task 7.2: changesets 192-198 end state + idempotency")
class CompanyPayrollMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /** Changeset ids of the FOR-07-06 slice ({@code 192-…} to {@code 198-…}). */
    private static final Pattern FOR_07_06_ID = Pattern.compile("^19[2-8]-.*");
    private static final String FOR_07_06_ID_SQL = "id ~ '^19[2-8]-'";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private long legacyLineId;
    private long legacyPayoutId;
    private long legacyWorkerId;
    private List<String> foremanPayrollOpsBefore198;
    private List<String> slice;

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        // Phase 1 — everything before the FOR-07-06 slice.
        try {
            runAllExceptSlice();
        } catch (Exception first) {
            // Expected on an empty DB: changeset 160 needs an ADMIN user. Seed one and resume.
            seedAdminUserIfPossible();
            runAllExceptSlice();
        }
        assertThat(slice).as("the unrun tail is exactly the FOR-07-06 slice")
                .isNotEmpty()
                .allMatch(id -> FOR_07_06_ID.matcher(id).matches());
        assertThat(columnExists("worker_payouts", "project_id")).as("pre-192 FOR-06-11 shape").isTrue();
        assertThat(columnExists("work_report_rooms", "paid_by_payout_id")).isTrue();

        foremanPayrollOpsBefore198 = operationsFor("FOREMAN", "PAYROLL");
        seedLegacyPayrollData();

        // Phase 2 — the FOR-07-06 slice.
        runChangelog();
    }

    // --- 192: work_report_rooms.paid_by_payout_id → paid_amount ---------------------------------

    @Test
    @Order(1)
    @DisplayName("192 drops paid_by_payout_id + its FK and adds paid_amount NOT NULL DEFAULT 0 with CHECK >= 0")
    void reportLinePaidAmountReplacesPaidMarker() throws Exception {
        assertThat(columnExists("work_report_rooms", "paid_by_payout_id")).as("old marker dropped (R1.1)").isFalse();
        assertThat(constraintExists("fk_work_report_rooms_paid_by_payout")).as("old FK dropped (R1.1)").isFalse();

        assertThat(columnExists("work_report_rooms", "paid_amount")).as("paid_amount added (R1.2)").isTrue();
        assertThat(isNullable("work_report_rooms", "paid_amount")).isFalse();
        assertThat(dataType("work_report_rooms", "paid_amount")).isEqualTo("numeric(19,2)");
        assertThat(columnDefault("work_report_rooms", "paid_amount")).contains("0");
        assertThat(checkClause("ck_work_report_rooms_paid_amount_nonneg")).contains("paid_amount").contains(">=");
    }

    @Test
    @Order(2)
    @DisplayName("R1.9: no data conversion — the legacy line paid by a FOR-06-11 payout starts at paid_amount = 0")
    void legacyLineStartsUnpaid() throws Exception {
        assertThat(queryForString("SELECT paid_amount::text FROM work_report_rooms WHERE id = " + legacyLineId))
                .isEqualTo("0.00");
        assertThat(queryForLong("SELECT COUNT(*) FROM work_report_rooms WHERE paid_amount <> 0"))
                .as("every existing line starts at 0").isZero();
    }

    // --- 193: worker_payouts worker-wide --------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("193 drops worker_payouts.project_id (+ index, FK) and adds payout_date NOT NULL + (worker_id, payout_date) index")
    void workerPayoutsWorkerWide() throws Exception {
        assertThat(columnExists("worker_payouts", "project_id")).as("project_id dropped (R1.3)").isFalse();
        assertThat(indexExists("ix_worker_payouts_project")).isFalse();
        assertThat(constraintExists("fk_worker_payouts_project")).isFalse();

        assertThat(columnExists("worker_payouts", "payout_date")).isTrue();
        assertThat(isNullable("worker_payouts", "payout_date")).isFalse();
        assertThat(dataType("worker_payouts", "payout_date")).isEqualTo("date");
        assertThat(columnDefault("worker_payouts", "payout_date")).containsIgnoringCase("CURRENT_DATE");
        assertThat(indexDef("ix_worker_payouts_worker_date")).contains("(worker_id, payout_date)");
    }

    @Test
    @Order(4)
    @DisplayName("R1.9: the legacy payout survives without a project, with a payout_date")
    void legacyPayoutSurvives() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM worker_payouts WHERE id = " + legacyPayoutId
                + " AND worker_id = " + legacyWorkerId + " AND status = 'PAID' AND payout_date IS NOT NULL"))
                .isEqualTo(1L);
        assertThat(queryForLong("SELECT COUNT(*) FROM worker_payout_allocations"))
                .as("no allocations are synthesized from the old marker (R1.9)").isZero();
    }

    // --- 194–196: new tables --------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("194 creates worker_payout_allocations with CASCADE FKs, unique (payout, line), line index, CHECK > 0")
    void allocationsTable() throws Exception {
        assertColumns("worker_payout_allocations", "id", "payout_id", "work_report_room_id", "amount",
                "created_date", "created_by", "updated_date", "updated_by");
        assertThat(isNullable("worker_payout_allocations", "payout_id")).isFalse();
        assertThat(isNullable("worker_payout_allocations", "work_report_room_id")).isFalse();
        assertThat(isNullable("worker_payout_allocations", "amount")).isFalse();
        assertThat(dataType("worker_payout_allocations", "amount")).isEqualTo("numeric(19,2)");
        assertThat(fkDeleteRule("fk_worker_payout_allocations_payout")).isEqualTo("c");
        assertThat(fkDeleteRule("fk_worker_payout_allocations_line")).isEqualTo("c");
        assertThat(constraintType("uq_worker_payout_allocations_payout_line")).isEqualTo("u");
        assertThat(indexDef("ix_worker_payout_allocations_line")).contains("(work_report_room_id)");
        assertThat(checkClause("ck_worker_payout_allocations_amount_positive")).contains("amount").contains(">");
    }

    @Test
    @Order(6)
    @DisplayName("195 creates worker_payroll_lines (worker FK, month, name, amount CHECK <> 0, note) with (worker_id, month) index")
    void payrollLinesTable() throws Exception {
        assertColumns("worker_payroll_lines", "id", "worker_id", "month", "name", "amount", "note",
                "created_date", "created_by", "updated_date", "updated_by");
        assertThat(isNullable("worker_payroll_lines", "worker_id")).isFalse();
        assertThat(isNullable("worker_payroll_lines", "month")).isFalse();
        assertThat(isNullable("worker_payroll_lines", "name")).isFalse();
        assertThat(isNullable("worker_payroll_lines", "amount")).isFalse();
        assertThat(isNullable("worker_payroll_lines", "note")).isTrue();
        assertThat(dataType("worker_payroll_lines", "name")).isEqualTo("character varying(255)");
        assertThat(constraintType("fk_worker_payroll_lines_worker")).isEqualTo("f");
        assertThat(checkClause("ck_worker_payroll_lines_amount_non_zero")).contains("amount").contains("<>");
        assertThat(indexDef("ix_worker_payroll_lines_worker_month")).contains("(worker_id, month)");
    }

    @Test
    @Order(7)
    @DisplayName("196 creates worker_payroll_profiles (unique worker FK, opening_balance NOT NULL DEFAULT 0)")
    void payrollProfilesTable() throws Exception {
        assertColumns("worker_payroll_profiles", "id", "worker_id", "opening_balance",
                "created_date", "created_by", "updated_date", "updated_by");
        assertThat(isNullable("worker_payroll_profiles", "opening_balance")).isFalse();
        assertThat(columnDefault("worker_payroll_profiles", "opening_balance")).contains("0");
        assertThat(constraintType("uq_worker_payroll_profiles_worker")).isEqualTo("u");
        assertThat(constraintType("fk_worker_payroll_profiles_worker")).isEqualTo("f");
    }

    // --- 197: users.seniority_pct ---------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("197 adds users.seniority_pct NUMERIC(6,4) NOT NULL DEFAULT 0 with CHECK 0..1; existing users get 0")
    void userSeniorityPct() throws Exception {
        assertThat(isNullable("users", "seniority_pct")).isFalse();
        assertThat(dataType("users", "seniority_pct")).isEqualTo("numeric(6,4)");
        assertThat(checkClause("ck_users_seniority_pct_range")).contains(">=").contains("<=");
        assertThat(queryForLong("SELECT COUNT(*) FROM users WHERE seniority_pct <> 0"))
                .as("no backfill: every existing user has 0").isZero();
    }

    // --- 198: FOREMAN PAYROLL revoked -----------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("198 revokes FOREMAN PAYROLL/READ; ADMIN/MANAGER/FINANCIER keep CRUD; CLIENT/WORKER stay ungranted (R14.3)")
    void foremanPayrollGrantRevoked() throws Exception {
        assertThat(foremanPayrollOpsBefore198).as("FOR-06-11 (182) seeded FOREMAN READ").containsExactly("READ");

        assertThat(operationsFor("FOREMAN", "PAYROLL")).as("FOREMAN: no grant").isEmpty();
        assertThat(queryForLong("SELECT COUNT(*) FROM role_resources rr JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE r.code = 'FOREMAN' AND res.code = 'PAYROLL'"))
                .as("the FOREMAN role_resources row is gone too").isZero();
        for (String role : List.of("ADMIN", "MANAGER", "FINANCIER")) {
            assertThat(operationsFor(role, "PAYROLL")).as("%s keeps PAYROLL CRUD", role)
                    .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        }
        assertThat(operationsFor("CLIENT", "PAYROLL")).isEmpty();
        assertThat(operationsFor("WORKER", "PAYROLL")).isEmpty();
    }

    // --- R1.8: idempotency ----------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("R1.8: re-running 192-198 (changelog rows removed) marks every changeset MARK_RAN and changes nothing")
    void sliceRerunIsIdempotent() throws Exception {
        String schemaBefore = schemaFingerprint();
        String grantsBefore = grantsFingerprint();
        long linesBefore = queryForLong("SELECT COUNT(*) FROM work_report_rooms");
        long payoutsBefore = queryForLong("SELECT COUNT(*) FROM worker_payouts");
        long sliceRows = queryForLong("SELECT COUNT(*) FROM databasechangelog WHERE " + FOR_07_06_ID_SQL);
        assertThat(sliceRows).as("every slice changeset was recorded").isEqualTo(slice.size());

        // Force Liquibase to evaluate every precondition again.
        execute("DELETE FROM databasechangelog WHERE " + FOR_07_06_ID_SQL);
        runChangelog();

        assertThat(queryForLong("SELECT COUNT(*) FROM databasechangelog WHERE " + FOR_07_06_ID_SQL))
                .isEqualTo(sliceRows);
        assertThat(queryForLong("SELECT COUNT(*) FROM databasechangelog WHERE " + FOR_07_06_ID_SQL
                + " AND exectype <> 'MARK_RAN'"))
                .as("every guarded changeset is skipped as MARK_RAN on re-run").isZero();
        assertThat(schemaFingerprint()).as("schema unchanged").isEqualTo(schemaBefore);
        assertThat(grantsFingerprint()).as("matrix unchanged").isEqualTo(grantsBefore);
        assertThat(queryForLong("SELECT COUNT(*) FROM work_report_rooms")).isEqualTo(linesBefore);
        assertThat(queryForLong("SELECT COUNT(*) FROM worker_payouts")).isEqualTo(payoutsBefore);

        // A plain re-run records nothing new.
        long allRows = queryForLong("SELECT COUNT(*) FROM databasechangelog");
        runChangelog();
        assertThat(queryForLong("SELECT COUNT(*) FROM databasechangelog")).isEqualTo(allRows);
    }

    // --- migration harness ----------------------------------------------------------------------

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Applies every unrun changeset except the trailing FOR-07-06 slice; records the slice ids. */
    private void runAllExceptSlice() throws Exception {
        try (Connection connection = connect()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int sliceSize = 0;
                for (int i = unrun.size() - 1; i >= 0 && FOR_07_06_ID.matcher(unrun.get(i).getId()).matches(); i--) {
                    sliceSize++;
                }
                slice = unrun.subList(unrun.size() - sliceSize, unrun.size()).stream().map(ChangeSet::getId).toList();
                int toApply = unrun.size() - sliceSize;
                if (toApply > 0) {
                    liquibase.update(toApply, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    private void runChangelog() throws Exception {
        try (Connection connection = connect()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private void seedAdminUserIfPossible() throws Exception {
        long adminRoleId = queryForLong("SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)");
        if (adminRoleId == 0) {
            return;
        }
        if (queryForLong("SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'") > 0) {
            return;
        }
        execute("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId + ", true, 'ru', NOW(), 'test')");
    }

    /**
     * FOR-06-11 legacy data on the pre-192 schema: a WORKER with an APPROVED report line on a
     * project, and a project-owned PAID payout that marked the line paid via {@code paid_by_payout_id}.
     */
    private void seedLegacyPayrollData() throws Exception {
        long workerRoleId = queryForLong("SELECT id FROM roles WHERE code = 'WORKER'");
        long unitId = insert("INSERT INTO measurement_units (code, name_ru, name_pl) "
                + "VALUES ('mig0706_unit', 'ед', 'jedn') RETURNING id");
        long categoryId = insert("INSERT INTO work_categories (code, order_no, name_ru, name_pl) "
                + "VALUES ('mig0706_cat', 1, 'кат', 'kat') RETURNING id");
        long workItemId = insert("INSERT INTO work_items (work_category_id, unit_id, name_ru, name_pl, code) "
                + "VALUES (" + categoryId + ", " + unitId + ", 'работа', 'praca', 'mig0706_wi') RETURNING id");
        long roomTypeId = insert("INSERT INTO room_types (code, name_ru, name_pl) "
                + "VALUES ('mig0706_rt', 'комната', 'pokój') RETURNING id");
        long projectId = insert("INSERT INTO projects (name, status) VALUES ('mig0706 project', 'ACTIVE') RETURNING id");
        long roomId = insert("INSERT INTO rooms (project_id, room_type_id, label) "
                + "VALUES (" + projectId + ", " + roomTypeId + ", 'mig0706 room') RETURNING id");
        legacyWorkerId = insert("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                + "VALUES ('mig0706 worker', 'mig0706-worker@example.com', " + workerRoleId
                + ", true, 'pl', NOW(), 'test') RETURNING id");
        long reportId = insert("INSERT INTO work_reports (project_id, work_item_id, author_id, worker_id, "
                + "performed_from, performed_to, status, client_unit_price, total_quantity, revenue_value) VALUES ("
                + projectId + ", " + workItemId + ", " + legacyWorkerId + ", " + legacyWorkerId
                + ", DATE '2025-01-10', DATE '2025-01-12', 'APPROVED', 100.00, 2.000, 200.00) RETURNING id");
        legacyLineId = insert("INSERT INTO work_report_rooms (report_id, room_id, quantity, input_mode) VALUES ("
                + reportId + ", " + roomId + ", 2.000, 'ABSOLUTE') RETURNING id");
        legacyPayoutId = insert("INSERT INTO worker_payouts (project_id, worker_id, period_from, period_to, amount, "
                + "suggested_amount, payment_channel, status, is_advance) VALUES (" + projectId + ", "
                + legacyWorkerId + ", DATE '2025-01-01', DATE '2025-01-31', 150.00, 150.00, 'CASH', 'PAID', false) "
                + "RETURNING id");
        execute("UPDATE work_report_rooms SET paid_by_payout_id = " + legacyPayoutId + " WHERE id = " + legacyLineId);
    }

    // --- SQL helpers ----------------------------------------------------------------------------

    private long insert(String sql) throws Exception {
        return queryForLong(sql);
    }

    private void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private long queryForLong(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String queryForString(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private List<String> queryForStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private boolean columnExists(String table, String column) throws Exception {
        return queryForLong("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = '" + table + "' AND column_name = '" + column + "'") > 0;
    }

    private void assertColumns(String table, String... columns) throws Exception {
        for (String column : columns) {
            assertThat(columnExists(table, column)).as("%s.%s exists", table, column).isTrue();
        }
    }

    private boolean isNullable(String table, String column) throws Exception {
        return "YES".equalsIgnoreCase(queryForString("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "' AND column_name = '" + column + "'"));
    }

    private String dataType(String table, String column) throws Exception {
        return queryForString("SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                + "WHERE a.attrelid = 'public." + table + "'::regclass AND a.attname = '" + column + "'");
    }

    private String columnDefault(String table, String column) throws Exception {
        String value = queryForString("SELECT COALESCE(column_default, '') FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "' AND column_name = '" + column + "'");
        return value == null ? "" : value;
    }

    private boolean constraintExists(String name) throws Exception {
        return queryForLong("SELECT COUNT(*) FROM pg_constraint WHERE conname = '" + name + "'") > 0;
    }

    /** {@code pg_constraint.contype}: {@code f} FK, {@code u} unique, {@code c} check, … */
    private String constraintType(String name) throws Exception {
        return queryForString("SELECT contype::text FROM pg_constraint WHERE conname = '" + name + "'");
    }

    /** {@code pg_constraint.confdeltype}: {@code c} = ON DELETE CASCADE. */
    private String fkDeleteRule(String name) throws Exception {
        return queryForString("SELECT confdeltype::text FROM pg_constraint WHERE conname = '" + name
                + "' AND contype = 'f'");
    }

    private String checkClause(String name) throws Exception {
        String def = queryForString("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '"
                + name + "' AND contype = 'c'");
        return def == null ? "" : def;
    }

    private boolean indexExists(String name) throws Exception {
        return queryForLong("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                + name + "'") > 0;
    }

    private String indexDef(String name) throws Exception {
        String def = queryForString("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                + name + "'");
        return def == null ? "" : def;
    }

    private List<String> operationsFor(String roleCode, String resourceCode) throws Exception {
        return queryForStrings("SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = '" + roleCode + "' AND res.code = '" + resourceCode + "'");
    }

    /** Columns + constraints + indexes of the tables the slice touches, as one comparable string. */
    private String schemaFingerprint() throws Exception {
        String tables = "('work_report_rooms','worker_payouts','worker_payout_allocations',"
                + "'worker_payroll_lines','worker_payroll_profiles','users')";
        List<String> parts = new ArrayList<>();
        parts.addAll(queryForStrings("SELECT table_name || '.' || column_name || ':' || data_type || ':' "
                + "|| is_nullable || ':' || COALESCE(column_default, '') FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name IN " + tables + " ORDER BY 1"));
        parts.addAll(queryForStrings("SELECT c.conname || ':' || pg_get_constraintdef(c.oid) FROM pg_constraint c "
                + "JOIN pg_class t ON c.conrelid = t.oid WHERE t.relname IN " + tables + " ORDER BY 1"));
        parts.addAll(queryForStrings("SELECT indexname || ':' || indexdef FROM pg_indexes "
                + "WHERE schemaname = 'public' AND tablename IN " + tables + " ORDER BY 1"));
        return String.join("\n", parts);
    }

    private String grantsFingerprint() throws Exception {
        return String.join(",", queryForStrings("SELECT r.code || '/' || res.code || '/' || o.code "
                + "FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id ORDER BY 1"));
    }
}
