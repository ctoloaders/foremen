package com.foremen.dao.integration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the real FOR-05-04 {@code 082-archive-and-collapse-work-prices}
 * changeset against a real PostgreSQL instance (Testcontainers) and verifies the migrated schema
 * against the live catalog — NOT a Hibernate-generated schema.
 *
 * <p>It exercises two flows against separate databases:
 * <ul>
 *   <li><b>Full changelog</b> (fresh container): {@code work_package_prices} no longer exists,
 *       {@code work_prices} carries {@code currency_id}/{@code net_price} (NOT NULL, FK to
 *       {@code currencies}), and re-running the changelog is a no-op (Requirements 1.1, 1.4,
 *       8.4).</li>
 *   <li><b>Two-phase collapse</b> (fresh container): the changelog is applied up to (but not
 *       including) changeset {@code 082}, several per-package prices are seeded for the same
 *       work item's {@code work_prices} aggregator (mirroring the FOR-04-12b fan-out real data
 *       would have), then the remainder of the changelog runs. This drives the {@code 082a}
 *       archive + {@code 082b} MAX-backfill + {@code 082c} drop, and proves: the archive table
 *       holds the original per-package row count, the backfilled {@code net_price} equals the
 *       per-work MAX across the seeded package rows, and the winning row's currency is carried
 *       over (Requirements 1.1, 1.4, 8.4).</li>
 * </ul>
 *
 * <p>Mirrors {@code WorkPricePackagesMigrationIntegrationTest} (changeset 044/045): standalone
 * Testcontainers + real changelog, catalog assertions via {@code information_schema}, and the
 * dynamic phase-1/phase-2 split via {@code listUnrunChangeSets}.
 *
 * <p>Validates: Requirements 1.1, 1.4, 8.4
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkPriceCollapseMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /** Fully migrated database (whole changelog applied, then re-applied for idempotency). */
    private PostgreSQLContainer<?> fullDb;
    /** Database used for the controlled two-phase collapse scenario. */
    private PostgreSQLContainer<?> collapseDb;

    /** The work item whose per-package prices were seeded pre-migration in the collapse scenario. */
    private long collapseWorkItemId;
    private long collapseWorkPriceId;
    /** The MAX net_price among the seeded per-package rows (the expected backfilled net_price). */
    private BigDecimal expectedMaxNetPrice;
    /** The currency id of the winning (MAX net_price) seeded row. */
    private long expectedWinningCurrencyId;
    /** How many per-package rows were seeded for that work item (expected archive row count). */
    private int seededPackageRowCount;

    @BeforeAll
    void startContainersAndMigrate() throws Exception {
        fullDb = new PostgreSQLContainer<>("postgres:16-alpine");
        fullDb.start();
        runFullChangelog(fullDb);

        collapseDb = new PostgreSQLContainer<>("postgres:16-alpine");
        collapseDb.start();
        runCollapseScenario(collapseDb);
    }

    @AfterAll
    void stopContainers() {
        if (fullDb != null) {
            fullDb.stop();
        }
        if (collapseDb != null) {
            collapseDb.stop();
        }
    }

    // ------------------------------------------------------------------
    // Liquibase apply helpers
    // ------------------------------------------------------------------

    private Connection newConnection(PostgreSQLContainer<?> db) throws Exception {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private void runFullChangelog(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    /**
     * Applies the changelog in two phases so the {@code 082a}/{@code 082b} archive+collapse sees
     * several pre-existing per-package prices for the same work item. Phase 1 runs every changeset
     * up to (but not including) the first one whose id starts with {@code 082}; phase 2 runs the
     * rest.
     *
     * <p>Phase 1 includes changeset {@code 044}/{@code 045}, which creates {@code work_package_prices}
     * and seeds it with one row per offer package for every catalog work item. We pick one such
     * aggregator and overwrite its per-package rows with distinct, deliberately-differing net_price
     * values so the MAX-collapse resolution is unambiguous to assert against.
     */
    private void runCollapseScenario(PostgreSQLContainer<?> db) throws Exception {
        int phaseOneCount = countChangeSetsBefore082(db);

        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(phaseOneCount, "");
            }
        }

        seedDistinctPackagePrices(db);

        // Phase 2: run the remainder — 082a/082b/082c.
        runFullChangelog(db);
    }

    private int countChangeSetsBefore082(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(
                        new Contexts(), new LabelExpression());
                int count = 0;
                for (ChangeSet cs : unrun) {
                    if (cs.getId().startsWith("082")) {
                        break;
                    }
                    count++;
                }
                assertThat(count)
                        .as("there must be changesets before 082 to run in phase 1")
                        .isGreaterThan(0);
                return count;
            }
        }
    }

    /**
     * Picks one work_prices aggregator seeded by 044/045's fan-out and overwrites its
     * work_package_prices rows with distinct net_price values (and, for the winning row, a
     * distinct currency) so the MAX-collapse resolution rule is unambiguous to assert against.
     */
    private void seedDistinctPackagePrices(PostgreSQLContainer<?> db) throws Exception {
        try (Connection c = newConnection(db)) {
            long workPriceId;
            long workItemId;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, work_item_id FROM work_prices ORDER BY id LIMIT 1")) {
                assertThat(rs.next())
                        .as("the 044/045 seed must provide at least one work_prices aggregator")
                        .isTrue();
                workPriceId = rs.getLong("id");
                workItemId = rs.getLong("work_item_id");
            }
            collapseWorkPriceId = workPriceId;
            collapseWorkItemId = workItemId;

            List<Long> packagePriceIds = new java.util.ArrayList<>();
            List<Long> currencyIds = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, currency_id FROM work_package_prices WHERE work_price_id = ? ORDER BY id")) {
                ps.setLong(1, workPriceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        packagePriceIds.add(rs.getLong("id"));
                        currencyIds.add(rs.getLong("currency_id"));
                    }
                }
            }
            assertThat(packagePriceIds)
                    .as("the fanned-out work_package_prices rows for the chosen aggregator")
                    .isNotEmpty();
            seededPackageRowCount = packagePriceIds.size();

            // A second currency to prove the winning row's currency (not just any row's) is carried
            // over. Falls back to the existing currency if only one exists.
            long altCurrencyId = findAlternateCurrencyId(c, currencyIds.get(0));

            // Assign strictly increasing net_price values (100.00, 200.00, ...), the last (highest)
            // row also gets the alternate currency, so MAX unambiguously picks it.
            BigDecimal price = new BigDecimal("100.00");
            for (int i = 0; i < packagePriceIds.size(); i++) {
                boolean isWinner = (i == packagePriceIds.size() - 1);
                long currencyForRow = isWinner ? altCurrencyId : currencyIds.get(i);
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE work_package_prices SET net_price = ?, currency_id = ? WHERE id = ?")) {
                    up.setBigDecimal(1, price);
                    up.setLong(2, currencyForRow);
                    up.setLong(3, packagePriceIds.get(i));
                    up.executeUpdate();
                }
                if (isWinner) {
                    expectedMaxNetPrice = price;
                    expectedWinningCurrencyId = currencyForRow;
                }
                price = price.add(new BigDecimal("100.00"));
            }
        }
    }

    private long findAlternateCurrencyId(Connection c, long excludeId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM currencies WHERE id <> ? LIMIT 1")) {
            ps.setLong(1, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : excludeId;
            }
        }
    }

    // ------------------------------------------------------------------
    // Requirement 1.4 : work_package_prices is gone after full migration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("work_package_prices no longer exists after migration (1.4)")
    void workPackagePricesTableIsGone() throws Exception {
        assertThat(tableExists(fullDb, "work_package_prices"))
                .as("work_package_prices should be dropped by 082c").isFalse();
    }

    // ------------------------------------------------------------------
    // Requirement 1.1 : work_prices carries currency_id/net_price (NOT NULL + FK)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("work_prices carries currency_id and net_price, both NOT NULL, currency_id FK'd to currencies (1.1)")
    void workPricesCarriesCurrencyAndNetPrice() throws Exception {
        assertThat(columnExists(fullDb, "work_prices", "net_price"))
                .as("work_prices.net_price exists").isTrue();
        assertThat(columnExists(fullDb, "work_prices", "currency_id"))
                .as("work_prices.currency_id exists").isTrue();

        assertColumnNotNull(fullDb, "work_prices", "net_price");
        assertColumnNotNull(fullDb, "work_prices", "currency_id");

        assertThat(foreignKeyReferences(fullDb, "fk_work_prices_currency"))
                .as("fk_work_prices_currency -> currencies").isEqualTo("currencies");

        // No work_prices row is left without a resolved price after the backfill.
        assertThat(count(fullDb,
                "SELECT COUNT(*) FROM work_prices WHERE net_price IS NULL OR currency_id IS NULL"))
                .as("every work_prices row must have net_price/currency_id backfilled")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 8.4 : archive table holds the original per-package row count
    // ------------------------------------------------------------------

    @Test
    @DisplayName("work_package_prices_archive holds the original per-package row count (8.4)")
    void archiveHoldsOriginalRowCount() throws Exception {
        long archivedForWork = count(collapseDb,
                "SELECT COUNT(*) FROM work_package_prices_archive WHERE work_price_id = "
                        + collapseWorkPriceId);
        assertThat(archivedForWork)
                .as("archive must hold exactly the seeded per-package row count for this work item")
                .isEqualTo(seededPackageRowCount);

        // Every archived row records the MAX_NETPRICE resolution rule (8.3).
        long withoutRule = count(collapseDb,
                "SELECT COUNT(*) FROM work_package_prices_archive "
                        + "WHERE work_price_id = " + collapseWorkPriceId
                        + " AND resolution_rule <> 'MAX_NETPRICE'");
        assertThat(withoutRule)
                .as("every archived row must record the MAX_NETPRICE resolution rule")
                .isZero();

        // archived_at is populated (auditable snapshot, not a silent delete).
        long withoutArchivedAt = count(collapseDb,
                "SELECT COUNT(*) FROM work_package_prices_archive "
                        + "WHERE work_price_id = " + collapseWorkPriceId
                        + " AND archived_at IS NULL");
        assertThat(withoutArchivedAt).as("archived_at must be populated for every archived row").isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 1.4 / 8.3 : backfilled net_price equals the per-work MAX
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Backfilled net_price equals the per-work MAX across the seeded package prices, currency from the winning row (1.4, 8.3)")
    void backfilledNetPriceEqualsMax() throws Exception {
        BigDecimal actualNetPrice;
        long actualCurrencyId;
        try (Connection c = newConnection(collapseDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT net_price, currency_id FROM work_prices WHERE id = ?")) {
            ps.setLong(1, collapseWorkPriceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the migrated work_prices aggregator must still exist").isTrue();
                actualNetPrice = rs.getBigDecimal("net_price");
                actualCurrencyId = rs.getLong("currency_id");
            }
        }

        assertThat(actualNetPrice)
                .as("backfilled net_price must equal the MAX seeded net_price (%s)", expectedMaxNetPrice)
                .isEqualByComparingTo(expectedMaxNetPrice);
        assertThat(actualCurrencyId)
                .as("backfilled currency_id must come from the winning (MAX net_price) row")
                .isEqualTo(expectedWinningCurrencyId);
    }

    // ------------------------------------------------------------------
    // Requirement 8.4 : re-running the changelog is a no-op (idempotent)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the changelog changes nothing for the collapse (idempotent, no-op) (8.4)")
    void reRunningChangelogIsANoOpOnFullDb() throws Exception {
        long changeSetsBefore = count(fullDb, "SELECT COUNT(*) FROM databasechangelog");
        long workPricesBefore = count(fullDb, "SELECT COUNT(*) FROM work_prices");

        runFullChangelog(fullDb);

        assertThat(count(fullDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(fullDb, "SELECT COUNT(*) FROM work_prices"))
                .as("no duplicate aggregators on re-run")
                .isEqualTo(workPricesBefore);
        assertThat(tableExists(fullDb, "work_package_prices"))
                .as("work_package_prices must remain dropped after re-run").isFalse();
    }

    @Test
    @DisplayName("Re-running the changelog on the collapsed DB leaves counts/values unchanged (idempotent, no-op) (8.4)")
    void reRunningChangelogIsANoOpOnCollapseDb() throws Exception {
        long changeSetsBefore = count(collapseDb, "SELECT COUNT(*) FROM databasechangelog");
        long archiveCountBefore = count(collapseDb,
                "SELECT COUNT(*) FROM work_package_prices_archive WHERE work_price_id = "
                        + collapseWorkPriceId);
        BigDecimal netPriceBefore;
        try (Connection c = newConnection(collapseDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT net_price FROM work_prices WHERE id = ?")) {
            ps.setLong(1, collapseWorkPriceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                netPriceBefore = rs.getBigDecimal("net_price");
            }
        }

        runFullChangelog(collapseDb);

        assertThat(count(collapseDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(collapseDb,
                "SELECT COUNT(*) FROM work_package_prices_archive WHERE work_price_id = "
                        + collapseWorkPriceId))
                .as("no duplicate archive rows on re-run")
                .isEqualTo(archiveCountBefore);

        BigDecimal netPriceAfter;
        try (Connection c = newConnection(collapseDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT net_price FROM work_prices WHERE id = ?")) {
            ps.setLong(1, collapseWorkPriceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                netPriceAfter = rs.getBigDecimal("net_price");
            }
        }
        assertThat(netPriceAfter)
                .as("net_price must be unchanged by the re-run")
                .isEqualByComparingTo(netPriceBefore);
    }

    // ------------------------------------------------------------------
    // information_schema helpers
    // ------------------------------------------------------------------

    private boolean tableExists(PostgreSQLContainer<?> db, String table) throws Exception {
        String sql = "SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(PostgreSQLContainer<?> db, String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void assertColumnNotNull(PostgreSQLContainer<?> db, String table, String column) throws Exception {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("column %s.%s should exist", table, column).isTrue();
                assertThat(rs.getString("is_nullable"))
                        .as("%s.%s NOT NULL", table, column).isEqualTo("NO");
            }
        }
    }

    /** @return the referenced table name for a named FK, or null if the FK does not exist. */
    private String foreignKeyReferences(PostgreSQLContainer<?> db, String constraintName) throws Exception {
        String sql = "SELECT ccu.table_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON tc.constraint_name = ccu.constraint_name "
                + " AND tc.table_schema = ccu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                + "  AND tc.table_schema = 'public' AND tc.constraint_name = ?";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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
}
