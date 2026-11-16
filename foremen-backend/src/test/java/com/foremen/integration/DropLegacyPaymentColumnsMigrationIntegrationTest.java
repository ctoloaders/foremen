package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * FOR-11-06 task 9.2 (R1.2, R14.1): changeset {@code 253} drops the deprecated common columns of
 * {@code payment_tranches} and {@code client_proformas} that {@code client_payments} owns since 247. The test
 * applies every changeset before 253, seeds one tranche and one proforma with their base rows, applies 253,
 * proves the columns are gone while the base data and detail-only columns survive, then removes the 253
 * bookkeeping rows and re-applies the changelog to prove the changesets are skipped (MARK_RAN) and harmless.
 *
 * <p>Repeatability: a fresh container per JVM run; seeded rows use run-unique names.
 *
 * <p>Validates: Requirements 1.2, 14.1
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-11-06-delivery-ux-refinements, task 9.2: changeset 253 drops the legacy payment columns")
class DropLegacyPaymentColumnsMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String CLEANUP_PREFIX = "253-";

    private static final List<String> TRANCHE_COLUMNS = List.of("amount_gross", "paid_gross", "due_date", "status");
    private static final List<String> PROFORMA_COLUMNS =
            List.of("status", "due_date", "total_gross", "paid_gross", "currency", "note");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private long trancheId;
    private long proformaId;
    private boolean legacyColumnsPresentBefore;
    private long executedCleanupChangeSets;

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try {
            applyBeforeCleanup();
        } catch (Exception first) {
            // Changeset 160 needs an ADMIN user on an empty DB: seed one and resume.
            seedAdminUserIfPossible();
            applyBeforeCleanup();
        }
        legacyColumnsPresentBefore = columnsPresent("payment_tranches", TRANCHE_COLUMNS) == TRANCHE_COLUMNS.size()
                && columnsPresent("client_proformas", PROFORMA_COLUMNS) == PROFORMA_COLUMNS.size();
        seed();
        applyAll();
        executedCleanupChangeSets =
                count("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '253-%' AND exectype = 'EXECUTED'");
    }

    @Test
    @DisplayName("253 removes every deprecated column and keeps the detail-only columns")
    void legacyColumnsDropped() throws Exception {
        assertThat(legacyColumnsPresentBefore).as("legacy columns existed before 253").isTrue();
        assertThat(columnsPresent("payment_tranches", TRANCHE_COLUMNS)).isZero();
        assertThat(columnsPresent("client_proformas", PROFORMA_COLUMNS)).isZero();
        assertThat(columnsPresent("payment_tranches", List.of("client_payment_id", "schedule_id", "seq_no")))
                .isEqualTo(3L);
        assertThat(columnsPresent("client_proformas",
                List.of("client_payment_id", "project_id", "number", "issue_date", "total_net", "total_vat")))
                .isEqualTo(6L);
        assertThat(executedCleanupChangeSets).isEqualTo(2L);
    }

    @Test
    @DisplayName("the base rows and the detail links survive the cleanup")
    void baseDataIntact() throws Exception {
        assertThat(decimal("SELECT amount_gross FROM client_payments cp JOIN payment_tranches t "
                + "ON t.client_payment_id = cp.id WHERE t.id = " + trancheId)).isEqualByComparingTo("1500.00");
        assertThat(decimal("SELECT paid_gross FROM client_payments cp JOIN client_proformas p "
                + "ON p.client_payment_id = cp.id WHERE p.id = " + proformaId)).isEqualByComparingTo("40.00");
        assertThat(count("SELECT COUNT(*) FROM client_payments")).isEqualTo(2L);
    }

    @Test
    @DisplayName("re-running 253 is a no-op: both changesets are MARK_RAN and nothing changes")
    void reRunIsNoOp() throws Exception {
        execute("DELETE FROM databasechangelog WHERE id LIKE '253-%'");
        applyAll();
        assertThat(count("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '253-%'")).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '253-%' AND exectype <> 'MARK_RAN'"))
                .isZero();
        assertThat(columnsPresent("payment_tranches", TRANCHE_COLUMNS)).isZero();
        assertThat(columnsPresent("client_proformas", PROFORMA_COLUMNS)).isZero();
        assertThat(count("SELECT COUNT(*) FROM client_payments")).isEqualTo(2L);
    }

    // ------------------------------------------------------------------ data

    private void seed() throws Exception {
        String run = Long.toString(System.nanoTime());
        long project = insert("projects", Map.of("name", "drop253-" + run, "status", "ACTIVE"));
        long schedule = insert("payment_schedules", Map.of("project_id", project, "currency", "PLN", "basis_gross",
                "1500.00", "source", "MANUAL", "status", "PUBLISHED"));
        long worksPayment = insert("client_payments", Map.of("project_id", project, "kind", "WORKS", "status",
                "PENDING", "amount_gross", "1500.00", "paid_gross", "0.00", "due_date", "2026-05-01"));
        long materialsPayment = insert("client_payments", Map.of("project_id", project, "kind", "MATERIALS",
                "status", "PARTIALLY_PAID", "amount_gross", "100.00", "paid_gross", "40.00"));
        trancheId = insert("payment_tranches", Map.of("schedule_id", schedule, "seq_no", 1,
                "client_payment_id", worksPayment, "status", "PENDING", "amount_gross", "1500.00",
                "paid_gross", "0.00", "due_date", "2026-05-01"));
        proformaId = insert("client_proformas", Map.of("project_id", project, "client_payment_id", materialsPayment,
                "status", "PARTIALLY_PAID", "currency", "PLN", "total_gross", "100.00", "paid_gross", "40.00"));
    }

    // ------------------------------------------------------------------ harness

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Applies every unrun changeset that precedes the first {@code 253-} changeset. */
    private void applyBeforeCleanup() throws Exception {
        try (Connection connection = connect()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int before = 0;
                while (before < unrun.size() && !unrun.get(before).getId().startsWith(CLEANUP_PREFIX)) {
                    before++;
                }
                if (before > 0) {
                    liquibase.update(before, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    private void applyAll() throws Exception {
        try (Connection connection = connect()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
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
                + "VALUES ('drop253-admin', 'drop253-admin@example.com', " + adminRoleId + ", true, 'pl', NOW(), 'test')");
    }

    /** Inserts a row; NOT NULL columns without default that are not given get a dummy; FKs are not enforced. */
    private long insert(String table, Map<String, ?> values) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            Map<String, String> literals = new LinkedHashMap<>();
            values.forEach((k, v) -> literals.put(k, v instanceof Number ? v.toString()
                    : "'" + v.toString().replace("'", "''") + "'"));
            try (ResultSet rs = st.executeQuery("SELECT column_name, data_type FROM information_schema.columns "
                    + "WHERE table_name = '" + table + "' AND is_nullable = 'NO' AND column_default IS NULL")) {
                while (rs.next()) {
                    String column = rs.getString(1);
                    if (!"id".equals(column)) {
                        literals.putIfAbsent(column, dummy(rs.getString(2)));
                    }
                }
            }
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

    private long columnsPresent(String table, List<String> columns) throws Exception {
        String in = String.join(", ", columns.stream().map(c -> "'" + c + "'").toList());
        return count("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = '" + table
                + "' AND column_name IN (" + in + ")");
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

    private void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
