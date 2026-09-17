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
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the DB-level {@code ON DELETE CASCADE} / {@code RESTRICT} behavior
 * of the FOR-04-12b package-based work-price model introduced by changeset
 * {@code 044-migrate-work-prices-to-packages}:
 * <ul>
 *     <li>deleting an {@code OfferPackage} removes exactly the {@code work_package_prices} rows that
 *         referenced it across every aggregator (via {@code ON DELETE CASCADE} on
 *         {@code work_package_prices.offer_package_id}), leaving every other package's rows intact
 *         (Requirement 1.10);</li>
 *     <li>deleting a {@code WorkItem} removes its {@code WorkPrice} aggregator (via
 *         {@code ON DELETE CASCADE} on {@code work_prices.work_item_id}) and, transitively, all of
 *         that aggregator's {@code work_package_prices} rows (via {@code ON DELETE CASCADE} on
 *         {@code work_package_prices.work_price_id}), leaving other work items untouched
 *         (Requirement 1.9);</li>
 *     <li>negative control — deleting a {@code Currency} that is still referenced by a
 *         {@code work_package_prices} row fails, because {@code fk_work_package_prices_currency}
 *         keeps the default {@code RESTRICT} (Requirement 1.11).</li>
 * </ul>
 *
 * <p>The cascade FKs are created by the Liquibase migration (044), not by JPA annotations, so this
 * test exercises the REAL migrated schema and deletes at the DB level (native SQL) with NO
 * application-level pre-deletion. It mirrors {@code ProjectMemberMigrationIntegrationTest} and the
 * other migration integration tests, which established the pattern of running the real changelog
 * against a real database and asserting against the live catalog rather than relying on Hibernate
 * DDL. Fixtures use freshly-inserted, uniquely-named rows so the test is independent of the seeded
 * catalog data (changeset 045) and repeatable.
 *
 * <p>Validates: Requirements 8.5, 1.9, 1.10, 1.11
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkPriceCascadeDeleteIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startContainerAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        runLiquibase();
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

    // ---------------------------------------------------------------------
    // Requirement 1.10 : deleting an OfferPackage cascades to its WorkPackagePrice rows only
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("Deleting an OfferPackage removes exactly its work_package_prices across all aggregators; other packages untouched")
    void deletingOfferPackageCascadesToItsPackagePricesOnly() throws Exception {
        long unique = uniqueSuffix();
        long currencyId = plnCurrencyId();

        // Two independent offer packages: one to delete, one that must survive.
        long packageToDelete = insertOfferPackage("cascade-del-pkg-" + unique);
        long packageToKeep = insertOfferPackage("cascade-keep-pkg-" + unique);

        long categoryId = insertWorkCategory(unique);
        long unitId = anyMeasurementUnitId();

        // Two aggregators (work items), each priced for BOTH packages, so the deleted package
        // has rows across multiple aggregators and the surviving package has rows we can assert on.
        long workItemA = insertWorkItem(categoryId, unitId, "Cascade Pkg Work A " + unique);
        long workItemB = insertWorkItem(categoryId, unitId, "Cascade Pkg Work B " + unique);
        long aggregatorA = insertWorkPrice(workItemA);
        long aggregatorB = insertWorkPrice(workItemB);

        long delPriceA = insertWorkPackagePrice(aggregatorA, packageToDelete, currencyId, "100.00");
        long delPriceB = insertWorkPackagePrice(aggregatorB, packageToDelete, currencyId, "200.00");
        long keepPriceA = insertWorkPackagePrice(aggregatorA, packageToKeep, currencyId, "111.00");
        long keepPriceB = insertWorkPackagePrice(aggregatorB, packageToKeep, currencyId, "222.00");

        // Sanity: all four rows exist before the delete.
        assertThat(packagePriceExists(delPriceA)).isTrue();
        assertThat(packagePriceExists(delPriceB)).isTrue();
        assertThat(packagePriceExists(keepPriceA)).isTrue();
        assertThat(packagePriceExists(keepPriceB)).isTrue();

        // DB-level delete of the OfferPackage — NO application-level pre-deletion of package prices.
        deleteOfferPackage(packageToDelete);

        // The deleted package's rows are gone across BOTH aggregators (1.10) ...
        assertThat(packagePriceExists(delPriceA))
                .as("work_package_price for the deleted package (aggregator A) is cascade-removed").isFalse();
        assertThat(packagePriceExists(delPriceB))
                .as("work_package_price for the deleted package (aggregator B) is cascade-removed").isFalse();
        // ... and no work_package_prices row references the deleted package any more.
        assertThat(countPackagePricesForOfferPackage(packageToDelete))
                .as("no work_package_prices reference the deleted offer package").isZero();

        // The other package's rows are untouched, and both aggregators still exist.
        assertThat(packagePriceExists(keepPriceA))
                .as("surviving package's price (aggregator A) is untouched").isTrue();
        assertThat(packagePriceExists(keepPriceB))
                .as("surviving package's price (aggregator B) is untouched").isTrue();
        assertThat(workPriceExists(aggregatorA)).as("aggregator A survives").isTrue();
        assertThat(workPriceExists(aggregatorB)).as("aggregator B survives").isTrue();
    }

    // ---------------------------------------------------------------------
    // Requirement 1.9 : deleting a WorkItem cascades to its aggregator + all its WorkPackagePrice rows
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("Deleting a WorkItem removes its WorkPrice aggregator and all its work_package_prices; other work items untouched")
    void deletingWorkItemCascadesToAggregatorAndPackagePrices() throws Exception {
        long unique = uniqueSuffix();
        long currencyId = plnCurrencyId();

        long offerPackage1 = insertOfferPackage("cascade-wi-pkg1-" + unique);
        long offerPackage2 = insertOfferPackage("cascade-wi-pkg2-" + unique);

        long categoryId = insertWorkCategory(unique);
        long unitId = anyMeasurementUnitId();

        // Target work item (to delete) with an aggregator priced for two packages.
        long targetWorkItem = insertWorkItem(categoryId, unitId, "Cascade WI Target " + unique);
        long targetAggregator = insertWorkPrice(targetWorkItem);
        long targetPrice1 = insertWorkPackagePrice(targetAggregator, offerPackage1, currencyId, "10.00");
        long targetPrice2 = insertWorkPackagePrice(targetAggregator, offerPackage2, currencyId, "20.00");

        // A second work item that must remain untouched.
        long otherWorkItem = insertWorkItem(categoryId, unitId, "Cascade WI Other " + unique);
        long otherAggregator = insertWorkPrice(otherWorkItem);
        long otherPrice = insertWorkPackagePrice(otherAggregator, offerPackage1, currencyId, "30.00");

        assertThat(workPriceExists(targetAggregator)).isTrue();
        assertThat(packagePriceExists(targetPrice1)).isTrue();
        assertThat(packagePriceExists(targetPrice2)).isTrue();

        // DB-level delete of the WorkItem — NO application-level pre-deletion.
        deleteWorkItem(targetWorkItem);

        // The aggregator is cascade-removed (work_prices.work_item_id ON DELETE CASCADE) ...
        assertThat(workPriceExists(targetAggregator))
                .as("WorkPrice aggregator is cascade-removed with its work item").isFalse();
        // ... and, transitively, all of its package prices (work_package_prices.work_price_id ON DELETE CASCADE).
        assertThat(packagePriceExists(targetPrice1))
                .as("target aggregator's first package price is cascade-removed").isFalse();
        assertThat(packagePriceExists(targetPrice2))
                .as("target aggregator's second package price is cascade-removed").isFalse();
        assertThat(countPackagePricesForWorkPrice(targetAggregator))
                .as("no work_package_prices reference the removed aggregator").isZero();

        // The other work item, its aggregator, and its price are untouched.
        assertThat(workPriceExists(otherAggregator)).as("other aggregator survives").isTrue();
        assertThat(packagePriceExists(otherPrice)).as("other work item's price survives").isTrue();
    }

    // ---------------------------------------------------------------------
    // Requirement 1.11 : deleting a still-referenced Currency is rejected (FK RESTRICT)
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("Deleting a Currency still referenced by a work_package_prices row fails (fk_work_package_prices_currency RESTRICT)")
    void deletingReferencedCurrencyIsRejected() throws Exception {
        long unique = uniqueSuffix();

        // A dedicated, freshly-inserted currency so the negative control does not depend on PLN.
        long currencyId = insertCurrency("CSC" + (unique % 1_000_000L));
        long offerPackage = insertOfferPackage("cascade-cur-pkg-" + unique);
        long categoryId = insertWorkCategory(unique);
        long unitId = anyMeasurementUnitId();
        long workItem = insertWorkItem(categoryId, unitId, "Cascade Currency Work " + unique);
        long aggregator = insertWorkPrice(workItem);
        long packagePrice = insertWorkPackagePrice(aggregator, offerPackage, currencyId, "42.00");

        assertThat(packagePriceExists(packagePrice)).isTrue();

        // Deleting the referenced currency must be rejected by the RESTRICT FK; no cascade.
        assertThatThrownBy(() -> deleteCurrency(currencyId))
                .as("deleting a currency referenced by a work_package_prices row is rejected")
                .isInstanceOf(SQLException.class);

        // The currency and the referencing price row both survive.
        assertThat(currencyExists(currencyId)).as("referenced currency survives the rejected delete").isTrue();
        assertThat(packagePriceExists(packagePrice)).as("referencing price row survives").isTrue();
    }

    // ---------------------------------------------------------------------
    // fixture-insertion helpers
    // ---------------------------------------------------------------------

    private static long uniqueSuffix() {
        return System.nanoTime() % 1_000_000_000L;
    }

    private long insertOfferPackage(String code) throws Exception {
        String sql = "INSERT INTO offer_packages (code, order_no, name_ru, name_pl, active, created_date) "
                + "VALUES (?, ?, ?, ?, true, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setInt(2, (int) (System.nanoTime() % 1_000_000L) + 1);
            ps.setString(3, "Пакет " + code);
            ps.setString(4, "Pakiet " + code);
            return executeReturningId(ps);
        }
    }

    private long insertWorkCategory(long unique) throws Exception {
        String sql = "INSERT INTO work_categories (code, order_no, name_ru, name_pl, active, created_date) "
                + "VALUES (?, ?, ?, ?, true, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "cascade-cat-" + unique);
            ps.setInt(2, (int) (unique % 1_000_000L) + 1);
            ps.setString(3, "Категория " + unique);
            ps.setString(4, "Kategoria " + unique);
            return executeReturningId(ps);
        }
    }

    private long anyMeasurementUnitId() throws Exception {
        // Reuse any seeded measurement unit; work_items.unit_id only needs a valid FK target.
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM measurement_units ORDER BY id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("at least one seeded measurement_unit exists").isTrue();
            return rs.getLong(1);
        }
    }

    private long insertWorkItem(long categoryId, long unitId, String namePl) throws Exception {
        String sql = "INSERT INTO work_items (work_category_id, unit_id, name_ru, name_pl, active, created_date) "
                + "VALUES (?, ?, ?, ?, true, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, categoryId);
            ps.setLong(2, unitId);
            ps.setString(3, namePl + " (ru)");
            ps.setString(4, namePl);
            return executeReturningId(ps);
        }
    }

    private long insertWorkPrice(long workItemId) throws Exception {
        // After migration 044, work_prices is a bare per-work-item aggregator (unique work_item_id).
        String sql = "INSERT INTO work_prices (work_item_id, created_date) VALUES (?, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, workItemId);
            return executeReturningId(ps);
        }
    }

    private long insertWorkPackagePrice(long workPriceId, long offerPackageId, long currencyId,
                                        String netPrice) throws Exception {
        String sql = "INSERT INTO work_package_prices "
                + "(work_price_id, offer_package_id, currency_id, net_price, created_date) "
                + "VALUES (?, ?, ?, ?::numeric, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, workPriceId);
            ps.setLong(2, offerPackageId);
            ps.setLong(3, currencyId);
            ps.setString(4, netPrice);
            return executeReturningId(ps);
        }
    }

    private long insertCurrency(String code) throws Exception {
        String sql = "INSERT INTO currencies (code, symbol, name_ru, name_pl, active, created_date) "
                + "VALUES (?, ?, ?, ?, true, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, "¤");
            ps.setString(3, "Валюта " + code);
            ps.setString(4, "Waluta " + code);
            return executeReturningId(ps);
        }
    }

    private long plnCurrencyId() throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM currencies WHERE code = 'PLN' LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("seeded PLN currency exists").isTrue();
            return rs.getLong(1);
        }
    }

    private long executeReturningId(PreparedStatement ps) throws Exception {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------------------------------------------------------------------
    // DB-level delete helpers (native SQL, no application pre-deletion)
    // ---------------------------------------------------------------------

    private void deleteOfferPackage(long id) throws Exception {
        executeUpdate("DELETE FROM offer_packages WHERE id = ?", id);
    }

    private void deleteWorkItem(long id) throws Exception {
        executeUpdate("DELETE FROM work_items WHERE id = ?", id);
    }

    private void deleteCurrency(long id) throws Exception {
        executeUpdate("DELETE FROM currencies WHERE id = ?", id);
    }

    private void executeUpdate(String sql, long id) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------
    // existence / count helpers
    // ---------------------------------------------------------------------

    private boolean packagePriceExists(long id) throws Exception {
        return rowExists("SELECT 1 FROM work_package_prices WHERE id = ?", id);
    }

    private boolean workPriceExists(long id) throws Exception {
        return rowExists("SELECT 1 FROM work_prices WHERE id = ?", id);
    }

    private boolean currencyExists(long id) throws Exception {
        return rowExists("SELECT 1 FROM currencies WHERE id = ?", id);
    }

    private boolean rowExists(String sql, long id) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countPackagePricesForOfferPackage(long offerPackageId) throws Exception {
        return count("SELECT COUNT(*) FROM work_package_prices WHERE offer_package_id = ?", offerPackageId);
    }

    private int countPackagePricesForWorkPrice(long workPriceId) throws Exception {
        return count("SELECT COUNT(*) FROM work_package_prices WHERE work_price_id = ?", workPriceId);
    }

    private int count(String sql, long id) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
