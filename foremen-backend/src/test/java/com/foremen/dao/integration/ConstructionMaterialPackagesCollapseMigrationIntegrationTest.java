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
 * Integration test that applies the real FOR-05-04
 * {@code 090-archive-and-drop-construction-material-packages} changeset against a real PostgreSQL
 * instance (Testcontainers) and verifies the migrated schema against the live catalog — NOT a
 * Hibernate-generated schema.
 *
 * <p>The construction-material collapse retires the {@code construction_material_packages} M:N join
 * table (created in {@code 057b}, seeded by {@code 058}/{@code 066}) after archiving every row into
 * {@code construction_material_packages_archive}. This mirrors the archive-before-drop pattern used
 * by {@code 082} (work_package_prices) and {@code 083} (estimate_line_package_prices).
 *
 * <p>It exercises two flows against separate databases:
 * <ul>
 *   <li><b>Full changelog</b> (fresh container): the join table {@code construction_material_packages}
 *       no longer exists, the {@code construction_material_packages_archive} table does exist, and
 *       re-running the changelog is a no-op (Requirements 5.4, 5.9, 8.8, 8.9).</li>
 *   <li><b>Two-phase collapse</b> (fresh container): the changelog is applied up to (but not
 *       including) changeset {@code 090}; the still-live {@code construction_material_packages} row
 *       count is captured (the seed rows from {@code 058}/{@code 066}); then the remainder of the
 *       changelog runs. This drives {@code 090a} archive + {@code 090b} drop, and proves the archive
 *       holds exactly the pre-drop source row count and the join table is gone afterwards
 *       (Requirements 5.4, 8.8).</li>
 * </ul>
 *
 * <p>Mirrors {@code WorkPriceCollapseMigrationIntegrationTest} (changeset 082) and
 * {@code EstimateSchemaMigrationIntegrationTest} (changeset 083): standalone Testcontainers + real
 * changelog, catalog assertions via {@code information_schema}, and the dynamic phase-1/phase-2 split
 * via {@code listUnrunChangeSets}.
 *
 * <p>Validates: Requirements 5.4, 5.9, 8.8, 8.9
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstructionMaterialPackagesCollapseMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String JOIN_TABLE = "construction_material_packages";
    private static final String ARCHIVE_TABLE = "construction_material_packages_archive";
    private static final String COLLAPSE_CHANGESET_PREFIX = "090";

    /** Fully migrated database (whole changelog applied, then re-applied for idempotency). */
    private PostgreSQLContainer<?> fullDb;
    /** Database used for the controlled two-phase collapse scenario. */
    private PostgreSQLContainer<?> collapseDb;

    /** The live {@code construction_material_packages} row count captured just before 090 ran. */
    private long sourceRowCountPreDrop;

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
     * Applies the changelog in two phases so that just before {@code 090} runs we can read the live
     * {@code construction_material_packages} row count (the seed rows created by {@code 058}/{@code 066}).
     * Phase 1 runs every changeset up to (but not including) the first one whose id starts with
     * {@code 090}; the source row count is captured; phase 2 runs the rest ({@code 090a}/{@code 090b}).
     */
    private void runCollapseScenario(PostgreSQLContainer<?> db) throws Exception {
        int phaseOneCount = countChangeSetsBeforeCollapse(db);

        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(phaseOneCount, "");
            }
        }

        // Capture the source count while the join table is still live (pre-drop).
        assertThat(tableExists(db, JOIN_TABLE))
                .as("%s must still exist before the 090 collapse runs", JOIN_TABLE)
                .isTrue();
        sourceRowCountPreDrop = count(db, "SELECT COUNT(*) FROM " + JOIN_TABLE);
        assertThat(sourceRowCountPreDrop)
                .as("the 058/066 seed must provide at least one %s row to archive", JOIN_TABLE)
                .isGreaterThan(0);

        // Phase 2: run the remainder — 090a (archive) + 090b (drop).
        runFullChangelog(db);
    }

    private int countChangeSetsBeforeCollapse(PostgreSQLContainer<?> db) throws Exception {
        try (Connection connection = newConnection(db)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(
                        new Contexts(), new LabelExpression());
                int count = 0;
                for (ChangeSet cs : unrun) {
                    if (cs.getId().startsWith(COLLAPSE_CHANGESET_PREFIX)) {
                        break;
                    }
                    count++;
                }
                assertThat(count)
                        .as("there must be changesets before %s to run in phase 1",
                                COLLAPSE_CHANGESET_PREFIX)
                        .isGreaterThan(0);
                return count;
            }
        }
    }

    // ------------------------------------------------------------------
    // Requirement 8.8 : the join table is gone after the collapse
    // ------------------------------------------------------------------

    @Test
    @DisplayName("construction_material_packages no longer exists after migration (5.4, 8.8)")
    void joinTableIsGoneAfterFullMigration() throws Exception {
        assertThat(tableExists(fullDb, JOIN_TABLE))
                .as("%s should be dropped by 090b", JOIN_TABLE).isFalse();
        assertThat(tableExists(fullDb, ARCHIVE_TABLE))
                .as("%s should exist after 090a", ARCHIVE_TABLE).isTrue();
    }

    @Test
    @DisplayName("construction_material_packages is gone in the two-phase collapse DB too (8.8)")
    void joinTableIsGoneAfterTwoPhaseCollapse() throws Exception {
        assertThat(tableExists(collapseDb, JOIN_TABLE))
                .as("%s should be dropped by 090b in the collapse scenario", JOIN_TABLE).isFalse();
    }

    // ------------------------------------------------------------------
    // Requirement 5.4 : the archive holds exactly the source count pre-drop
    // ------------------------------------------------------------------

    @Test
    @DisplayName("construction_material_packages_archive holds exactly the pre-drop source row count (5.4, 8.8)")
    void archiveHoldsExactlyPreDropSourceRowCount() throws Exception {
        long archived = count(collapseDb, "SELECT COUNT(*) FROM " + ARCHIVE_TABLE);
        assertThat(archived)
                .as("archive row count must equal the live %s count captured pre-drop", JOIN_TABLE)
                .isEqualTo(sourceRowCountPreDrop);

        // Every archived row records both natural-key columns and its archived_at snapshot.
        long withNullKeyOrArchivedAt = count(collapseDb,
                "SELECT COUNT(*) FROM " + ARCHIVE_TABLE
                        + " WHERE construction_material_id IS NULL"
                        + " OR offer_package_id IS NULL"
                        + " OR archived_at IS NULL");
        assertThat(withNullKeyOrArchivedAt)
                .as("every archived row must carry both natural keys and a non-null archived_at")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Requirements 5.9 / 8.9 : re-running the changelog is a no-op (idempotent)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the changelog changes no schema or data on the full DB (idempotent) (5.9, 8.9)")
    void reRunningChangelogIsANoOpOnFullDb() throws Exception {
        long changeSetsBefore = count(fullDb, "SELECT COUNT(*) FROM databasechangelog");
        long archiveBefore = count(fullDb, "SELECT COUNT(*) FROM " + ARCHIVE_TABLE);

        runFullChangelog(fullDb);

        assertThat(count(fullDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(fullDb, "SELECT COUNT(*) FROM " + ARCHIVE_TABLE))
                .as("no duplicate archive rows on re-run")
                .isEqualTo(archiveBefore);
        assertThat(tableExists(fullDb, JOIN_TABLE))
                .as("%s must remain dropped after re-run", JOIN_TABLE).isFalse();
        assertThat(tableExists(fullDb, ARCHIVE_TABLE))
                .as("%s must remain present after re-run", ARCHIVE_TABLE).isTrue();
    }

    @Test
    @DisplayName("Re-running the changelog on the collapsed DB leaves the archive count unchanged (idempotent) (5.9, 8.9)")
    void reRunningChangelogIsANoOpOnCollapseDb() throws Exception {
        long changeSetsBefore = count(collapseDb, "SELECT COUNT(*) FROM databasechangelog");
        long archiveBefore = count(collapseDb, "SELECT COUNT(*) FROM " + ARCHIVE_TABLE);

        runFullChangelog(collapseDb);

        assertThat(count(collapseDb, "SELECT COUNT(*) FROM databasechangelog"))
                .as("no changeset applied twice on re-run")
                .isEqualTo(changeSetsBefore);
        assertThat(count(collapseDb, "SELECT COUNT(*) FROM " + ARCHIVE_TABLE))
                .as("archive count must be unchanged by the re-run (still equal to the pre-drop source)")
                .isEqualTo(archiveBefore);
        assertThat(count(collapseDb, "SELECT COUNT(*) FROM " + ARCHIVE_TABLE))
                .as("archive count must still equal the source count captured pre-drop")
                .isEqualTo(sourceRowCountPreDrop);
        assertThat(tableExists(collapseDb, JOIN_TABLE))
                .as("%s must remain dropped after re-run", JOIN_TABLE).isFalse();
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

    private long count(PostgreSQLContainer<?> db, String sql) throws Exception {
        try (Connection c = newConnection(db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
