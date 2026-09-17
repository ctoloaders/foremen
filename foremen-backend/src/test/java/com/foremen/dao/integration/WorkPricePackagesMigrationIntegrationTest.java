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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the real FOR-04-12b Liquibase migration/seed changesets
 * ({@code 044-migrate-work-prices-to-packages} and {@code 045-seed-work-package-prices}) against a
 * real PostgreSQL instance (Testcontainers) and verifies the migrated schema against the live
 * catalog — NOT a Hibernate-generated schema.
 *
 * <p>It exercises two flows against separate databases:
 * <ul>
 *   <li><b>Full changelog</b> (fresh container): the {@code work_package_prices} table exists with
 *       its three FKs; the two owner FKs on {@code work_price_id} and {@code offer_package_id} plus
 *       the {@code work_prices.work_item_id} FK are {@code ON DELETE CASCADE} while
 *       {@code currency_id} is {@code RESTRICT} (read from the PostgreSQL catalog
 *       {@code pg_constraint.confdeltype}); the moved price columns
 *       ({@code valid_from}/{@code valid_to}/{@code net_price}/{@code currency_id}) are absent from
 *       {@code work_prices}; the {@code (work_price_id, offer_package_id)} unique constraint exists;
 *       package prices are seeded (COUNT &gt; 0, all currency PLN); and re-running the changelog is a
 *       no-op (Requirements 8.4, 2.1, 2.3, 2.4, 6.7).</li>
 *   <li><b>Two-phase fan-out</b> (fresh container): the changelog is applied up to (but not
 *       including) changeset {@code 044}, a legacy "current" {@code work_prices} row
 *       ({@code valid_to IS NULL}, carrying {@code net_price}/{@code currency_id}) is inserted, then
 *       the remainder of the changelog runs. This drives the {@code 044c} fan-out
 *       ({@code CROSS JOIN offer_packages}) and proves it created exactly one
 *       {@code work_package_prices} row per seeded offer package for that pre-existing current price,
 *       copying its {@code net_price}/{@code currency_id} (Requirement 2.3).</li>
 * </ul>
 *
 * <p>Mirrors {@code ProjectMemberMigrationIntegrationTest} / {@code OtpTokenMigrationIntegrationTest}
 * (standalone Testcontainers + real changelog, catalog assertions via {@code information_schema} /
 * {@code pg_constraint}).
 *
 * <p>Validates: Requirements 8.4, 2.1, 2.3, 2.4, 6.7
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkPricePackagesMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /** Fully migrated database (whole changelog applied, then re-applied for idempotency). */
    private PostgreSQLContainer<?> fullDb;
    /** Database used for the controlled two-phase fan-out scenario. */
    private PostgreSQLContainer<?> fanoutDb;

    /** A pre-existing current work_prices row captured in phase 1, before 044 drops its price columns. */
    private long legacyWorkItemId;
    private java.math.BigDecimal legacyNetPrice;
    private long legacyCurrencyId;

    @BeforeAll
    void startContainersAndMigrate() throws Exception {
        fullDb = new PostgreSQLContainer<>("postgres:16-alpine");
        fullDb.start();
        runFullChangelog(fullDb);

        fanoutDb = new PostgreSQLContainer<>("postgres:16-alpine");
        fanoutDb.start();
        runFanoutScenario(fanoutDb);
    }

    @AfterAll
    void stopContainers() {
        if (fullDb != null) {
            fullDb.stop();
        }
        if (fanoutDb != null) {
            fanoutDb.stop();
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
     * Applies the changelog in two phases so the {@code 044c} fan-out sees a pre-existing "current"
     * work_prices row. Phase 1 runs every changeset up to (but not including) the first one whose id
     * starts with {@code 044}; phase 2 runs the rest. The phase-1 count is computed dynamically from
     * the changelog so it stays correct if earlier changesets are added.
     *
     * <p>Phase 1 includes the FOR-04-12 {@code 039} seed, which populates {@code work_prices} with
     * legacy single-price current rows (with {@code net_price}/{@code currency_id}). We capture one of
     * those rows here — before {@code 044f} drops the price columns — so the fan-out assertion can
     * verify {@code 044c} copied its {@code net_price}/{@code currency_id} into one
     * {@code work_package_prices} row per offer package.
     */
    private void runFanoutScenario(PostgreSQLContainer<?> db) throws Exception {
        int phaseOneCount = countChangeSetsBefore044(db);

        // Phase 1: everything through changeset 043 (creates work_prices with its price columns and
        // seeds work_items / offer_packages / PLN currency and the legacy 039 work_prices rows), but
        // not 044/045.
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(phaseOneCount, "");
            }
        }

        // Capture one pre-existing current price row (valid_to IS NULL) with its price + currency,
        // before 044f drops those columns from work_prices.
        captureLegacyCurrentWorkPrice(db);

        // Phase 2: run the remainder — 044 (incl. the 044c fan-out) and 045.
        runFullChangelog(db);
    }

    private int countChangeSetsBefore044(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(
                        new Contexts(), new LabelExpression());
                int count = 0;
                for (ChangeSet cs : unrun) {
                    if (cs.getId().startsWith("044")) {
                        break;
                    }
                    count++;
                }
                assertThat(count)
                        .as("there must be changesets before 044 to run in phase 1")
                        .isGreaterThan(0);
                return count;
            }
        }
    }

    private void captureLegacyCurrentWorkPrice(PostgreSQLContainer<?> db) throws Exception {
        String sql = "SELECT work_item_id, net_price, currency_id FROM work_prices "
                + "WHERE valid_to IS NULL ORDER BY id LIMIT 1";
        try (Connection c = newConnection(db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next())
                    .as("the FOR-04-12 seed (039) must provide at least one current work_prices row "
                            + "for the fan-out to migrate")
                    .isTrue();
            legacyWorkItemId = rs.getLong("work_item_id");
            legacyNetPrice = rs.getBigDecimal("net_price");
            legacyCurrencyId = rs.getLong("currency_id");
        }
        assertThat(legacyNetPrice).as("captured legacy net_price").isNotNull();
    }

    // ------------------------------------------------------------------
    // Requirement 2.1 : work_package_prices table + three FKs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("work_package_prices exists with its three foreign keys (2.1)")
    void workPackagePricesTableAndForeignKeys() throws Exception {
        assertThat(tableExists(fullDb, "work_package_prices"))
                .as("work_package_prices table").isTrue();

        // Column shape (2.1): the three FK columns + net_price, all NOT NULL.
        assertColumnNotNull(fullDb, "work_package_prices", "work_price_id");
        assertColumnNotNull(fullDb, "work_package_prices", "offer_package_id");
        assertColumnNotNull(fullDb, "work_package_prices", "currency_id");
        assertColumnNotNull(fullDb, "work_package_prices", "net_price");

        // The three named FKs must exist and point at the right tables.
        assertThat(foreignKeyReferences(fullDb, "fk_work_package_prices_work_price"))
                .as("fk_work_package_prices_work_price -> work_prices").isEqualTo("work_prices");
        assertThat(foreignKeyReferences(fullDb, "fk_work_package_prices_offer_package"))
                .as("fk_work_package_prices_offer_package -> offer_packages").isEqualTo("offer_packages");
        assertThat(foreignKeyReferences(fullDb, "fk_work_package_prices_currency"))
                .as("fk_work_package_prices_currency -> currencies").isEqualTo("currencies");
    }

    // ------------------------------------------------------------------
    // Requirement 2.1 / 8.4 : ON DELETE CASCADE vs RESTRICT (pg_constraint.confdeltype)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The two owner FKs and work_prices.work_item_id are ON DELETE CASCADE; currency_id is RESTRICT (8.4)")
    void cascadeAndRestrictDeleteRules() throws Exception {
        // confdeltype: 'c' = CASCADE, 'r' = RESTRICT, 'a' = NO ACTION (the default).
        assertThat(deleteRule(fullDb, "fk_work_package_prices_work_price"))
                .as("work_package_prices.work_price_id must be ON DELETE CASCADE")
                .isEqualTo('c');
        assertThat(deleteRule(fullDb, "fk_work_package_prices_offer_package"))
                .as("work_package_prices.offer_package_id must be ON DELETE CASCADE")
                .isEqualTo('c');
        assertThat(deleteRule(fullDb, "fk_work_prices_work_item"))
                .as("work_prices.work_item_id must be ON DELETE CASCADE")
                .isEqualTo('c');

        // currency_id must NOT cascade: the default RESTRICT/NO ACTION protects an in-use currency.
        assertThat(deleteRule(fullDb, "fk_work_package_prices_currency"))
                .as("work_package_prices.currency_id must NOT cascade (RESTRICT / NO ACTION)")
                .isIn('r', 'a');
    }

    // ------------------------------------------------------------------
    // Requirement 2.4 : moved price columns dropped from work_prices
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valid_from / valid_to / net_price / currency_id are absent from work_prices after migration (2.4)")
    void workPricesPriceColumnsDropped() throws Exception {
        assertThat(columnExists(fullDb, "work_prices", "valid_from"))
                .as("work_prices.valid_from dropped").isFalse();
        assertThat(columnExists(fullDb, "work_prices", "valid_to"))
                .as("work_prices.valid_to dropped").isFalse();
        assertThat(columnExists(fullDb, "work_prices", "net_price"))
                .as("work_prices.net_price dropped").isFalse();
        assertThat(columnExists(fullDb, "work_prices", "currency_id"))
                .as("work_prices.currency_id dropped").isFalse();

        // work_item_id remains (the aggregator's identifying association).
        assertThat(columnExists(fullDb, "work_prices", "work_item_id"))
                .as("work_prices.work_item_id retained").isTrue();
    }

    // ------------------------------------------------------------------
    // Requirement 2.1 / 8.4 : unique (work_price_id, offer_package_id)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The (work_price_id, offer_package_id) unique constraint exists (2.1)")
    void uniqueWorkPriceOfferPackageConstraint() throws Exception {
        assertThat(constraintExists(fullDb, "uk_work_package_prices_work_offer"))
                .as("uk_work_package_prices_work_offer unique constraint").isTrue();
        assertThat(uniqueConstraintColumns(fullDb, "uk_work_package_prices_work_offer"))
                .as("unique constraint columns")
                .containsExactlyInAnyOrder("work_price_id", "offer_package_id");
    }

    // ------------------------------------------------------------------
    // Requirement 6.7 : package prices seeded (COUNT > 0, currency PLN)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Package prices are seeded (COUNT > 0) and all reference the PLN currency (6.7)")
    void packagePricesSeededInPln() throws Exception {
        assertThat(count(fullDb, "SELECT COUNT(*) FROM work_package_prices"))
                .as("work_package_prices seed count").isGreaterThan(0);

        long plnId = scalar(fullDb, "SELECT id FROM currencies WHERE code = 'PLN' LIMIT 1");
        long nonPln = count(fullDb,
                "SELECT COUNT(*) FROM work_package_prices WHERE currency_id <> " + plnId);
        assertThat(nonPln)
                .as("every seeded work_package_prices row must use the PLN currency")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 2.4 / 8.4 : re-running the changelog is a no-op (idempotent)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the changelog changes nothing (idempotent) (2.4, 8.4)")
    void reRunningChangelogIsANoOp() throws Exception {
        long changeSetsBefore = count(fullDb, "SELECT COUNT(*) FROM databasechangelog");
        long packagePricesBefore = count(fullDb, "SELECT COUNT(*) FROM work_package_prices");
        long workPricesBefore = count(fullDb, "SELECT COUNT(*) FROM work_prices");

        // Apply the whole changelog again: every 044/045 sub-changeset is guarded (preConditions +
        // MARK_RAN / NOT EXISTS), so nothing new is applied and no rows are added.
        runFullChangelog(fullDb);

        assertThat(count(fullDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(fullDb, "SELECT COUNT(*) FROM work_package_prices"))
                .as("no duplicate package prices on re-run")
                .isEqualTo(packagePricesBefore);
        assertThat(count(fullDb, "SELECT COUNT(*) FROM work_prices"))
                .as("no duplicate aggregators on re-run")
                .isEqualTo(workPricesBefore);

        // The dropped columns are still gone (the re-run did not resurrect them).
        assertThat(columnExists(fullDb, "work_prices", "net_price"))
                .as("work_prices.net_price still absent after re-run").isFalse();
    }

    // ------------------------------------------------------------------
    // Requirement 2.3 : fan-out created one WorkPackagePrice per seeded package per current price
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The 044c fan-out created one work_package_prices row per offer package for the pre-existing current price (2.3)")
    void fanOutCreatedOneRowPerPackage() throws Exception {
        // Locate the migrated aggregator via the work item of the current price captured in phase 1.
        long aggregatorId = scalar(fanoutDb,
                "SELECT id FROM work_prices WHERE work_item_id = " + legacyWorkItemId + " LIMIT 1");
        assertThat(aggregatorId).as("the migrated legacy aggregator survives 044").isPositive();

        long packageCount = count(fanoutDb, "SELECT COUNT(*) FROM offer_packages");
        assertThat(packageCount).as("seeded offer packages").isGreaterThan(0);

        // Fan-out: exactly one work_package_prices row per offer package for that aggregator.
        long fannedRows = count(fanoutDb,
                "SELECT COUNT(*) FROM work_package_prices WHERE work_price_id = " + aggregatorId);
        assertThat(fannedRows)
                .as("044c must create one work_package_prices row per seeded offer package")
                .isEqualTo(packageCount);

        // Each fanned row covers a distinct offer package (no package missed or duplicated).
        long distinctPackages = count(fanoutDb,
                "SELECT COUNT(DISTINCT offer_package_id) FROM work_package_prices "
                        + "WHERE work_price_id = " + aggregatorId);
        assertThat(distinctPackages)
                .as("each fanned row targets a distinct offer package")
                .isEqualTo(packageCount);

        // The copied net_price / currency_id equal the source current price's captured values.
        long mismatched;
        try (Connection c = newConnection(fanoutDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM work_package_prices "
                             + "WHERE work_price_id = ? AND (net_price <> ? OR currency_id <> ?)")) {
            ps.setLong(1, aggregatorId);
            ps.setBigDecimal(2, legacyNetPrice);
            ps.setLong(3, legacyCurrencyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                mismatched = rs.getLong(1);
            }
        }
        assertThat(mismatched)
                .as("fan-out copies the source current price's net_price (%s) and currency_id (%s)",
                        legacyNetPrice, legacyCurrencyId)
                .isZero();
    }

    // ------------------------------------------------------------------
    // information_schema / pg_catalog helpers
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

    /**
     * Reads the ON DELETE action directly from the PostgreSQL catalog. {@code pg_constraint.confdeltype}
     * is a single char: 'c' = CASCADE, 'r' = RESTRICT, 'a' = NO ACTION, 'n' = SET NULL, 'd' = SET DEFAULT.
     */
    private char deleteRule(PostgreSQLContainer<?> db, String constraintName) throws Exception {
        String sql = "SELECT confdeltype FROM pg_constraint WHERE conname = ? AND contype = 'f'";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("foreign key constraint %s should exist", constraintName).isTrue();
                String v = rs.getString(1);
                assertThat(v).as("confdeltype for %s", constraintName).isNotNull().hasSize(1);
                return v.charAt(0);
            }
        }
    }

    private boolean constraintExists(PostgreSQLContainer<?> db, String constraintName) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND constraint_name = ?";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private java.util.List<String> uniqueConstraintColumns(PostgreSQLContainer<?> db, String constraintName)
            throws Exception {
        String sql = "SELECT kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'UNIQUE' "
                + "  AND tc.table_schema = 'public' AND tc.constraint_name = ?";
        try (Connection c = newConnection(db); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
                return cols;
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

    private long scalar(PostgreSQLContainer<?> db, String sql) throws Exception {
        try (Connection c = newConnection(db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("scalar query returned no row: %s", sql).isTrue();
            return rs.getLong(1);
        }
    }
}
