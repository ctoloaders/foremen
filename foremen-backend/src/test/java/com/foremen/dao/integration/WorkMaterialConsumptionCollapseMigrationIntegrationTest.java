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
 * Integration test that applies the real FOR-05-04
 * {@code 084-collapse-work-material-consumptions-package} changeset against a real PostgreSQL
 * instance (Testcontainers) and verifies the migrated schema against the live catalog — NOT a
 * Hibernate-generated schema.
 *
 * <p>It exercises two flows against separate databases:
 * <ul>
 *   <li><b>Full changelog</b> (fresh container): {@code work_material_consumptions} no longer
 *       carries {@code offer_package_id}, and no duplicate rows remain per
 *       {@code (work_item_id, material_type, branch)} group (Requirements 7.2, 7.3).</li>
 *   <li><b>Two-phase collapse</b> (fresh container): the changelog is applied up to (but not
 *       including) changeset {@code 084a}, a chosen work item's per-package construction norm
 *       rows (seeded by {@code 069}) are overwritten with distinct, deliberately-differing
 *       {@code norm_qty} values, then the remainder of the changelog runs. This drives the
 *       {@code 084a} archive + {@code 084b} MAX-dedupe + {@code 084c} drop, and proves: the
 *       archive table holds the original per-package row count, the surviving row's
 *       {@code norm_qty} equals the group's MAX across the seeded package rows, and re-running
 *       the changelog is a no-op (Requirements 7.2, 7.3, 8.4).</li>
 * </ul>
 *
 * <p>Mirrors {@code WorkPriceCollapseMigrationIntegrationTest} (changeset 082): standalone
 * Testcontainers + real changelog, catalog assertions via {@code information_schema}, and the
 * dynamic phase-1/phase-2 split via {@code listUnrunChangeSets}.
 *
 * <p>Validates: Requirements 7.2, 7.3, 8.4
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkMaterialConsumptionCollapseMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /** Fully migrated database (whole changelog applied, then re-applied for idempotency). */
    private PostgreSQLContainer<?> fullDb;
    /** Database used for the controlled two-phase collapse scenario. */
    private PostgreSQLContainer<?> collapseDb;

    /** The work item whose per-package construction norms were seeded pre-migration. */
    private long collapseWorkItemId;
    private long collapseMaterialTypeId;
    private String collapseBranch;
    /** The MAX norm_qty among the seeded per-package rows (the expected surviving value). */
    private BigDecimal expectedMaxNormQty;
    /** How many per-package rows were seeded for that group (expected archive row count). */
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
     * Applies the changelog in two phases so the {@code 084a}/{@code 084b} archive+dedupe sees a
     * chosen (work_item, material_type, branch) group with several pre-existing per-package norm
     * rows carrying deliberately-differing {@code norm_qty} values. Phase 1 runs every changeset
     * up to (but not including) the first one whose id starts with {@code 084}; phase 2 runs the
     * rest.
     *
     * <p>Phase 1 includes changeset {@code 069}, which seeds {@code work_material_consumptions}
     * with one row per offer package for several catalog work items (e.g. work {@code 2.02},
     * construction branch, material type {@code PLYTA_GK}, across budget/norm/lux). We pick one
     * such group and overwrite its per-package rows with distinct, strictly-increasing
     * {@code norm_qty} values so the MAX-collapse resolution is unambiguous to assert against.
     */
    private void runCollapseScenario(PostgreSQLContainer<?> db) throws Exception {
        int phaseOneCount = countChangeSetsBefore084(db);

        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(phaseOneCount, "");
            }
        }

        seedDistinctNormQuantities(db);

        // Phase 2: run the remainder — 084a/084b/084c.
        runFullChangelog(db);
    }

    private int countChangeSetsBefore084(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(
                        new Contexts(), new LabelExpression());
                int count = 0;
                for (ChangeSet cs : unrun) {
                    if (cs.getId().startsWith("084")) {
                        break;
                    }
                    count++;
                }
                assertThat(count)
                        .as("there must be changesets before 084 to run in phase 1")
                        .isGreaterThan(0);
                return count;
            }
        }
    }

    /**
     * Picks one (work_item, material_type, branch) group seeded by 069's fan-out — construction
     * branch, work {@code 2.02}, material type {@code PLYTA_GK}, present across budget/norm/lux
     * — and overwrites its per-package rows with distinct, strictly-increasing norm_qty values so
     * the MAX-collapse resolution rule is unambiguous to assert against.
     */
    private void seedDistinctNormQuantities(PostgreSQLContainer<?> db) throws Exception {
        try (Connection c = newConnection(db)) {
            long workItemId;
            long materialTypeId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM work_items WHERE code = ?")) {
                ps.setString(1, "2.02");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("work item 2.02 must exist (069 seed)").isTrue();
                    workItemId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM construction_material_types WHERE code = ?")) {
                ps.setString(1, "PLYTA_GK");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("construction material type PLYTA_GK must exist").isTrue();
                    materialTypeId = rs.getLong(1);
                }
            }
            collapseWorkItemId = workItemId;
            collapseMaterialTypeId = materialTypeId;
            collapseBranch = "construction";

            List<Long> consumptionIds = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM work_material_consumptions "
                            + "WHERE work_item_id = ? AND construction_material_type_id = ? AND branch = ? "
                            + "ORDER BY id")) {
                ps.setLong(1, workItemId);
                ps.setLong(2, materialTypeId);
                ps.setString(3, collapseBranch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        consumptionIds.add(rs.getLong("id"));
                    }
                }
            }
            assertThat(consumptionIds)
                    .as("the fanned-out per-package work_material_consumptions rows for the chosen group")
                    .isNotEmpty();
            seededPackageRowCount = consumptionIds.size();

            // Assign strictly increasing norm_qty values (1.0000, 2.0000, ...) so MAX
            // unambiguously picks the last row.
            BigDecimal normQty = new BigDecimal("1.0000");
            for (long id : consumptionIds) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE work_material_consumptions SET norm_qty = ? WHERE id = ?")) {
                    up.setBigDecimal(1, normQty);
                    up.setLong(2, id);
                    up.executeUpdate();
                }
                expectedMaxNormQty = normQty;
                normQty = normQty.add(new BigDecimal("1.0000"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Requirement 7.2 : offer_package_id column is gone after migration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("work_material_consumptions no longer has an offer_package_id column after migration (7.2)")
    void offerPackageColumnIsGone() throws Exception {
        assertThat(columnExists(fullDb, "work_material_consumptions", "offer_package_id"))
                .as("offer_package_id should be dropped by 084c").isFalse();
    }

    // ------------------------------------------------------------------
    // Requirement 7.3 : exactly one row per (work_item, material_type, branch) group
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Exactly one row remains per (work_item, material_type, branch) group, no duplicates (7.3)")
    void noDuplicateGroupsRemain() throws Exception {
        long duplicateGroupCount = count(fullDb,
                "SELECT COUNT(*) FROM ("
                        + "  SELECT work_item_id, "
                        + "         COALESCE(construction_material_type_id, finishing_material_type_id) AS material_type_id, "
                        + "         branch, COUNT(*) AS cnt "
                        + "  FROM work_material_consumptions "
                        + "  GROUP BY work_item_id, "
                        + "           COALESCE(construction_material_type_id, finishing_material_type_id), "
                        + "           branch "
                        + "  HAVING COUNT(*) > 1"
                        + ") dupes");
        assertThat(duplicateGroupCount)
                .as("no (work_item, material_type, branch) group should have more than one row after collapse")
                .isZero();

        assertThat(count(fullDb, "SELECT COUNT(*) FROM work_material_consumptions"))
                .as("some rows must remain after the collapse")
                .isGreaterThan(0);
    }

    // ------------------------------------------------------------------
    // Requirement 7.3 / 8.3 : surviving row's norm_qty equals the group's MAX
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Surviving row's norm_qty equals the MAX across the seeded package rows for the group (7.3, 8.3)")
    void survivingRowNormQtyEqualsMax() throws Exception {
        List<BigDecimal> survivingNormQtys = new java.util.ArrayList<>();
        try (Connection c = newConnection(collapseDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT norm_qty FROM work_material_consumptions "
                             + "WHERE work_item_id = ? AND construction_material_type_id = ? AND branch = ?")) {
            ps.setLong(1, collapseWorkItemId);
            ps.setLong(2, collapseMaterialTypeId);
            ps.setString(3, collapseBranch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    survivingNormQtys.add(rs.getBigDecimal("norm_qty"));
                }
            }
        }

        assertThat(survivingNormQtys)
                .as("exactly one row must survive the collapse for the seeded group")
                .hasSize(1);
        assertThat(survivingNormQtys.get(0))
                .as("surviving norm_qty must equal the MAX seeded norm_qty (%s)", expectedMaxNormQty)
                .isEqualByComparingTo(expectedMaxNormQty);
    }

    // ------------------------------------------------------------------
    // Requirement 8.4 : archive holds the pre-collapse row count for the group
    // ------------------------------------------------------------------

    @Test
    @DisplayName("work_material_consumptions_pkg_archive holds the pre-collapse row count for the group (8.4)")
    void archiveHoldsPreCollapseRowCount() throws Exception {
        long archivedForGroup = count(collapseDb,
                "SELECT COUNT(*) FROM work_material_consumptions_pkg_archive "
                        + "WHERE work_item_id = " + collapseWorkItemId
                        + " AND construction_material_type_id = " + collapseMaterialTypeId
                        + " AND branch = '" + collapseBranch + "'");
        assertThat(archivedForGroup)
                .as("archive must hold exactly the seeded per-package row count for this group")
                .isEqualTo(seededPackageRowCount);

        // Every archived row records the MAX_NORMQTY resolution rule (8.3).
        long withoutRule = count(collapseDb,
                "SELECT COUNT(*) FROM work_material_consumptions_pkg_archive "
                        + "WHERE work_item_id = " + collapseWorkItemId
                        + " AND construction_material_type_id = " + collapseMaterialTypeId
                        + " AND branch = '" + collapseBranch + "'"
                        + " AND resolution_rule <> 'MAX_NORMQTY'");
        assertThat(withoutRule)
                .as("every archived row must record the MAX_NORMQTY resolution rule")
                .isZero();

        // archived_at is populated (auditable snapshot, not a silent delete).
        long withoutArchivedAt = count(collapseDb,
                "SELECT COUNT(*) FROM work_material_consumptions_pkg_archive "
                        + "WHERE work_item_id = " + collapseWorkItemId
                        + " AND construction_material_type_id = " + collapseMaterialTypeId
                        + " AND branch = '" + collapseBranch + "'"
                        + " AND archived_at IS NULL");
        assertThat(withoutArchivedAt).as("archived_at must be populated for every archived row").isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 8.4 : re-running the changelog is a no-op (idempotent)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the changelog changes nothing on the fully-migrated DB (idempotent, no-op) (8.4)")
    void reRunningChangelogIsANoOpOnFullDb() throws Exception {
        long changeSetsBefore = count(fullDb, "SELECT COUNT(*) FROM databasechangelog");
        long rowsBefore = count(fullDb, "SELECT COUNT(*) FROM work_material_consumptions");

        runFullChangelog(fullDb);

        assertThat(count(fullDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(fullDb, "SELECT COUNT(*) FROM work_material_consumptions"))
                .as("no duplicate/removed rows on re-run")
                .isEqualTo(rowsBefore);
        assertThat(columnExists(fullDb, "work_material_consumptions", "offer_package_id"))
                .as("offer_package_id must remain dropped after re-run").isFalse();
    }

    @Test
    @DisplayName("Re-running the changelog on the collapsed DB leaves counts/values unchanged (idempotent, no-op) (8.4)")
    void reRunningChangelogIsANoOpOnCollapseDb() throws Exception {
        long changeSetsBefore = count(collapseDb, "SELECT COUNT(*) FROM databasechangelog");
        long archiveCountBefore = count(collapseDb,
                "SELECT COUNT(*) FROM work_material_consumptions_pkg_archive "
                        + "WHERE work_item_id = " + collapseWorkItemId
                        + " AND construction_material_type_id = " + collapseMaterialTypeId
                        + " AND branch = '" + collapseBranch + "'");
        BigDecimal normQtyBefore;
        try (Connection c = newConnection(collapseDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT norm_qty FROM work_material_consumptions "
                             + "WHERE work_item_id = ? AND construction_material_type_id = ? AND branch = ?")) {
            ps.setLong(1, collapseWorkItemId);
            ps.setLong(2, collapseMaterialTypeId);
            ps.setString(3, collapseBranch);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                normQtyBefore = rs.getBigDecimal("norm_qty");
            }
        }

        runFullChangelog(collapseDb);

        assertThat(count(collapseDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(collapseDb,
                "SELECT COUNT(*) FROM work_material_consumptions_pkg_archive "
                        + "WHERE work_item_id = " + collapseWorkItemId
                        + " AND construction_material_type_id = " + collapseMaterialTypeId
                        + " AND branch = '" + collapseBranch + "'"))
                .as("no duplicate archive rows on re-run")
                .isEqualTo(archiveCountBefore);

        BigDecimal normQtyAfter;
        try (Connection c = newConnection(collapseDb);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT norm_qty FROM work_material_consumptions "
                             + "WHERE work_item_id = ? AND construction_material_type_id = ? AND branch = ?")) {
            ps.setLong(1, collapseWorkItemId);
            ps.setLong(2, collapseMaterialTypeId);
            ps.setString(3, collapseBranch);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                normQtyAfter = rs.getBigDecimal("norm_qty");
            }
        }
        assertThat(normQtyAfter)
                .as("norm_qty must be unchanged by the re-run")
                .isEqualByComparingTo(normQtyBefore);
    }

    // ------------------------------------------------------------------
    // information_schema helpers
    // ------------------------------------------------------------------

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

    private long count(PostgreSQLContainer<?> db, String sql) throws Exception {
        try (Connection c = newConnection(db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
