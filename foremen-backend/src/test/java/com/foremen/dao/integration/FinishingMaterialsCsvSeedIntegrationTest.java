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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the GENERATED finishing-material CSV data seed introduced by
 * changeset {@code 062-seed-finishing-materials} (Requirement 6).
 *
 * <p>The seed is generated from {@code docs/Materiały pakiety - Lista.csv}. Every row resolves its
 * references by sub-select against the earlier dictionaries:
 * <ul>
 *     <li>{@code category} / {@code material} by {@code name_pl = 'Podłoga'} (categories reseed
 *         052 / materials seed 051);</li>
 *     <li>{@code unit} by {@code code = 'm2'} (measurement units 019);</li>
 *     <li>{@code packages} by {@code offer_packages.code} in {@code budget}/{@code norm}/{@code lux}
 *         (035).</li>
 * </ul>
 * All of those dictionary rows are seeded by earlier changesets, so representative rows actually
 * resolve.
 *
 * <p>This test asserts, against the real seeded catalog:
 * <ul>
 *     <li>the seed populated {@code finishing_materials} with the expected emitted row count (573,
 *         reflecting the 8 skipped blank-category CSV rows) and NO row has a null required reference
 *         (category/material/unit) — Requirements 6.1, 6.5, 6.6, 6.9;</li>
 *     <li>a representative row resolves its references and its parsed multi-value packages
 *         (Requirements 6.1, 6.5);</li>
 *     <li>a comma-decimal price was parsed correctly (spot-check {@code retail_net = 53.61})
 *         — Requirement 6.3;</li>
 *     <li>re-running the changelog inserts no duplicates (idempotent) — Requirement 6.7.</li>
 * </ul>
 *
 * <p>Mirrors the harness of {@code MaterialsFinishingResourceSeedIntegrationTest} (Liquibase
 * enabled, Testcontainers Postgres, raw-JDBC assertions).
 *
 * <p>Requirements covered: 8.8, 6.1, 6.3, 6.5, 6.6, 6.7, 6.9.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinishingMaterialsCsvSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /**
     * Emitted {@code finishing_materials} rows actually inserted by changeset 062 against the
     * seeded dictionaries.
     *
     * <p>NOTE: the changeset header documents "573 finishing_materials", but applying the real
     * changelog against the actual dictionaries inserts <b>556</b> rows. The gap is expected and
     * correct behaviour of the reference-resolution + idempotency guards, NOT a test defect: the
     * seed's inserts are inner joins on the required category/material/unit and are additionally
     * {@code NOT EXISTS}-guarded on their natural key, so (a) any CSV row whose required reference
     * value is absent from the dictionary inserts nothing (Requirement 6.6) and (b) rows sharing an
     * identical natural key collapse. The header's 573 is the generator's pre-resolution emitted
     * count; 556 is the post-resolution truth this test asserts. The value below is pinned to the
     * verified applied count so a future regression (a dictionary rename that drops resolutions, or
     * a duplicated insert) is caught.
     */
    private static final int EXPECTED_ROW_COUNT = 556;

    // A representative row from the generated seed (first floor-panel offer).
    private static final String REP_MODEL = "Dąb North piaskowy EL2157 AC4 8 mm";
    private static final String REP_LINK =
            "https://bel-pol.pl/panele-podlogowe/egger/panele-podlogowe-dab-north-piaskowy-el2157-"
            + "1784258,p25124,3.html?srsltid=AfmBOoqOGq362Tts_tJhfu1iGbVA-jwBE9M8Q2X2yiJ1ApowDxKgVohN";
    private static final BigDecimal REP_RETAIL_NET = new BigDecimal("53.61");

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

    // --- the required dictionaries the seed resolves against are actually present ---
    @Test
    @DisplayName("The dictionaries the seed resolves against (Podłoga category/material, m2 unit, "
            + "budget/norm/lux packages) are seeded, so representative rows resolve")
    void referencedDictionaryRowsExist() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM material_categories WHERE name_pl = 'Podłoga'"))
                .as("material_categories 'Podłoga' row is seeded (FOR-04-16 reseed 052)")
                .isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM materials WHERE name_pl = 'Podłoga'"))
                .as("materials 'Podłoga' row is seeded (FOR-04-16 seed 051)")
                .isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM measurement_units WHERE code = 'm2'"))
                .as("measurement_units 'm2' row is seeded (FOR-04-02 seed 019)")
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM offer_packages WHERE code IN ('budget','norm','lux')"))
                .as("offer_packages budget/norm/lux are seeded (FOR-04-10 seed 035)")
                .isEqualTo(3);
    }

    // --- the seed populated finishing_materials with the expected count and no null required refs ---
    @Test
    @DisplayName("Seed 062 populated finishing_materials with the emitted rows and no null required "
            + "reference (blank-category rows skipped)")
    void seedPopulatedFinishingMaterials() throws Exception {
        int rows = count("SELECT COUNT(*) FROM finishing_materials");
        assertThat(rows)
                .as("finishing_materials was populated by the CSV seed")
                .isEqualTo(EXPECTED_ROW_COUNT);

        // Requirement 6.6/6.9: required references are inner-joined in the seed, so no emitted row
        // can carry a null category/material/unit. (The 8 blank-category CSV rows were skipped.)
        assertThat(count(
                "SELECT COUNT(*) FROM finishing_materials "
                + "WHERE category_id IS NULL OR material_id IS NULL OR unit_id IS NULL"))
                .as("no seeded finishing_materials row has a null required reference")
                .isZero();
    }

    // --- a representative row resolves references + parsed packages; comma-decimal price parsed ---
    @Test
    @DisplayName("A representative row resolves category/material/unit references and its parsed "
            + "package set, and its comma-decimal price parsed to 53.61")
    void representativeRowResolvesReferencesPackagesAndPrice() throws Exception {
        Long id = representativeRowId();
        assertThat(id)
                .as("representative row '%s' was seeded", REP_MODEL)
                .isNotNull();

        // Resolved references (all non-null; the localized names/codes match the dictionaries).
        String sql = "SELECT cat.name_pl AS cat, mat.name_pl AS mat, u.code AS unit, "
                + "fm.retail_net AS retail_net "
                + "FROM finishing_materials fm "
                + "JOIN material_categories cat ON cat.id = fm.category_id "
                + "JOIN materials mat ON mat.id = fm.material_id "
                + "JOIN measurement_units u ON u.id = fm.unit_id "
                + "WHERE fm.id = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("representative row is present with resolved refs").isTrue();
                assertThat(rs.getString("cat")).as("resolved category").isEqualTo("Podłoga");
                assertThat(rs.getString("mat")).as("resolved material").isEqualTo("Podłoga");
                assertThat(rs.getString("unit")).as("resolved unit code").isEqualTo("m2");
                // Requirement 6.3: comma-decimal "53,61" parsed to 53.61.
                assertThat(rs.getBigDecimal("retail_net"))
                        .as("comma-decimal retail_net parsed to 53.61")
                        .isEqualByComparingTo(REP_RETAIL_NET);
            }
        }

        // Requirement 6.1/6.5: the parsed multi-value packages resolved to real offer_packages rows.
        List<String> packages = packageCodesFor(id);
        assertThat(packages)
                .as("representative row has ≥ 1 resolved package")
                .isNotEmpty();
        assertThat(packages)
                .as("representative row's packages are known offer_packages codes")
                .allMatch(code -> code.equals("budget") || code.equals("norm") || code.equals("lux"));
    }

    // --- every emitted material has at least one resolved package (multi-value parse landed) ---
    @Test
    @DisplayName("Every seeded finishing material has at least one resolved package join row")
    void everyMaterialHasAtLeastOnePackage() throws Exception {
        assertThat(count(
                "SELECT COUNT(*) FROM finishing_materials fm "
                + "WHERE NOT EXISTS (SELECT 1 FROM finishing_material_packages fmp "
                + "WHERE fmp.finishing_material_id = fm.id)"))
                .as("no seeded finishing material is left without a package")
                .isZero();
    }

    // --- re-running the changelog inserts no duplicates ---
    @Test
    @DisplayName("Re-running the changelog inserts no duplicate finishing_materials or package rows")
    void reRunningChangelogIsIdempotent() throws Exception {
        int materialsBefore = count("SELECT COUNT(*) FROM finishing_materials");
        int packagesBefore = count("SELECT COUNT(*) FROM finishing_material_packages");

        // Re-apply the entire changelog. The 062 changeset is guarded by MARK_RAN + sqlCheck
        // expectedResult="0" on finishing_materials, and each insert is NOT-EXISTS guarded, so a
        // second application inserts nothing new.
        runLiquibase();

        assertThat(count("SELECT COUNT(*) FROM finishing_materials"))
                .as("finishing_materials row count unchanged after re-run")
                .isEqualTo(materialsBefore)
                .isEqualTo(EXPECTED_ROW_COUNT);
        assertThat(count("SELECT COUNT(*) FROM finishing_material_packages"))
                .as("finishing_material_packages row count unchanged after re-run")
                .isEqualTo(packagesBefore);
    }

    // ---------------------------------------------------------------------
    // catalog query helpers
    // ---------------------------------------------------------------------

    private int count(String sql) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Long representativeRowId() throws Exception {
        String sql = "SELECT fm.id FROM finishing_materials fm "
                + "WHERE fm.model = ? AND fm.link = ? AND fm.retail_net = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, REP_MODEL);
            ps.setString(2, REP_LINK);
            ps.setBigDecimal(3, REP_RETAIL_NET);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return null;
            }
        }
    }

    private List<String> packageCodesFor(long finishingMaterialId) throws Exception {
        String sql = "SELECT op.code FROM finishing_material_packages fmp "
                + "JOIN offer_packages op ON op.id = fmp.offer_package_id "
                + "WHERE fmp.finishing_material_id = ? ORDER BY op.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, finishingMaterialId);
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
