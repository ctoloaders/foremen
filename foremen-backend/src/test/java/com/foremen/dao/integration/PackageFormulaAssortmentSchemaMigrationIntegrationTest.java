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
 * (Testcontainers) and verifies the FOR-05-04 formula-engine/package-override/assortment schema
 * introduced by changesets {@code 085}–{@code 088}:
 * <ul>
 *     <li>the four tables exist after migrate: {@code work_volume_formulas},
 *         {@code work_package_overrides}, {@code assortment_groups},
 *         {@code assortment_line_items} (Requirements 2.1, 4.1, 6.1);</li>
 *     <li>the {@code work_volume_formulas.work_item_id} UNIQUE constraint exists (0..1 formula per
 *         work item, Requirement 2.1);</li>
 *     <li>the {@code work_package_overrides (work_item_id, offer_package_id)} UNIQUE constraint
 *         exists (exactly one override row per pair, Requirement 4.1);</li>
 *     <li>the {@code assortment_line_items.typical_material_id} provenance FK is
 *         {@code ON DELETE SET NULL} (Requirement 6.7);</li>
 *     <li>re-running the changelog is a no-op: every 085-088 changeset is guarded by
 *         {@code tableExists} + {@code MARK_RAN}, so a second application changes no schema.</li>
 * </ul>
 *
 * <p>Mirrors the {@code EstimateSchemaMigrationIntegrationTest} convention: run the real changelog
 * against a real database via raw Liquibase and assert against the live schema through
 * {@code information_schema} using raw JDBC.
 *
 * <p>Validates: Requirements 2.1, 4.1, 6.1, 6.7
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageFormulaAssortmentSchemaMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> NEW_TABLES = List.of(
            "work_volume_formulas",
            "work_package_overrides",
            "assortment_groups",
            "assortment_line_items");

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

    // --- Requirements 2.1, 4.1, 6.1 : the four new tables exist after migrate ---
    @Test
    @DisplayName("Changesets 085-088 create the four new tables")
    void createsTheFourNewTables() throws Exception {
        for (String table : NEW_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s exists after migrate", table)
                    .isTrue();
        }
    }

    // --- Requirement 2.1 : work_volume_formulas.work_item_id UNIQUE (0..1 formula per work) ---
    @Test
    @DisplayName("work_volume_formulas.work_item_id has a UNIQUE constraint")
    void workVolumeFormulasWorkItemIdIsUnique() throws Exception {
        assertThat(uniqueConstraintColumns("work_volume_formulas"))
                .as("a UNIQUE constraint on work_volume_formulas covers exactly [work_item_id]")
                .contains(List.of("work_item_id"));
    }

    // --- Requirement 4.1 : (work_item_id, offer_package_id) UNIQUE — one override row per pair ---
    @Test
    @DisplayName("work_package_overrides (work_item_id, offer_package_id) is UNIQUE")
    void workPackageOverridesWorkItemPackageIsUnique() throws Exception {
        assertThat(uniqueConstraintColumns("work_package_overrides"))
                .as("a UNIQUE constraint covers exactly [work_item_id, offer_package_id]")
                .contains(List.of("work_item_id", "offer_package_id"));
    }

    // --- Requirement 6.7 : assortment_line_items.typical_material_id FK is ON DELETE SET NULL ---
    @Test
    @DisplayName("assortment_line_items.typical_material_id provenance FK is ON DELETE SET NULL")
    void assortmentLineItemsTypicalMaterialFkIsSetNull() throws Exception {
        assertThat(deleteRuleForForeignKeyColumn("assortment_line_items", "typical_material_id"))
                .as("assortment_line_items.typical_material_id FK delete rule")
                .isEqualTo("SET NULL");
    }

    // --- Functional check on Requirement 6.7 : deleting the material nulls the FK, row survives ---
    @Test
    @DisplayName("Deleting the referenced material sets typical_material_id to NULL and keeps the row")
    void deletingTypicalMaterialNullsReferenceAndKeepsLineItem() throws Exception {
        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            try {
                long groupId = insertAssortmentGroup(c, "SCHEMA_TEST_GROUP");
                long offerPackageId = firstOfferPackageId(c);
                long materialId = insertMinimalMaterial(c, "SCHEMA_TEST_MATERIAL");
                long lineItemId = insertAssortmentLineItem(
                        c, groupId, offerPackageId, "SCHEMA_TEST_LINE_ITEM", materialId);

                try (PreparedStatement ps =
                        c.prepareStatement("DELETE FROM materials WHERE id = ?")) {
                    ps.setLong(1, materialId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT typical_material_id FROM assortment_line_items WHERE id = ?")) {
                    ps.setLong(1, lineItemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next())
                                .as("the assortment line item row still exists after material delete")
                                .isTrue();
                        assertThat(rs.getObject(1))
                                .as("typical_material_id is NULL after the referenced material is deleted")
                                .isNull();
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    // --- Re-migrate is a no-op : tables + constraints unchanged after a second changelog run ---
    @Test
    @DisplayName("Re-running the changelog is a no-op for the new formula/override/assortment schema")
    void reRunningChangelogIsNoOp() throws Exception {
        // Re-apply the entire changelog. Every 085-088 changeset is guarded by tableExists +
        // MARK_RAN, so a second application makes no schema change.
        runLiquibase();

        for (String table : NEW_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s still exists after re-migrate", table)
                    .isTrue();
        }
        assertThat(uniqueConstraintColumns("work_volume_formulas"))
                .as("work_volume_formulas UNIQUE constraints unchanged after re-migrate")
                .contains(List.of("work_item_id"));
        assertThat(uniqueConstraintColumns("work_package_overrides"))
                .as("work_package_overrides UNIQUE constraints unchanged after re-migrate")
                .contains(List.of("work_item_id", "offer_package_id"));
        assertThat(deleteRuleForForeignKeyColumn("assortment_line_items", "typical_material_id"))
                .as("assortment_line_items.typical_material_id FK delete rule unchanged after re-migrate")
                .isEqualTo("SET NULL");
    }

    // ---------------------------------------------------------------------
    // Fixture helpers (minimal inserts to exercise the ON DELETE SET NULL FK)
    // ---------------------------------------------------------------------

    private long insertAssortmentGroup(Connection c, String namePrefix) throws Exception {
        String sql = "INSERT INTO assortment_groups (name_ru, name_pl, sort_order) "
                + "VALUES (?, ?, 0) RETURNING id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, namePrefix + "_RU");
            ps.setString(2, namePrefix + "_PL");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long firstOfferPackageId(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM offer_packages ORDER BY id LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Inserts a minimal {@code materials} row satisfying only its NOT NULL columns
     * ({@code code}, {@code name_ru}, {@code name_pl}; see {@code 050-create-materials.xml}).
     */
    private long insertMinimalMaterial(Connection c, String namePrefix) throws Exception {
        String sql = "INSERT INTO materials (code, name_ru, name_pl) VALUES (?, ?, ?) RETURNING id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, namePrefix + "_" + System.nanoTime());
            ps.setString(2, namePrefix + "_RU");
            ps.setString(3, namePrefix + "_PL");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertAssortmentLineItem(
            Connection c, long groupId, long offerPackageId, String namePrefix, Long typicalMaterialId)
            throws Exception {
        String sql = "INSERT INTO assortment_line_items "
                + "(assortment_group_id, offer_package_id, name_ru, name_pl, min_price, avg_price, "
                + "max_price, qty_ref50, typical_material_id) "
                + "VALUES (?, ?, ?, ?, 10, 20, 30, 1, ?) RETURNING id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, offerPackageId);
            ps.setString(3, namePrefix + "_RU");
            ps.setString(4, namePrefix + "_PL");
            if (typicalMaterialId != null) {
                ps.setLong(5, typicalMaterialId);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ---------------------------------------------------------------------
    // information_schema query helpers
    // ---------------------------------------------------------------------

    /** Whether a base table with the given name exists in the public schema. */
    private boolean tableExists(String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ? AND table_type = 'BASE TABLE'";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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
