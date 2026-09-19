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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code work_items.code} stable natural key introduced by
 * changeset {@code 063-add-work-items-code}:
 * <ul>
 *     <li>the {@code code} column is added and the partial unique index
 *         {@code ux_work_items_code ON work_items(code) WHERE code IS NOT NULL} exists
 *         (Requirement 1.3);</li>
 *     <li>the {@code 063c} data changeset backfills {@code code} from the Excel positional
 *         {@code LP} (normalized {@code N.MM}) for the seeded work items — asserted against
 *         representative rows: {@code 'Rozbiórki ściany murowanej'} &rarr; {@code 1.02},
 *         {@code 'Zabezpieczenie na czas prac okien, drzwi, drogi transportu'} &rarr; {@code 1.01},
 *         {@code 'Posadzka cementowa'} &rarr; {@code 10.01} (Requirement 1.2);</li>
 *     <li>non-null {@code code} values are unique, while multiple {@code NULL}s are permitted (the
 *         partial unique index allows unlimited nulls) — a work item with no derivable {@code LP}
 *         keeps {@code code = NULL} and the migration does not fail (Requirements 1.3, 1.4);</li>
 *     <li>re-running the changelog is idempotent — the {@code 063c} backfill is guarded by
 *         {@code MARK_RAN} + {@code sqlCheck expectedResult="0"} (no code populated), so a second
 *         application leaves the populated-code state exactly as it was (Requirement 1.2).</li>
 * </ul>
 *
 * <p>Mirrors {@code WorkCatalogResourceSeedIntegrationTest} (changeset 037): run the real changelog
 * against a real database and assert against the live catalog via raw JDBC. The {@code work_items}
 * catalog rows the backfill populates are seeded by changeset {@code 039-seed-work-prices-resource}
 * (guarded by {@code sqlCheck COUNT(*) FROM work_items = 0}), so a fresh migration has rows for
 * {@code 063c} to backfill.
 *
 * <p>Validates: Requirements 1.2, 1.3, 1.4, 10.9
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkItemCodeBackfillIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String INDEX_NAME = "ux_work_items_code";

    /**
     * Representative (name_pl -> expected code) rows drawn from the 063c backfill and the design
     * examples. Each is seeded by changeset 039 and must receive its Excel LP code.
     */
    private static final Map<String, String> REPRESENTATIVE_CODES = new LinkedHashMap<>();

    static {
        REPRESENTATIVE_CODES.put("Zabezpieczenie na czas prac okien, drzwi, drogi transportu", "1.01");
        REPRESENTATIVE_CODES.put("Rozbiórki ściany murowanej", "1.02");
        REPRESENTATIVE_CODES.put("Posadzka cementowa", "10.01");
    }

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

    // --- Requirement 1.3 : the code column and its partial unique index exist ---
    @Test
    @DisplayName("Changeset 063 adds the work_items.code column and the partial unique index")
    void addsCodeColumnAndPartialUniqueIndex() throws Exception {
        assertThat(columnExists("work_items", "code"))
                .as("work_items.code column exists").isTrue();
        assertThat(indexExists(INDEX_NAME))
                .as("partial unique index %s exists", INDEX_NAME).isTrue();
        assertThat(indexIsUnique(INDEX_NAME))
                .as("index %s is a UNIQUE index", INDEX_NAME).isTrue();
        assertThat(indexIsPartial(INDEX_NAME))
                .as("index %s is partial (WHERE code IS NOT NULL), so it allows many nulls", INDEX_NAME)
                .isTrue();
    }

    // --- Requirement 1.2 : representative rows are backfilled with their Excel LP code ---
    @Test
    @DisplayName("Changeset 063c backfills code for the representative seeded work items")
    void backfillsRepresentativeCodes() throws Exception {
        // Sanity: the catalog rows the backfill targets are actually seeded (by changeset 039),
        // otherwise the backfill would silently populate nothing.
        assertThat(countWorkItems())
                .as("work_items catalog rows seeded by changeset 039").isPositive();

        for (Map.Entry<String, String> row : REPRESENTATIVE_CODES.entrySet()) {
            assertThat(codeForNamePl(row.getKey()))
                    .as("code for work item '%s'", row.getKey())
                    .isEqualTo(row.getValue());
        }
    }

    // --- Requirement 1.3 : non-null codes are unique ---
    @Test
    @DisplayName("Non-null work_items.code values are unique")
    void nonNullCodesAreUnique() throws Exception {
        // At least the three representative codes must be present.
        assertThat(countPopulatedCodes())
                .as("populated (non-null) code count")
                .isGreaterThanOrEqualTo(REPRESENTATIVE_CODES.size());

        // No code value appears more than once among the non-null rows.
        assertThat(duplicateCodes())
                .as("duplicate non-null code values (must be none — enforced by ux_work_items_code)")
                .isEmpty();
    }

    // --- Requirement 1.4 : nulls are permitted and a second row with the same code is rejected ---
    @Test
    @DisplayName("The partial unique index permits multiple null codes but rejects a duplicate non-null code")
    void permitsNullsAndRejectsDuplicateNonNull() throws Exception {
        long categoryId = anyWorkCategoryId();
        long unitId = anyMeasurementUnitId();

        // Two fresh work items with a NULL code coexist — the partial unique index allows unlimited
        // nulls (Requirement 1.4), so a work item with no derivable LP keeps code = null.
        long a = insertWorkItem(categoryId, unitId, "wmc-null-a-" + System.nanoTime(), null);
        long b = insertWorkItem(categoryId, unitId, "wmc-null-b-" + System.nanoTime(), null);
        assertThat(a).isNotEqualTo(b);
        assertThat(codeForId(a)).as("first null-code row keeps code null").isNull();
        assertThat(codeForId(b)).as("second null-code row keeps code null").isNull();

        // A fresh unique non-null code inserts fine; a second row with the SAME code is rejected by
        // the ux_work_items_code partial unique index (uniqueness of non-null codes, Requirement 1.3).
        String uniqueCode = "WMC-UNIQ-" + System.nanoTime();
        insertWorkItem(categoryId, unitId, "wmc-code-a-" + System.nanoTime(), uniqueCode);
        boolean secondRejected = false;
        try {
            insertWorkItem(categoryId, unitId, "wmc-code-b-" + System.nanoTime(), uniqueCode);
        } catch (Exception expected) {
            secondRejected = true;
        }
        assertThat(secondRejected)
                .as("a second work item with a duplicate non-null code is rejected").isTrue();

        // Teardown: remove the rows this test created so repeated runs stay clean.
        deleteWorkItem(a);
        deleteWorkItem(b);
        deleteWorkItemsByCode(uniqueCode);
    }

    // --- Requirement 1.2 : the backfill is idempotent (guarded by MARK_RAN) ---
    @Test
    @DisplayName("Re-running the changelog leaves the populated code state unchanged (idempotent backfill)")
    void reRunningChangelogIsIdempotent() throws Exception {
        Map<String, String> before = snapshotRepresentativeCodes();
        int populatedBefore = countPopulatedCodes();

        // Re-apply the entire changelog. 063c is guarded by MARK_RAN + a sqlCheck that skips the
        // backfill once any code is populated, so a second application changes nothing.
        runLiquibase();

        assertThat(snapshotRepresentativeCodes())
                .as("representative codes unchanged after re-run")
                .isEqualTo(before);
        assertThat(countPopulatedCodes())
                .as("populated code count unchanged after re-run")
                .isEqualTo(populatedBefore);
    }

    // ---------------------------------------------------------------------
    // catalog query helpers
    // ---------------------------------------------------------------------

    private String codeForNamePl(String namePl) throws Exception {
        String sql = "SELECT code FROM work_items WHERE name_pl = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, namePl);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("work item '%s' should be seeded", namePl).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String codeForId(long id) throws Exception {
        String sql = "SELECT code FROM work_items WHERE id = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("work item id %s should exist", id).isTrue();
                return rs.getString(1);
            }
        }
    }

    private int countWorkItems() throws Exception {
        return scalarInt("SELECT COUNT(*) FROM work_items");
    }

    private int countPopulatedCodes() throws Exception {
        return scalarInt("SELECT COUNT(*) FROM work_items WHERE code IS NOT NULL");
    }

    /** Any code value that occurs more than once among the non-null rows (must be empty). */
    private List<String> duplicateCodes() throws Exception {
        String sql = "SELECT code FROM work_items WHERE code IS NOT NULL "
                + "GROUP BY code HAVING COUNT(*) > 1";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> dups = new ArrayList<>();
            while (rs.next()) {
                dups.add(rs.getString(1));
            }
            return dups;
        }
    }

    private Map<String, String> snapshotRepresentativeCodes() throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String namePl : REPRESENTATIVE_CODES.keySet()) {
            snapshot.put(namePl, codeForNamePl(namePl));
        }
        return snapshot;
    }

    private long anyWorkCategoryId() throws Exception {
        return scalarLong("SELECT id FROM work_categories ORDER BY id LIMIT 1");
    }

    private long anyMeasurementUnitId() throws Exception {
        return scalarLong("SELECT id FROM measurement_units ORDER BY id LIMIT 1");
    }

    private long insertWorkItem(long categoryId, long unitId, String namePl, String code) throws Exception {
        String sql = "INSERT INTO work_items (work_category_id, unit_id, name_ru, name_pl, code, "
                + "active, created_date) VALUES (?, ?, ?, ?, ?, TRUE, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, categoryId);
            ps.setLong(2, unitId);
            ps.setString(3, namePl);
            ps.setString(4, namePl);
            if (code == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, code);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void deleteWorkItem(long id) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM work_items WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void deleteWorkItemsByCode(String code) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM work_items WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------
    // information_schema / catalog helpers
    // ---------------------------------------------------------------------

    private boolean columnExists(String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexExists(String indexName) throws Exception {
        String sql = "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' "
                + "AND tablename = 'work_items' AND indexname = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexIsUnique(String indexName) throws Exception {
        String sql = "SELECT ix.indisunique FROM pg_class i "
                + "JOIN pg_index ix ON ix.indexrelid = i.oid "
                + "WHERE i.relname = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** A partial index carries a non-null predicate (pg_index.indpred) — here WHERE code IS NOT NULL. */
    private boolean indexIsPartial(String indexName) throws Exception {
        String sql = "SELECT (ix.indpred IS NOT NULL) FROM pg_class i "
                + "JOIN pg_index ix ON ix.indexrelid = i.oid "
                + "WHERE i.relname = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
