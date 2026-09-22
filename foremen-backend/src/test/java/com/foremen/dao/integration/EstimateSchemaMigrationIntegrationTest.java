package com.foremen.dao.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the FOR-05-03 estimate schema introduced by changesets
 * {@code 076}–{@code 080}, as amended by the FOR-05-04 retirement (changeset {@code 083}) of the
 * per-package project price vertical:
 * <ul>
 *     <li>the three still-live estimate tables exist after migrate: {@code estimates},
 *         {@code estimate_lines}, {@code estimate_line_room_qty} (Requirements 1.1, 2.4);</li>
 *     <li>the {@code estimates.project_id} UNIQUE constraint enforcing the 1:1 invariant exists
 *         (Requirement 1.1);</li>
 *     <li>the {@code estimate_lines.work_price_id} {@code ON DELETE SET NULL} provenance FK
 *         exists (Requirement 2.4);</li>
 *     <li>{@code estimate_line_package_prices} and {@code estimate_line_package_price_history}
 *         (FOR-05-03) no longer exist — they were archived and dropped by FOR-05-04 changeset
 *         {@code 083} (Requirements 8.2, 8.6) — while their {@code *_archive} counterparts do
 *         exist and hold the archived rows (Requirement 8.4);</li>
 *     <li>re-running the changelog is a no-op: every changeset is guarded by
 *         {@code tableExists} + {@code MARK_RAN}, so a second application changes no schema.</li>
 * </ul>
 *
 * <p>A second, independent database ({@code archiveDb}) drives a controlled two-phase scenario
 * (mirroring {@code WorkPriceCollapseMigrationIntegrationTest}'s {@code collapseDb}): the
 * changelog is applied up to (but not including) changeset {@code 083}, a handful of rows are
 * seeded directly into the still-live {@code estimate_line_package_prices} /
 * {@code estimate_line_package_price_history} tables, then the remainder of the changelog runs.
 * This proves the {@code 083a}/{@code 083b} archive step preserves the exact seeded row COUNT
 * (Requirement 8.4) and that a second full changelog run does not duplicate the archived rows
 * (idempotent re-migrate, Requirement 8.4).
 *
 * <p>Mirrors the {@code WorkCatalogResourceSeedIntegrationTest} convention: run the real changelog
 * against a real database via raw Liquibase and assert against the live schema through
 * {@code information_schema} using raw JDBC.
 *
 * <p>Validates: Requirements 1.1, 2.4, 8.2, 8.4, 8.6
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EstimateSchemaMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> ESTIMATE_TABLES = List.of(
            "estimates",
            "estimate_lines",
            "estimate_line_room_qty");

    private static final List<String> RETIRED_ELPP_TABLES = List.of(
            "estimate_line_package_prices",
            "estimate_line_package_price_history");

    private static final List<String> ELPP_ARCHIVE_TABLES = List.of(
            "estimate_line_package_prices_archive",
            "estimate_line_package_price_history_archive");

    private static final int SEEDED_PACKAGE_PRICE_ROWS = 3;
    private static final int SEEDED_HISTORY_ROWS_PER_PRICE = 2;

    private PostgreSQLContainer<?> postgres;

    /** Second database used for the pre-083 seed / archive-row-count / no-double-insert scenario. */
    private PostgreSQLContainer<?> archiveDb;

    /** IDs of the estimate_line_package_prices rows seeded before changeset 083 runs. */
    private List<Long> seededPackagePriceIds;
    /** IDs of the estimate_line_package_price_history rows seeded before changeset 083 runs. */
    private List<Long> seededHistoryIds;

    @BeforeAll
    void startContainerAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        runLiquibase();

        archiveDb = new PostgreSQLContainer<>("postgres:16-alpine");
        archiveDb.start();
        runArchiveRowCountScenario();
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
        if (archiveDb != null) {
            archiveDb.stop();
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection newConnection(PostgreSQLContainer<?> db) throws Exception {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private void runLiquibase() throws Exception {
        runLiquibase(postgres);
    }

    private void runLiquibase(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    // ------------------------------------------------------------------
    // archiveDb : pre-083 seed -> archive row count -> idempotent re-migrate
    // ------------------------------------------------------------------

    /**
     * Applies the changelog in two phases against {@code archiveDb}: phase 1 runs every
     * changeset up to (but not including) the first one whose id starts with {@code 083}
     * (i.e. through {@code 082}, leaving {@code estimate_line_package_prices} /
     * {@code estimate_line_package_price_history} live); a handful of rows are then seeded
     * directly into those two tables via raw JDBC; phase 2 runs the remainder of the changelog,
     * driving {@code 083a}-{@code 083d}.
     */
    private void runArchiveRowCountScenario() throws Exception {
        int phaseOneCount = countChangeSetsBefore083(archiveDb);

        try (Connection connection = newConnection(archiveDb)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(phaseOneCount, "");
            }
        }

        seedElppRowsPreDrop(archiveDb);

        // Phase 2: run the remainder — 083a/083b/083c/083d.
        runLiquibase(archiveDb);
    }

    private int countChangeSetsBefore083(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(
                        new Contexts(), new LabelExpression());
                int count = 0;
                for (ChangeSet cs : unrun) {
                    if (cs.getId().startsWith("083")) {
                        break;
                    }
                    count++;
                }
                assertThat(count)
                        .as("there must be changesets before 083 to run in phase 1")
                        .isGreaterThan(0);
                return count;
            }
        }
    }

    /**
     * Seeds the minimal FK-chain (measurement unit, work category, work item, offer package,
     * currency, project, estimate, estimate line) plus {@code SEEDED_PACKAGE_PRICE_ROWS} rows of
     * {@code estimate_line_package_prices} and {@code SEEDED_HISTORY_ROWS_PER_PRICE} history rows
     * per price into {@code estimate_line_package_price_history}, directly via JDBC, while both
     * tables are still live (i.e. before changeset 083 runs).
     */
    private void seedElppRowsPreDrop(PostgreSQLContainer<?> db) throws Exception {
        try (Connection c = newConnection(db)) {
            long unitId = insertReturningId(c,
                    "INSERT INTO measurement_units (code, name_ru, name_pl) "
                            + "VALUES ('ELPP_TEST_UNIT', 'test unit', 'test unit') RETURNING id");
            long categoryId = insertReturningId(c,
                    "INSERT INTO work_categories (code, order_no, name_ru, name_pl) "
                            + "VALUES ('ELPP_TEST_CAT', 999, 'test cat', 'test cat') RETURNING id");
            long workItemId = insertReturningId(c,
                    "INSERT INTO work_items (work_category_id, unit_id, name_ru, name_pl) "
                            + "VALUES (" + categoryId + ", " + unitId + ", "
                            + "'test work item', 'test work item') RETURNING id");
            long currencyId = insertReturningId(c,
                    "INSERT INTO currencies (code, symbol, name_ru, name_pl) "
                            + "VALUES ('ZZT', 'Z', 'test currency', 'test currency') RETURNING id");
            long projectId = insertReturningId(c,
                    "INSERT INTO projects (name) VALUES ('ELPP archive-count test project') "
                            + "RETURNING id");
            long estimateId = insertReturningId(c,
                    "INSERT INTO estimates (project_id, currency_id) VALUES ("
                            + projectId + ", " + currencyId + ") RETURNING id");
            long lineId = insertReturningId(c,
                    "INSERT INTO estimate_lines (estimate_id, work_item_id, unit_id, unit_price) "
                            + "VALUES (" + estimateId + ", " + workItemId + ", " + unitId
                            + ", 10.00) RETURNING id");

            seededPackagePriceIds = new ArrayList<>();
            seededHistoryIds = new ArrayList<>();
            for (int i = 0; i < SEEDED_PACKAGE_PRICE_ROWS; i++) {
                // A distinct offer_package per row: uk_elpp_line_package is UNIQUE on
                // (line_id, offer_package_id), so reusing one package for the same line would
                // collide on the second seeded row.
                long offerPackageId = insertReturningId(c,
                        "INSERT INTO offer_packages (code, order_no, name_ru, name_pl) "
                                + "VALUES ('ELPP_TEST_PKG_" + i + "', " + (999 + i) + ", "
                                + "'test pkg " + i + "', 'test pkg " + i + "') RETURNING id");
                long priceId = insertReturningId(c,
                        "INSERT INTO estimate_line_package_prices "
                                + "(line_id, offer_package_id, original_unit_price, unit_price, unpriced) "
                                + "VALUES (" + lineId + ", " + offerPackageId + ", "
                                + "100.00, 100.00, false) RETURNING id");
                seededPackagePriceIds.add(priceId);

                for (int h = 0; h < SEEDED_HISTORY_ROWS_PER_PRICE; h++) {
                    long historyId = insertReturningId(c,
                            "INSERT INTO estimate_line_package_price_history "
                                    + "(package_price_id, original_unit_price, unit_price, changed_by) "
                                    + "VALUES (" + priceId + ", 100.00, 100.00, 'test') RETURNING id");
                    seededHistoryIds.add(historyId);
                }
            }
        }

        assertThat(seededPackagePriceIds).hasSize(SEEDED_PACKAGE_PRICE_ROWS);
        assertThat(seededHistoryIds)
                .hasSize(SEEDED_PACKAGE_PRICE_ROWS * SEEDED_HISTORY_ROWS_PER_PRICE);
    }

    private long insertReturningId(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("INSERT ... RETURNING id must return a row").isTrue();
            return rs.getLong(1);
        }
    }

    private long count(PostgreSQLContainer<?> db, String sql) throws Exception {
        try (Connection c = newConnection(db);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // --- Requirements 1.1, 2.4 : the three still-live estimate tables exist after migrate ---
    @Test
    @DisplayName("Changesets 076-080 create the three live estimate tables")
    void createsTheLiveEstimateTables() throws Exception {
        for (String table : ESTIMATE_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s exists after migrate", table)
                    .isTrue();
        }
    }

    // --- Requirements 8.2, 8.4, 8.6 : the ELPP vertical is retired, archived first ---
    @Test
    @DisplayName("FOR-05-04 changeset 083 drops the ELPP tables and archives their rows")
    void retiresElppVerticalWithArchive() throws Exception {
        for (String table : RETIRED_ELPP_TABLES) {
            assertThat(tableExists(table))
                    .as("retired table %s no longer exists after migrate", table)
                    .isFalse();
        }
        for (String table : ELPP_ARCHIVE_TABLES) {
            assertThat(tableExists(table))
                    .as("archive table %s exists after migrate", table)
                    .isTrue();
        }
    }

    // --- Requirement 1.1 : estimates.project_id UNIQUE enforces the 1:1 invariant ---
    @Test
    @DisplayName("estimates.project_id has a UNIQUE constraint (1:1 invariant)")
    void estimatesProjectIdIsUnique() throws Exception {
        assertThat(uniqueConstraintColumns("estimates"))
                .as("a UNIQUE constraint on estimates covers exactly [project_id]")
                .contains(List.of("project_id"));
    }

    // --- Requirement 2.4 : estimate_lines.work_price_id provenance FK is ON DELETE SET NULL ---
    @Test
    @DisplayName("estimate_lines.work_price_id provenance FK is ON DELETE SET NULL")
    void estimateLinesWorkPriceFkIsSetNull() throws Exception {
        assertThat(deleteRuleForForeignKeyColumn("estimate_lines", "work_price_id"))
                .as("estimate_lines.work_price_id FK delete rule")
                .isEqualTo("SET NULL");
    }

    // --- Re-migrate is a no-op : tables + constraints unchanged after a second changelog run ---
    @Test
    @DisplayName("Re-running the changelog is a no-op for the estimate schema")
    void reRunningChangelogIsNoOp() throws Exception {
        // Re-apply the entire changelog. Every changeset is guarded by tableExists/MARK_RAN
        // (or the archive-before-drop preconditions in 083), so a second application makes no
        // further schema change.
        runLiquibase();

        for (String table : ESTIMATE_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s still exists after re-migrate", table)
                    .isTrue();
        }
        for (String table : RETIRED_ELPP_TABLES) {
            assertThat(tableExists(table))
                    .as("retired table %s still absent after re-migrate", table)
                    .isFalse();
        }
        for (String table : ELPP_ARCHIVE_TABLES) {
            assertThat(tableExists(table))
                    .as("archive table %s still exists after re-migrate", table)
                    .isTrue();
        }
        assertThat(uniqueConstraintColumns("estimates"))
                .as("estimates UNIQUE constraints unchanged after re-migrate")
                .contains(List.of("project_id"));
        assertThat(deleteRuleForForeignKeyColumn("estimate_lines", "work_price_id"))
                .as("estimate_lines.work_price_id FK delete rule unchanged after re-migrate")
                .isEqualTo("SET NULL");
    }

    // --- Requirement 8.4 : archive tables hold exactly the seeded pre-drop row count ---
    @Test
    @DisplayName("Archive tables hold exactly the seeded pre-drop row count, by original_id (8.4)")
    void archiveTablesHoldExactSeededRowCount() throws Exception {
        assertThat(count(archiveDb, "SELECT COUNT(*) FROM estimate_line_package_prices_archive"))
                .as("estimate_line_package_prices_archive row count must equal the seeded count")
                .isEqualTo(SEEDED_PACKAGE_PRICE_ROWS);
        assertThat(count(archiveDb,
                "SELECT COUNT(*) FROM estimate_line_package_price_history_archive"))
                .as("estimate_line_package_price_history_archive row count must equal the seeded count")
                .isEqualTo(SEEDED_PACKAGE_PRICE_ROWS * SEEDED_HISTORY_ROWS_PER_PRICE);

        // Every seeded price id has exactly one corresponding archive row (no loss, no
        // duplication), matched by original_id.
        for (Long priceId : seededPackagePriceIds) {
            assertThat(count(archiveDb,
                    "SELECT COUNT(*) FROM estimate_line_package_prices_archive "
                            + "WHERE original_id = " + priceId))
                    .as("archive must hold exactly one row for original_id=%d", priceId)
                    .isEqualTo(1);
        }
        for (Long historyId : seededHistoryIds) {
            assertThat(count(archiveDb,
                    "SELECT COUNT(*) FROM estimate_line_package_price_history_archive "
                            + "WHERE original_id = " + historyId))
                    .as("history archive must hold exactly one row for original_id=%d", historyId)
                    .isEqualTo(1);
        }

        // The tables were in fact dropped (rows only live in the archives now).
        assertThat(tableExists(archiveDb, "estimate_line_package_prices")).isFalse();
        assertThat(tableExists(archiveDb, "estimate_line_package_price_history")).isFalse();
    }

    // --- Requirement 8.4 : re-migrating a second time does not double-insert archive rows ---
    @Test
    @DisplayName("Re-running the full changelog does not duplicate archive rows (idempotent re-migrate) (8.4)")
    void reRunningChangelogDoesNotDuplicateArchiveRows() throws Exception {
        long priceArchiveBefore = count(archiveDb,
                "SELECT COUNT(*) FROM estimate_line_package_prices_archive");
        long historyArchiveBefore = count(archiveDb,
                "SELECT COUNT(*) FROM estimate_line_package_price_history_archive");

        runLiquibase(archiveDb);

        assertThat(count(archiveDb, "SELECT COUNT(*) FROM estimate_line_package_prices_archive"))
                .as("no duplicate archive rows for estimate_line_package_prices on re-run")
                .isEqualTo(priceArchiveBefore);
        assertThat(count(archiveDb,
                "SELECT COUNT(*) FROM estimate_line_package_price_history_archive"))
                .as("no duplicate archive rows for estimate_line_package_price_history on re-run")
                .isEqualTo(historyArchiveBefore);

        // Still exactly the originally seeded counts, not just "unchanged versus itself".
        assertThat(priceArchiveBefore).isEqualTo(SEEDED_PACKAGE_PRICE_ROWS);
        assertThat(historyArchiveBefore)
                .isEqualTo(SEEDED_PACKAGE_PRICE_ROWS * SEEDED_HISTORY_ROWS_PER_PRICE);
    }

    // ---------------------------------------------------------------------
    // information_schema query helpers
    // ---------------------------------------------------------------------

    /** Whether a base table with the given name exists in the public schema (default database). */
    private boolean tableExists(String tableName) throws Exception {
        return tableExists(postgres, tableName);
    }

    /** Whether a base table with the given name exists in the public schema of {@code db}. */
    private boolean tableExists(PostgreSQLContainer<?> db, String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ? AND table_type = 'BASE TABLE'";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * All UNIQUE constraints on the given table, each as the ordered list of its column names.
     * Reads {@code table_constraints} (constraint_type = 'UNIQUE') joined to
     * {@code key_column_usage} ordered by {@code ordinal_position}.
     */
    private List<List<String>> uniqueConstraintColumns(String tableName) throws Exception {
        String constraintsSql = "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'UNIQUE'";
        List<String> constraintNames = new ArrayList<>();
        try (Connection c = newConnection();
                PreparedStatement ps = c.prepareStatement(constraintsSql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraintNames.add(rs.getString(1));
                }
            }
        }

        List<List<String>> result = new ArrayList<>();
        String columnsSql = "SELECT column_name FROM information_schema.key_column_usage "
                + "WHERE table_schema = 'public' AND constraint_name = ? "
                + "ORDER BY ordinal_position";
        for (String constraintName : constraintNames) {
            try (Connection c = newConnection();
                    PreparedStatement ps = c.prepareStatement(columnsSql)) {
                ps.setString(1, constraintName);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> columns = new ArrayList<>();
                    while (rs.next()) {
                        columns.add(rs.getString(1));
                    }
                    result.add(columns);
                }
            }
        }
        return result;
    }

    /**
     * The {@code ON DELETE} rule of the foreign-key constraint on the given (table, column),
     * e.g. {@code "SET NULL"}, {@code "CASCADE"}, {@code "RESTRICT"} / {@code "NO ACTION"}.
     * Joins {@code key_column_usage} (to find the FK constraint on the column) with
     * {@code referential_constraints} (which carries {@code delete_rule}).
     */
    private String deleteRuleForForeignKeyColumn(String tableName, String columnName)
            throws Exception {
        String sql = "SELECT rc.delete_rule "
                + "FROM information_schema.referential_constraints rc "
                + "JOIN information_schema.table_constraints tc "
                + "  ON rc.constraint_name = tc.constraint_name "
                + " AND rc.constraint_schema = tc.constraint_schema "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON rc.constraint_name = kcu.constraint_name "
                + " AND rc.constraint_schema = kcu.constraint_schema "
                + "WHERE tc.table_schema = 'public' "
                + "  AND tc.table_name = ? "
                + "  AND tc.constraint_type = 'FOREIGN KEY' "
                + "  AND kcu.column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
            }
        }
    }
}
