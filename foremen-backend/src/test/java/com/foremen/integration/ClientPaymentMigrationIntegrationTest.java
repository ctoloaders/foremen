package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * FOR-11-06 task 2.8 (R1.1, R1.2, R14.4, R14.5): changeset {@code 247} (unified {@code client_payments} base
 * table) applied through the real Liquibase changelog over <b>legacy data</b>. The test applies every changeset
 * before {@code 247}, seeds tranches, proformas, transactions, allocations, bank links and proforma lines on
 * the old schema, then applies the rest of the changelog and proves: one base row per tranche and proforma,
 * counts and {@code SUM(amount_gross)} / {@code SUM(paid_gross)} per kind preserved, every allocation, bank
 * link and proforma line still resolves, the CHECK constraints reject bad rows, and a re-run changes nothing.
 *
 * <p>Repeatability: a fresh container per JVM run; legacy rows are inserted with run-unique values.
 *
 * <p>Validates: Requirements 1.1, 1.2, 14.4, 14.5
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-11-06-delivery-ux-refinements, task 2.8: changeset 247 backfills and keeps links resolvable")
class ClientPaymentMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String FIRST_CHANGESET_PREFIX = "247-";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    // Expected legacy totals, filled by seedLegacyData().
    private long trancheCount;
    private long proformaCount;
    private BigDecimal worksAmount;
    private BigDecimal worksPaid;
    private BigDecimal materialsAmount;
    private BigDecimal materialsPaid;
    private long allocationCount;
    private long clientLinkCount;
    private long proformaLinkCount;
    private long lineCount;

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try {
            applyBefore247();
        } catch (Exception first) {
            // Changeset 160 needs an ADMIN user on an empty DB: seed one and resume.
            seedAdminUserIfPossible();
            applyBefore247();
        }
        assertThat(tableExists("client_payments")).as("old schema before 247").isFalse();
        seedLegacyData();
        runChangelog();
    }

    // ------------------------------------------------------------------ the tests

    @Test
    @DisplayName("one base row per tranche and per proforma, kinds match, nothing extra")
    void oneBaseRowPerDetail() throws Exception {
        assertThat(trancheCount).isEqualTo(4L);
        assertThat(proformaCount).isEqualTo(4L);
        assertThat(count("SELECT COUNT(*) FROM client_payments WHERE kind = 'WORKS'")).isEqualTo(trancheCount);
        assertThat(count("SELECT COUNT(*) FROM client_payments WHERE kind = 'MATERIALS'"))
                .isEqualTo(proformaCount);
        assertThat(count("SELECT COUNT(*) FROM client_payments")).isEqualTo(trancheCount + proformaCount);
        assertThat(count("SELECT COUNT(*) FROM payment_tranches WHERE client_payment_id IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM client_proformas WHERE client_payment_id IS NULL")).isZero();
        assertThat(count("SELECT COUNT(DISTINCT client_payment_id) FROM payment_tranches"))
                .isEqualTo(trancheCount);
        assertThat(count("SELECT COUNT(DISTINCT client_payment_id) FROM client_proformas"))
                .isEqualTo(proformaCount);
        assertThat(count("SELECT COUNT(*) FROM client_payments cp WHERE cp.kind = 'WORKS' AND NOT EXISTS "
                + "(SELECT 1 FROM payment_tranches t WHERE t.client_payment_id = cp.id)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM client_payments cp WHERE cp.kind = 'MATERIALS' AND NOT EXISTS "
                + "(SELECT 1 FROM client_proformas p WHERE p.client_payment_id = cp.id)")).isZero();
    }

    @Test
    @DisplayName("SUM(amount_gross) and SUM(paid_gross) per kind equal the legacy sums")
    void sumsPerKindPreserved() throws Exception {
        assertThat(decimal("SELECT SUM(amount_gross) FROM client_payments WHERE kind = 'WORKS'"))
                .isEqualByComparingTo(worksAmount).isEqualByComparingTo("4250.50");
        assertThat(decimal("SELECT SUM(paid_gross) FROM client_payments WHERE kind = 'WORKS'"))
                .isEqualByComparingTo(worksPaid).isEqualByComparingTo("1250.00");
        assertThat(decimal("SELECT SUM(amount_gross) FROM client_payments WHERE kind = 'MATERIALS'"))
                .isEqualByComparingTo(materialsAmount).isEqualByComparingTo("2380.25");
        assertThat(decimal("SELECT SUM(paid_gross) FROM client_payments WHERE kind = 'MATERIALS'"))
                .isEqualByComparingTo(materialsPaid).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("every base row copies status, due date, amounts, project and currency of its detail row")
    void fieldsCopiedRowByRow() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM payment_tranches t "
                + "JOIN payment_schedules s ON s.id = t.schedule_id "
                + "JOIN client_payments cp ON cp.id = t.client_payment_id "
                + "WHERE cp.kind = 'WORKS' AND cp.status = t.status AND cp.due_date = t.due_date "
                + "AND cp.amount_gross = t.amount_gross AND cp.paid_gross = t.paid_gross "
                + "AND cp.project_id = s.project_id AND cp.currency = s.currency"))
                .as("matching tranches").isEqualTo(trancheCount);
        assertThat(count("SELECT COUNT(*) FROM client_proformas p "
                + "JOIN client_payments cp ON cp.id = p.client_payment_id "
                + "WHERE cp.kind = 'MATERIALS' AND cp.status = p.status "
                + "AND cp.due_date IS NOT DISTINCT FROM p.due_date AND cp.amount_gross = p.total_gross "
                + "AND cp.paid_gross = p.paid_gross AND cp.project_id = p.project_id "
                + "AND cp.currency = p.currency AND cp.note IS NOT DISTINCT FROM p.note"))
                .as("matching proformas").isEqualTo(proformaCount);
        // stored statuses are copied as they are (no re-derivation in SQL)
        assertThat(strings("SELECT status FROM client_payments WHERE kind = 'WORKS' ORDER BY id"))
                .containsExactlyInAnyOrder("PAID", "PARTIALLY_PAID", "PENDING", "OVERDUE");
        assertThat(strings("SELECT status FROM client_payments WHERE kind = 'MATERIALS' ORDER BY id"))
                .containsExactlyInAnyOrder("DRAFT", "ISSUED", "PARTIALLY_PAID", "CANCELLED");
    }

    @Test
    @DisplayName("every tranche allocation still resolves to a tranche and its base row; sums match paid_gross")
    void allocationsResolve() throws Exception {
        assertThat(allocationCount).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM tranche_allocations a "
                + "JOIN payment_tranches t ON t.id = a.tranche_id "
                + "JOIN client_payments cp ON cp.id = t.client_payment_id AND cp.kind = 'WORKS'"))
                .isEqualTo(allocationCount);
        assertThat(count("SELECT COUNT(*) FROM tranche_allocations")).isEqualTo(allocationCount);
        // the seeded allocations settle exactly the paid amount of their tranches
        assertThat(count("SELECT COUNT(*) FROM client_payments cp "
                + "JOIN payment_tranches t ON t.client_payment_id = cp.id "
                + "WHERE cp.paid_gross > 0 AND cp.paid_gross = "
                + "(SELECT COALESCE(SUM(a.amount), 0) FROM tranche_allocations a WHERE a.tranche_id = t.id)"))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("bank links to a schedule or a proforma still resolve to a MATERIALS / WORKS base row")
    void bankLinksResolve() throws Exception {
        assertThat(clientLinkCount).isEqualTo(1L);
        assertThat(proformaLinkCount).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM bank_transaction_links l "
                + "JOIN payment_schedules s ON s.id = l.payment_schedule_id "
                + "JOIN payment_tranches t ON t.schedule_id = s.id "
                + "JOIN client_payments cp ON cp.id = t.client_payment_id AND cp.kind = 'WORKS' "
                + "WHERE l.kind = 'CLIENT_PAYMENT' AND cp.project_id = s.project_id"))
                .as("schedule link reaches its tranches' base rows").isEqualTo(3L);
        assertThat(count("SELECT COUNT(*) FROM bank_transaction_links l "
                + "JOIN client_proformas p ON p.id = l.client_proforma_id "
                + "JOIN client_payments cp ON cp.id = p.client_payment_id AND cp.kind = 'MATERIALS' "
                + "WHERE l.kind = 'PROFORMA_PAYMENT'")).isEqualTo(proformaLinkCount);
        assertThat(count("SELECT COUNT(*) FROM bank_transaction_links WHERE kind IN "
                + "('CLIENT_PAYMENT', 'PROFORMA_PAYMENT')")).isEqualTo(clientLinkCount + proformaLinkCount);
    }

    @Test
    @DisplayName("every proforma line still points at a proforma that has a MATERIALS base row")
    void proformaLinesResolve() throws Exception {
        assertThat(lineCount).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM client_proforma_lines l "
                + "JOIN client_proformas p ON p.id = l.proforma_id "
                + "JOIN client_payments cp ON cp.id = p.client_payment_id AND cp.kind = 'MATERIALS'"))
                .isEqualTo(lineCount);
    }

    @Test
    @DisplayName("constraints: NOT NULL + unique detail FKs, kind / status / amount CHECKs, temp columns dropped")
    void constraintsApplied() throws Exception {
        assertThat(isNullable("payment_tranches", "client_payment_id")).isFalse();
        assertThat(isNullable("client_proformas", "client_payment_id")).isFalse();
        assertThat(count("SELECT COUNT(*) FROM pg_constraint WHERE conname IN ("
                + "'uq_payment_tranches_client_payment', 'uq_client_proformas_client_payment', "
                + "'fk_payment_tranches_client_payment', 'fk_client_proformas_client_payment', "
                + "'ck_client_payments_kind', 'ck_client_payments_status', 'ck_client_payments_amounts')"))
                .isEqualTo(7L);
        assertThat(count("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'client_payments' "
                + "AND column_name IN ('legacy_tranche_id', 'legacy_proforma_id')")).isZero();
        // the deprecated common columns no longer require a value
        assertThat(isNullable("payment_tranches", "amount_gross")).isTrue();
        assertThat(isNullable("client_proformas", "total_gross")).isTrue();

        long projectId = count("SELECT MIN(project_id) FROM client_payments");
        assertThatThrownBy(() -> execute("INSERT INTO client_payments (project_id, kind, status, amount_gross) "
                + "VALUES (" + projectId + ", 'OTHER', 'PENDING', 1)")).hasMessageContaining("ck_client_payments_kind");
        assertThatThrownBy(() -> execute("INSERT INTO client_payments (project_id, kind, status, amount_gross) "
                + "VALUES (" + projectId + ", 'WORKS', 'DRAFT', 1)")).hasMessageContaining("ck_client_payments_status");
        assertThatThrownBy(() -> execute("INSERT INTO client_payments (project_id, kind, status, amount_gross) "
                + "VALUES (" + projectId + ", 'WORKS', 'PENDING', 0)")).hasMessageContaining("ck_client_payments_amounts");
        assertThatThrownBy(() -> execute("UPDATE payment_tranches SET client_payment_id = "
                + "(SELECT client_payment_id FROM payment_tranches ORDER BY id OFFSET 1 LIMIT 1) "
                + "WHERE id = (SELECT MIN(id) FROM payment_tranches)"))
                .hasMessageContaining("uq_payment_tranches_client_payment");
    }

    @Test
    @DisplayName("changesets 247 are recorded once; re-running them changes no rows")
    void reRunIsNoOp() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '247-%' AND exectype = 'EXECUTED'"))
                .isEqualTo(3L);
        long baseRows = count("SELECT COUNT(*) FROM client_payments");
        BigDecimal amounts = decimal("SELECT SUM(amount_gross) FROM client_payments");
        BigDecimal paid = decimal("SELECT SUM(paid_gross) FROM client_payments");

        execute("DELETE FROM databasechangelog WHERE id LIKE '247-%'");
        runChangelog();

        assertThat(count("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '247-%'")).isEqualTo(3L);
        assertThat(count("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '247-%' AND exectype <> 'MARK_RAN'"))
                .as("every 247 changeset is skipped as MARK_RAN on re-run").isZero();
        assertThat(count("SELECT COUNT(*) FROM client_payments")).isEqualTo(baseRows);
        assertThat(decimal("SELECT SUM(amount_gross) FROM client_payments")).isEqualByComparingTo(amounts);
        assertThat(decimal("SELECT SUM(paid_gross) FROM client_payments")).isEqualByComparingTo(paid);
    }

    // ------------------------------------------------------------------ legacy data

    /**
     * Two projects; schedule S1 with a PAID, a PARTIALLY_PAID and a PENDING tranche, schedule S2 with an OVERDUE
     * tranche; four proformas of different statuses; two lines; one transaction settling the first two tranches
     * (two allocations); one schedule link and one proforma link.
     */
    private void seedLegacyData() throws Exception {
        String run = Long.toString(System.nanoTime());
        long p1 = insert("projects", Map.of("name", "mig-" + run + "-a", "status", "ACTIVE"));
        long p2 = insert("projects", Map.of("name", "mig-" + run + "-b", "status", "ACTIVE"));

        long s1 = insert("payment_schedules", Map.of("project_id", p1, "currency", "PLN", "basis_gross",
                "4000.00", "source", "MANUAL", "status", "PUBLISHED"));
        long s2 = insert("payment_schedules", Map.of("project_id", p2, "currency", "EUR", "basis_gross",
                "750.50", "source", "MANUAL", "status", "PUBLISHED"));

        long t1 = tranche(s1, 1, "PAID", "2026-01-10", "1000.00", "1000.00");
        long t2 = tranche(s1, 2, "PARTIALLY_PAID", "2026-02-10", "2000.00", "250.00");
        tranche(s1, 3, "PENDING", "2026-03-10", "500.00", "0.00");
        tranche(s2, 1, "OVERDUE", "2026-01-05", "750.50", "0.00");

        insert("client_proformas", proforma(p1, "DRAFT", null, null, "300.25", "0.00", "draft note"));
        long pf2 = insert("client_proformas", proforma(p1, "ISSUED", "MIG/" + run + "/1", "2026-04-01", "1230.00",
                "0.00", null));
        insert("client_proformas", proforma(p2, "PARTIALLY_PAID", "MIG/" + run + "/2", "2026-04-15", "800.00",
                "300.00", "second"));
        insert("client_proformas", proforma(p2, "CANCELLED", null, null, "50.00", "0.00", null));

        for (int lineNo = 1; lineNo <= 2; lineNo++) {
            insert("client_proforma_lines", Map.of("proforma_id", pf2, "position_id", 1000 + lineNo,
                    "line_no", lineNo, "description", "Line " + lineNo));
        }

        long account = insert("bank_accounts", Map.of("type", "CASH", "included_in_ledger", "true"));
        long bankTx = insert("bank_transactions", Map.of("account_id", account, "channel", "CASH", "origin",
                "MANUAL", "status", "POSTED", "amount", "1250.00", "currency", "PLN", "operation_date",
                "2026-02-11", "booking_date", "2026-02-11"));
        long payTx = insert("payment_transactions", Map.of("schedule_id", s1, "channel", "MANUAL", "amount_gross",
                "1250.00", "received_at", "2026-02-11T10:00:00Z", "status", "CONFIRMED"));
        insert("tranche_allocations", Map.of("transaction_id", payTx, "tranche_id", t1, "amount", "1000.00"));
        insert("tranche_allocations", Map.of("transaction_id", payTx, "tranche_id", t2, "amount", "250.00"));
        insert("bank_transaction_links", Map.of("transaction_id", bankTx, "kind", "CLIENT_PAYMENT", "amount",
                "1250.00", "payment_schedule_id", s1, "payment_transaction_id", payTx, "source", "MANUAL"));
        insert("bank_transaction_links", Map.of("transaction_id", bankTx, "kind", "PROFORMA_PAYMENT", "amount",
                "10.00", "client_proforma_id", pf2, "source", "MANUAL"));

        trancheCount = count("SELECT COUNT(*) FROM payment_tranches");
        proformaCount = count("SELECT COUNT(*) FROM client_proformas");
        worksAmount = decimal("SELECT SUM(amount_gross) FROM payment_tranches");
        worksPaid = decimal("SELECT SUM(paid_gross) FROM payment_tranches");
        materialsAmount = decimal("SELECT SUM(total_gross) FROM client_proformas");
        materialsPaid = decimal("SELECT SUM(paid_gross) FROM client_proformas");
        allocationCount = count("SELECT COUNT(*) FROM tranche_allocations");
        clientLinkCount = count("SELECT COUNT(*) FROM bank_transaction_links WHERE kind = 'CLIENT_PAYMENT'");
        proformaLinkCount = count("SELECT COUNT(*) FROM bank_transaction_links WHERE kind = 'PROFORMA_PAYMENT'");
        lineCount = count("SELECT COUNT(*) FROM client_proforma_lines");
    }

    private long tranche(long scheduleId, int seq, String status, String due, String amount, String paid)
            throws Exception {
        return insert("payment_tranches", Map.of("schedule_id", scheduleId, "seq_no", seq, "status", status,
                "due_date", due, "amount_gross", amount, "paid_gross", paid));
    }

    private static Map<String, Object> proforma(long projectId, String status, String number, String due,
                                                String total, String paid, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project_id", projectId);
        m.put("status", status);
        m.put("currency", "PLN");
        m.put("total_gross", total);
        m.put("paid_gross", paid);
        if (number != null) {
            m.put("number", number);
        }
        if (due != null) {
            m.put("due_date", due);
        }
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    // ------------------------------------------------------------------ harness

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Applies every unrun changeset that precedes the first {@code 247-} changeset. */
    private void applyBefore247() throws Exception {
        try (Connection connection = connect()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int before = 0;
                while (before < unrun.size() && !unrun.get(before).getId().startsWith(FIRST_CHANGESET_PREFIX)) {
                    before++;
                }
                if (before > 0) {
                    liquibase.update(before, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    /**
     * Applies every unrun changeset that precedes the cleanup changeset {@code 253-}: the deprecated common
     * columns that this test compares against the base rows are dropped by 253, which has its own test
     * ({@code DropLegacyPaymentColumnsMigrationIntegrationTest}).
     */
    private void runChangelog() throws Exception {
        try (Connection connection = connect()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int before = 0;
                while (before < unrun.size() && !unrun.get(before).getId().startsWith("253-")) {
                    before++;
                }
                if (before > 0) {
                    liquibase.update(before, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    private void seedAdminUserIfPossible() throws Exception {
        long adminRoleId = count("SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)");
        if (adminRoleId == 0
                || count("SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'") > 0) {
            return;
        }
        execute("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId + ", true, 'pl', NOW(), 'test')");
    }

    /**
     * Inserts one legacy row. Columns that are NOT NULL without a default and not given in {@code values} get a
     * type-appropriate dummy; foreign keys are not enforced for the insert ({@code session_replication_role =
     * replica}), so a dummy id is enough for tables the scenario does not care about.
     */
    private long insert(String table, Map<String, ?> values) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            Map<String, String> literals = new LinkedHashMap<>();
            values.forEach((k, v) -> literals.put(k, literal(v)));
            try (ResultSet rs = st.executeQuery("SELECT column_name, data_type FROM information_schema.columns "
                    + "WHERE table_name = '" + table + "' AND is_nullable = 'NO' AND column_default IS NULL")) {
                while (rs.next()) {
                    String column = rs.getString(1);
                    if (!"id".equals(column)) {
                        literals.putIfAbsent(column, dummy(rs.getString(2)));
                    }
                }
            }
            // Rows seeded by earlier changesets may use ids the sequence has not reached yet.
            literals.putIfAbsent("id", "(SELECT COALESCE(MAX(id), 0) + 1000 FROM " + table + ")");
            List<String> cols = new ArrayList<>(literals.keySet());
            String sql = "INSERT INTO " + table + " (" + String.join(", ", cols.stream().map(
                    col -> "\"" + col + "\"").toList()) + ") VALUES ("
                    + String.join(", ", cols.stream().map(literals::get).toList()) + ") RETURNING id";
            try (ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String literal(Object v) {
        if (v instanceof Number) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private static String dummy(String dataType) {
        return switch (dataType) {
            case "bigint", "integer", "smallint", "numeric" -> "1";
            case "boolean" -> "false";
            case "date" -> "CURRENT_DATE";
            case "timestamp without time zone", "timestamp with time zone" -> "NOW()";
            case "jsonb", "json" -> "'{}'";
            default -> "'x'";
        };
    }

    private boolean tableExists(String table) throws Exception {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + table + "'") > 0;
    }

    private boolean isNullable(String table, String column) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT is_nullable FROM information_schema.columns "
                     + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " exists").isTrue();
            return "YES".equals(rs.getString(1));
        }
    }

    private long count(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private BigDecimal decimal(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            BigDecimal v = rs.getBigDecimal(1);
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    private List<String> strings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
