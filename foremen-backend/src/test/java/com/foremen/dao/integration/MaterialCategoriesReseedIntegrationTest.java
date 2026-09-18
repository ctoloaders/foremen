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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code material_categories} DATA-ONLY re-seed introduced by
 * changeset {@code 052-reseed-material-categories} (FOR-04-16 Track D):
 * <ul>
 *     <li>the two obsolete rows {@code construction} and {@code finishing} (seeded in changeset
 *         {@code 033}) are ABSENT — deleted by {@code 052};</li>
 *     <li>the 5 new {@code Kategoria} codes ({@code drzwi}, {@code listwy_przypodlogowe},
 *         {@code podloga}, {@code plytki}, {@code sanitariat}) are PRESENT with the correct
 *         {@code nameRU}/{@code namePL};</li>
 *     <li>re-running the full changelog is idempotent: {@code construction}/{@code finishing} stay
 *         absent, the 5 rows stay present, and row counts are unchanged.</li>
 * </ul>
 *
 * <p>Mirrors {@code MaterialTypesResourceSeedIntegrationTest}: run the real changelog against a real
 * database and assert against the live catalog via raw JDBC. Per the task scope, this test does NOT
 * assert on the {@code MATERIAL_CATEGORIES} resource row or its ABAC grants — changeset {@code 052}
 * does not touch them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaterialCategoriesReseedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> OBSOLETE_CODES = List.of("construction", "finishing");

    /** The 5 new codes seeded by changeset 052, mapped to their expected (nameRU, namePL). */
    private static final Map<String, String[]> NEW_CATEGORIES = new LinkedHashMap<>() {{
        put("drzwi", new String[] {"Двери", "Drzwi"});
        put("listwy_przypodlogowe", new String[] {"Плинтусы напольные", "Listwy przypodłogowe"});
        put("podloga", new String[] {"Пол", "Podłoga"});
        put("plytki", new String[] {"Плитка", "Płytki"});
        put("sanitariat", new String[] {"Сантехника", "Sanitariat"});
    }};

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

    // --- the obsolete construction/finishing rows are deleted by 052 ---
    @Test
    @DisplayName("Changeset 052 removes the obsolete 'construction' and 'finishing' categories")
    void removesObsoleteCategories() throws Exception {
        for (String code : OBSOLETE_CODES) {
            assertThat(categoryExists(code))
                    .as("obsolete category '%s' must be absent after re-seed", code)
                    .isFalse();
        }
    }

    // --- the 5 new Kategoria codes are present with correct nameRU/namePL ---
    @Test
    @DisplayName("Changeset 052 seeds the 5 new Kategoria codes with correct nameRU/namePL")
    void seedsNewCategories() throws Exception {
        for (Map.Entry<String, String[]> entry : NEW_CATEGORIES.entrySet()) {
            String code = entry.getKey();
            String expectedNameRu = entry.getValue()[0];
            String expectedNamePl = entry.getValue()[1];

            assertThat(categoryExists(code))
                    .as("new category '%s' must be present after re-seed", code)
                    .isTrue();
            assertThat(nameRuOf(code))
                    .as("nameRU of '%s'", code)
                    .isEqualTo(expectedNameRu);
            assertThat(namePlOf(code))
                    .as("namePL of '%s'", code)
                    .isEqualTo(expectedNamePl);
        }
    }

    // --- re-running the changelog changes nothing ---
    @Test
    @DisplayName("Re-running the changelog leaves the re-seed unchanged (idempotent)")
    void reRunningChangelogIsIdempotent() throws Exception {
        int totalBefore = countCategories();
        Map<String, Boolean> obsoleteBefore = snapshotObsoletePresence();
        Map<String, String[]> newBefore = snapshotNewCategories();

        // Re-apply the entire changelog. The 052 changesets are guarded by MARK_RAN preconditions
        // (delete: sqlCheck expectedResult="2"; each insert: sqlCheck expectedResult="0"), so a
        // second application deletes nothing and inserts nothing new.
        runLiquibase();

        assertThat(countCategories())
                .as("material_categories row count unchanged after re-run")
                .isEqualTo(totalBefore);

        // Obsolete rows remain absent.
        for (String code : OBSOLETE_CODES) {
            assertThat(categoryExists(code))
                    .as("obsolete category '%s' stays absent after re-run", code)
                    .isFalse();
        }
        assertThat(snapshotObsoletePresence())
                .as("obsolete presence snapshot unchanged after re-run")
                .isEqualTo(obsoleteBefore);

        // The 5 new rows remain present with unchanged names.
        Map<String, String[]> newAfter = snapshotNewCategories();
        for (String code : NEW_CATEGORIES.keySet()) {
            assertThat(categoryExists(code))
                    .as("new category '%s' stays present after re-run", code)
                    .isTrue();
            assertThat(newAfter.get(code))
                    .as("names of '%s' unchanged after re-run", code)
                    .containsExactly(newBefore.get(code));
        }
    }

    // ---------------------------------------------------------------------
    // catalog query helpers
    // ---------------------------------------------------------------------

    private boolean categoryExists(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM material_categories WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private String nameRuOf(String code) throws Exception {
        return nameColumnOf(code, "name_ru");
    }

    private String namePlOf(String code) throws Exception {
        return nameColumnOf(code, "name_pl");
    }

    private String nameColumnOf(String code, String column) throws Exception {
        String sql = "SELECT " + column + " FROM material_categories WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
            }
        }
    }

    private int countCategories() throws Exception {
        String sql = "SELECT COUNT(*) FROM material_categories";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** obsolete code -> present? */
    private Map<String, Boolean> snapshotObsoletePresence() throws Exception {
        Map<String, Boolean> snapshot = new LinkedHashMap<>();
        for (String code : OBSOLETE_CODES) {
            snapshot.put(code, categoryExists(code));
        }
        return snapshot;
    }

    /** new code -> [name_ru, name_pl] for rows that exist. */
    private Map<String, String[]> snapshotNewCategories() throws Exception {
        Map<String, String[]> snapshot = new LinkedHashMap<>();
        for (String code : NEW_CATEGORIES.keySet()) {
            snapshot.put(code, new String[] {nameRuOf(code), namePlOf(code)});
        }
        return snapshot;
    }
}
