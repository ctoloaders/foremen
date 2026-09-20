package com.foremen.dao.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the GENERATED {@code work_material_consumptions} CSV data seed
 * introduced by changeset {@code 069-seed-work-material-consumptions} (FOR-04-19, Requirement 7).
 *
 * <p>The seed is generated from {@code FOR-04-RESEARCH-material-norms/work-material-norms.csv}. Each
 * emitted row is one {@code (work item, offer package, material TYPE)} norm; the row references a
 * material TYPE (the analog GROUP), NEVER a concrete material. References resolve by sub-select at
 * apply time:
 * <ul>
 *     <li>{@code work_code} &rarr; {@code work_items.code} (the {@code N.MM} natural key backfilled
 *         by changeset 063);</li>
 *     <li>package token &rarr; {@code offer_packages.code} ({@code budget}/{@code norm}/{@code lux});</li>
 *     <li>{@code material_unit} &rarr; {@code measurement_units.code};</li>
 *     <li>{@code material_type} &rarr; the branch's type dictionary — construction resolves to
 *         {@code construction_material_types.code}.</li>
 * </ul>
 *
 * <p><b>Actual seeded state (asserted here).</b> The consumption seed emits BOTH construction- and
 * finishing-branch rows. The construction branch resolves against
 * {@code construction_material_types}; the finishing branch resolves against the FOR-04-16
 * {@code material_types} dictionary. Historically the finishing branch inserted nothing because the
 * CSV uses UPPER_SNAKE analog codes (e.g. {@code KLEJ_DO_PLYTEK}) absent from {@code material_types}
 * (which used lowercase semantic codes). Changeset {@code 070-seed-finishing-material-types-research}
 * — registered BEFORE 069 in the changelog — seeds those UPPER_SNAKE finishing analog types, so the
 * finishing {@code (row, package)} pairs now resolve and insert finishing rows (Requirement 7.3 /
 * 7.8). This test therefore asserts BOTH branches are present, each row carries exactly one
 * type-id matching its branch (a construction row has a non-null construction type + NULL finishing
 * type, a finishing row the reverse), branch is preserved from the CSV, justification + citation are
 * populated, and multi-package CSV rows fan out into one row per package.
 *
 * <p>Representative work item {@code '1.01'} carries two construction analog types, each fanned
 * across all three offer packages ({@code budget}/{@code norm}/{@code lux}) — one row per package
 * (Requirement 7.3 fan-out):
 * <ul>
 *     <li>{@code FOLIA_OCHRONNA} in unit {@code m2};</li>
 *     <li>{@code TASMA_MALARSKA} in unit {@code szt}.</li>
 * </ul>
 *
 * <p>This test asserts, against the real seeded catalog:
 * <ul>
 *     <li>the seed populated {@code work_material_consumptions} with BOTH branches — a non-zero
 *         construction subset and a non-zero finishing subset — and every seed row carries EXACTLY
 *         ONE type-id matching its branch (construction &rarr; construction type + NULL finishing
 *         type; finishing &rarr; finishing type + NULL construction type) — Requirements 7.2, 7.3,
 *         7.7, 7.8;</li>
 *     <li>every seed row resolved its required references (work item / offer package / material
 *         unit all non-null) — Requirement 7.2, 7.3;</li>
 *     <li>representative {@code '1.01'} rows exist with preserved branch, justification (RU + PL),
 *         and citation ({@code source_type}/{@code source_doc}/{@code source_ref}) — Requirements
 *         7.3, 7.7;</li>
 *     <li>the multi-package CSV rows fanned into exactly one consumption row per package
 *         (budget/norm/lux) for each of the two representative types — Requirement 7.3;</li>
 *     <li>re-running the changelog inserts no duplicates (idempotent) — Requirement 7.6.</li>
 * </ul>
 *
 * <p>Mirrors the harness of {@code FinishingMaterialsCsvSeedIntegrationTest} (Liquibase enabled,
 * Testcontainers Postgres, raw-JDBC assertions).
 *
 * <p>Validates: Requirements 7.2, 7.3, 7.6, 7.7, 10.6, 10.7.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkMaterialConsumptionSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /** created_by marker of the construction consumption seed (changeset 069). */
    private static final String SEED_MARKER = "seed:for-04-19";

    /** created_by marker of the finishing consumption seed (changeset 071). */
    private static final String SEED_MARKER_FINISHING = "seed:for-04-19-finishing";

    /**
     * SQL predicate matching EITHER seed marker, so counts span both the construction subset
     * (069, {@code seed:for-04-19}) and the finishing subset (071, {@code seed:for-04-19-finishing}).
     */
    private static final String SEED_FILTER =
            "created_by IN ('seed:for-04-19', 'seed:for-04-19-finishing')";

    /**
     * The number of CONSTRUCTION consumption rows actually inserted by changeset 069 against the
     * seeded dictionaries.
     *
     * <p>NOTE: the changeset header documents 1021 emitted construction rows, but applying the real
     * changelog against the actual dictionaries inserts <b>993</b> construction rows. The gap is
     * expected and correct behaviour of the reference-resolution + idempotency guards, NOT a test
     * defect (the same phenomenon {@code FinishingMaterialsCsvSeedIntegrationTest} documents for its
     * seed): each insert is an inner join on the required work item / offer package / material unit
     * / material type and is additionally {@code NOT EXISTS}-guarded on its natural key, so (a) any
     * {@code (row, package)} whose required reference value is absent from the dictionary inserts
     * nothing (Requirement 7.4) and (b) rows sharing an identical natural key collapse.
     */
    private static final int EXPECTED_CONSTRUCTION_ROW_COUNT = 993;

    /**
     * The number of FINISHING consumption rows actually inserted by changeset 071
     * ({@code 071-seed-work-material-consumptions-finishing}) once changeset 070 has seeded the
     * UPPER_SNAKE finishing analog types the CSV references.
     *
     * <p>Before this work the finishing branch was EMPTY (the finishing вилка = 0 gap): the
     * generated 069 changeset emitted ONLY construction INSERTs — its generator skipped every
     * finishing {@code (row, package)} pair because the CSV's UPPER_SNAKE analog codes were absent
     * from {@code material_types}. Changeset 070 seeds those 21 analog types and changeset 071
     * supplies the 154 finishing {@code INSERT...SELECT} rows the generator had skipped. Like the
     * construction subset this is the POST-resolution applied truth, not the CSV's raw 154
     * finishing (row, package) count: a pair still fails to resolve if its {@code work_code},
     * package token or unit is absent from the seeded dictionaries, and identical natural keys
     * collapse via the {@code NOT EXISTS} guard. Pinned to the verified applied count so a
     * regression that drops finishing resolutions (or reverts 070/071) is caught.
     *
     * <p>The CSV yields 154 finishing (row, package) pairs; applying the real changelog inserts
     * <b>124</b> of them — the remaining 30 pairs fail to resolve because their {@code work_code}
     * is not among the backfilled {@code work_items.code} values (or their package/unit is absent),
     * so their inner-join {@code INSERT...SELECT} inserts nothing (Requirement 7.4). 124 is the
     * post-resolution applied truth (was 0 before this work — the finishing вилка = 0 gap).
     */
    private static final int EXPECTED_FINISHING_ROW_COUNT = 124;

    /** Total seeded consumption rows = construction subset + finishing subset. */
    private static final int EXPECTED_SEED_ROW_COUNT =
            EXPECTED_CONSTRUCTION_ROW_COUNT + EXPECTED_FINISHING_ROW_COUNT;

    private static final String REP_WORK_CODE = "1.01";
    private static final List<String> PACKAGE_CODES = List.of("budget", "norm", "lux");

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

    // --- the seed populated work_material_consumptions with BOTH branches ---
    @Test
    @DisplayName("Seed 069 populated work_material_consumptions with BOTH branches; the construction "
            + "and finishing subsets are each non-zero and each row carries exactly one type-id "
            + "matching its branch")
    void seedPopulatedBothBranches() throws Exception {
        assertThat(countSeedRows())
                .as("work_material_consumptions was populated by the CSV seed (construction + finishing)")
                .isEqualTo(EXPECTED_SEED_ROW_COUNT);

        // Requirement 7.7: branch is preserved from the CSV; only 'construction' and 'finishing'
        // values occur and BOTH branches are present.
        int constructionRows = count(
                "SELECT COUNT(*) FROM work_material_consumptions "
                + "WHERE " + SEED_FILTER + " AND branch = 'construction'");
        int finishingRows = count(
                "SELECT COUNT(*) FROM work_material_consumptions "
                + "WHERE " + SEED_FILTER + " AND branch = 'finishing'");

        assertThat(constructionRows)
                .as("construction subset seeded with the verified applied count")
                .isEqualTo(EXPECTED_CONSTRUCTION_ROW_COUNT);
        assertThat(finishingRows)
                .as("finishing subset now resolves and is seeded (finishing вилка = 0 gap closed)")
                .isGreaterThan(0)
                .isEqualTo(EXPECTED_FINISHING_ROW_COUNT);

        // No branch value other than the two enum members.
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions "
                + "WHERE " + SEED_FILTER + " AND branch NOT IN ('construction', 'finishing')"))
                .as("no seeded row has a branch outside {construction, finishing}")
                .isZero();

        // Requirement 7.3: XOR per branch — a construction row sets ONLY the construction type,
        // a finishing row sets ONLY the finishing type.
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions "
                + "WHERE " + SEED_FILTER + " AND branch = 'construction' "
                + "AND (construction_material_type_id IS NULL OR finishing_material_type_id IS NOT NULL)"))
                .as("every seeded construction row has a construction type and a NULL finishing type")
                .isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions "
                + "WHERE " + SEED_FILTER + " AND branch = 'finishing' "
                + "AND (finishing_material_type_id IS NULL OR construction_material_type_id IS NOT NULL)"))
                .as("every seeded finishing row has a finishing type and a NULL construction type")
                .isZero();
    }

    // --- every seed row resolved its required references (no null required FK) ---
    @Test
    @DisplayName("Every seeded consumption row resolved its required references (work item, offer "
            + "package, material unit, construction material type all non-null)")
    void everySeedRowResolvedRequiredReferences() throws Exception {
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions "
                + "WHERE " + SEED_FILTER + " "
                + "AND (work_item_id IS NULL OR offer_package_id IS NULL OR material_unit_id IS NULL)"))
                .as("no seeded consumption row has a null required reference")
                .isZero();

        // The resolved FKs point at real dictionary rows (inner-join proof over the whole seed).
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions wmc "
                + "WHERE wmc." + SEED_FILTER + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_items wi WHERE wi.id = wmc.work_item_id) "))
                .as("every seeded work_item_id resolves to a real work_items row")
                .isZero();
        // Construction rows: their construction_material_type_id resolves to a real type row.
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions wmc "
                + "WHERE wmc." + SEED_FILTER + " AND wmc.branch = 'construction' "
                + "AND NOT EXISTS (SELECT 1 FROM construction_material_types t "
                + "WHERE t.id = wmc.construction_material_type_id)"))
                .as("every seeded construction row's construction_material_type_id resolves to a real type row")
                .isZero();
        // Finishing rows: their finishing_material_type_id resolves to a real material_types row
        // (proving changeset 070 seeded the analog types changeset 071's inserts needed).
        assertThat(count(
                "SELECT COUNT(*) FROM work_material_consumptions wmc "
                + "WHERE wmc." + SEED_FILTER + " AND wmc.branch = 'finishing' "
                + "AND NOT EXISTS (SELECT 1 FROM material_types t "
                + "WHERE t.id = wmc.finishing_material_type_id)"))
                .as("every seeded finishing row's finishing_material_type_id resolves to a real material_types row")
                .isZero();
    }

    // --- a representative finishing row resolves to a seeded finishing analog type (070) ---
    @Test
    @DisplayName("A representative finishing row resolves to a changeset-070-seeded finishing analog "
            + "type (e.g. KLEJ_DO_PLYTEK), carrying a finishing type id and a NULL construction type id")
    void representativeFinishingRowResolvesToSeededType() throws Exception {
        // KLEJ_DO_PLYTEK is the highest-frequency finishing analog type in the CSV (26 rows); at
        // least one of its (work, package) pairs resolves once 070 seeds the type.
        int kleiRows = count(
                "SELECT COUNT(*) FROM work_material_consumptions wmc "
                + "JOIN material_types t ON t.id = wmc.finishing_material_type_id "
                + "WHERE wmc.created_by = '" + SEED_MARKER_FINISHING + "' AND wmc.branch = 'finishing' "
                + "AND t.code = 'KLEJ_DO_PLYTEK' "
                + "AND wmc.construction_material_type_id IS NULL");
        assertThat(kleiRows)
                .as("representative finishing type KLEJ_DO_PLYTEK resolved to seeded rows with a "
                        + "finishing type id and NULL construction type id")
                .isGreaterThan(0);
    }

    // --- representative rows exist with preserved branch, justification and citation ---
    @Test
    @DisplayName("Representative work item '1.01' FOLIA_OCHRONNA/TASMA_MALARSKA rows preserve branch, "
            + "justification (RU + PL) and citation (source_type/source_doc/source_ref)")
    void representativeRowsPreserveBranchJustificationAndCitation() throws Exception {
        assertRepresentativeRow("FOLIA_OCHRONNA", "m2");
        assertRepresentativeRow("TASMA_MALARSKA", "szt");
    }

    private void assertRepresentativeRow(String typeCode, String unitCode) throws Exception {
        String sql = "SELECT wmc.branch, wmc.justification_ru, wmc.justification_pl, "
                + "wmc.source_type, wmc.source_doc, wmc.source_ref, wmc.norm_qty "
                + "FROM work_material_consumptions wmc "
                + "JOIN work_items wi ON wi.id = wmc.work_item_id "
                + "JOIN offer_packages op ON op.id = wmc.offer_package_id "
                + "JOIN measurement_units u ON u.id = wmc.material_unit_id "
                + "JOIN construction_material_types t ON t.id = wmc.construction_material_type_id "
                + "WHERE wi.code = ? AND op.code = 'budget' AND u.code = ? AND t.code = ? "
                + "AND wmc.created_by = '" + SEED_MARKER + "'";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, REP_WORK_CODE);
            ps.setString(2, unitCode);
            ps.setString(3, typeCode);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("representative row '%s'/%s/budget for work '%s' is seeded",
                                typeCode, unitCode, REP_WORK_CODE)
                        .isTrue();
                assertThat(rs.getString("branch"))
                        .as("branch preserved as 'construction' for %s", typeCode)
                        .isEqualTo("construction");
                assertThat(rs.getString("justification_ru"))
                        .as("justification_ru populated for %s", typeCode)
                        .isNotBlank();
                assertThat(rs.getString("justification_pl"))
                        .as("justification_pl populated for %s", typeCode)
                        .isNotBlank();
                assertThat(rs.getString("source_type"))
                        .as("source_type populated for %s", typeCode)
                        .isEqualTo("excel");
                assertThat(rs.getString("source_doc"))
                        .as("source_doc populated for %s", typeCode)
                        .isNotBlank();
                assertThat(rs.getString("source_ref"))
                        .as("source_ref populated for %s", typeCode)
                        .isNotBlank();
                assertThat(rs.getBigDecimal("norm_qty"))
                        .as("norm_qty populated for %s", typeCode)
                        .isNotNull();
            }
        }
    }

    // --- multi-package CSV rows fanned into one row per package ---
    @Test
    @DisplayName("Multi-package CSV rows fanned into exactly one consumption row per offer package "
            + "(budget/norm/lux) for each representative type")
    void multiPackageRowsFannedIntoOneRowPerPackage() throws Exception {
        assertFanOut("FOLIA_OCHRONNA", "m2");
        assertFanOut("TASMA_MALARSKA", "szt");
    }

    private void assertFanOut(String typeCode, String unitCode) throws Exception {
        List<String> packages = packagesFor(REP_WORK_CODE, typeCode, unitCode);
        assertThat(packages)
                .as("work '%s' type %s fanned into one row per package", REP_WORK_CODE, typeCode)
                .containsExactlyInAnyOrderElementsOf(PACKAGE_CODES);
        // Exactly one row per package (no duplicate insert per (work, package, type, unit)).
        assertThat(packages)
                .as("no duplicate package rows for work '%s' type %s", REP_WORK_CODE, typeCode)
                .doesNotHaveDuplicates();
    }

    // --- re-running the changelog inserts no duplicates (Requirement 7.6) ---
    @Test
    @DisplayName("Re-running the changelog inserts no duplicate work_material_consumptions rows")
    void reRunningChangelogIsIdempotent() throws Exception {
        int seedRowsBefore = countSeedRows();
        int totalRowsBefore = count("SELECT COUNT(*) FROM work_material_consumptions");

        // Re-apply the entire changelog. The 069 changeset is guarded by MARK_RAN + sqlCheck
        // expectedResult="0" on created_by = 'seed:for-04-19', and each insert is NOT-EXISTS
        // guarded, so a second application inserts nothing new.
        runLiquibase();

        assertThat(countSeedRows())
                .as("seed row count unchanged after re-run")
                .isEqualTo(seedRowsBefore)
                .isEqualTo(EXPECTED_SEED_ROW_COUNT);
        assertThat(count("SELECT COUNT(*) FROM work_material_consumptions"))
                .as("total work_material_consumptions row count unchanged after re-run")
                .isEqualTo(totalRowsBefore);
    }

    // ---------------------------------------------------------------------
    // catalog query helpers
    // ---------------------------------------------------------------------

    private int countSeedRows() throws Exception {
        return count("SELECT COUNT(*) FROM work_material_consumptions WHERE " + SEED_FILTER);
    }

    private int count(String sql) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<String> packagesFor(String workCode, String typeCode, String unitCode) throws Exception {
        String sql = "SELECT op.code FROM work_material_consumptions wmc "
                + "JOIN work_items wi ON wi.id = wmc.work_item_id "
                + "JOIN offer_packages op ON op.id = wmc.offer_package_id "
                + "JOIN measurement_units u ON u.id = wmc.material_unit_id "
                + "JOIN construction_material_types t ON t.id = wmc.construction_material_type_id "
                + "WHERE wi.code = ? AND u.code = ? AND t.code = ? "
                + "AND wmc.created_by = '" + SEED_MARKER + "' "
                + "ORDER BY op.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workCode);
            ps.setString(2, unitCode);
            ps.setString(3, typeCode);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> codes = new ArrayList<>();
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
                return codes;
            }
        }
    }
}
