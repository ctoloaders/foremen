package com.foremen.dao.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.service.formula.FormulaValidator;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the FOR-05-05 bill-of-materials schema and seed introduced by
 * changesets {@code 102}–{@code 104}:
 * <ul>
 *     <li><b>Schema (102, 103).</b> The two new tables exist after migrate
 *         ({@code estimate_line_room_materials}, {@code work_room_types}) with their documented
 *         UNIQUE constraints — {@code estimate_line_room_materials
 *         (room_qty_id, branch, construction_type_id, finishing_type_id)} (R13.4) and
 *         {@code work_room_types (work_item_id, room_type_id)} (R10.1) — and their documented FK
 *         {@code ON DELETE} actions: the owner {@code room_qty_id} FK is {@code CASCADE} (R19.3),
 *         the two provenance ({@code source_*}) and the two {@code concrete_*} material FKs are
 *         {@code SET NULL} (R13.2), the two type FKs are {@code RESTRICT}/{@code NO ACTION}, and
 *         both {@code work_room_types} FKs are {@code CASCADE}.</li>
 *     <li><b>Seed (104, R17).</b> After migrate <em>every</em> work item has exactly one
 *         {@code work_volume_formulas} row (full coverage, R17.1), and every seeded
 *         {@code parsed_ast} passes {@link FormulaValidator} — it references only the 14 known
 *         room-dimension variables (or a numeric constant), no unknown work refs (R17.2).</li>
 *     <li><b>Idempotent re-migrate (17.3, 17.4, 19.2).</b> Re-running the whole changelog is a
 *         no-op: the two tables and their constraints are unchanged, and — crucially for the
 *         non-overwriting seed guarantee — the {@code work_volume_formulas} rows are
 *         <em>preserved</em>: the total row count is unchanged and each work item's formula
 *         {@code (source_text, parsed_ast)} is byte-for-byte identical before and after the
 *         second changelog application (R17.3, R17.4).</li>
 * </ul>
 *
 * <p>Mirrors the {@code EstimateSchemaMigrationIntegrationTest} /
 * {@code PackageFormulaAssortmentSchemaMigrationIntegrationTest} convention: run the real
 * changelog against a real database via raw Liquibase and assert against the live schema through
 * {@code information_schema} using raw JDBC. The AST-validation half reuses the
 * {@code WorkVolumeFormulaSeedConsistencyTest} approach — deserialize the persisted
 * {@code parsed_ast} into a {@link FormulaAst} with the same Jackson mapper the {@code jsonb}
 * column uses and run {@link FormulaValidator} on it.
 *
 * <p>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 19.2, 19.3
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillOfMaterialsSchemaMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> NEW_TABLES = List.of(
            "estimate_line_room_materials",
            "work_room_types");

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // ------------------------------------------------------------------
    // 102 / 103 : the two new tables exist after migrate (R19.2, R19.3)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Changesets 102-103 create estimate_line_room_materials and work_room_types")
    void createsTheNewTables() throws Exception {
        for (String table : NEW_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s exists after migrate", table)
                    .isTrue();
        }
    }

    // --- R13.4 : material line keyed uniquely per assignment by branch and type ---
    @Test
    @DisplayName("estimate_line_room_materials (room_qty_id, branch, construction_type_id, finishing_type_id) is UNIQUE")
    void estimateLineRoomMaterialsIsKeyedByRoomQtyBranchType() throws Exception {
        assertThat(uniqueConstraintColumns("estimate_line_room_materials"))
                .as("a UNIQUE constraint covers exactly "
                        + "[room_qty_id, branch, construction_type_id, finishing_type_id]")
                .contains(List.of(
                        "room_qty_id", "branch", "construction_type_id", "finishing_type_id"));
    }

    // --- R10.1 : a room type is attached to a work at most once ---
    @Test
    @DisplayName("work_room_types (work_item_id, room_type_id) is UNIQUE")
    void workRoomTypesIsKeyedByWorkItemRoomType() throws Exception {
        assertThat(uniqueConstraintColumns("work_room_types"))
                .as("a UNIQUE constraint covers exactly [work_item_id, room_type_id]")
                .contains(List.of("work_item_id", "room_type_id"));
    }

    // --- R19.3 / R13.2 : estimate_line_room_materials FK delete rules ---
    @Test
    @DisplayName("estimate_line_room_materials FK delete rules: owner CASCADE, source/concrete SET NULL, type RESTRICT")
    void estimateLineRoomMaterialsFkDeleteRules() throws Exception {
        // Owner FK: the material rows cascade-delete with their room-qty (and the owning line) — R19.3.
        assertThat(deleteRuleForForeignKeyColumn("estimate_line_room_materials", "room_qty_id"))
                .as("room_qty_id FK delete rule (owner, cascade) — R19.3")
                .isEqualTo("CASCADE");

        // Provenance (source_*) FKs: SET NULL — provenance never drives value (R13.2).
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_room_materials", "source_construction_material_id"))
                .as("source_construction_material_id FK delete rule (SET NULL) — R13.2")
                .isEqualTo("SET NULL");
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_room_materials", "source_finishing_material_id"))
                .as("source_finishing_material_id FK delete rule (SET NULL) — R13.2")
                .isEqualTo("SET NULL");

        // Chosen Concrete_Material FKs: SET NULL.
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_room_materials", "concrete_construction_material_id"))
                .as("concrete_construction_material_id FK delete rule (SET NULL)")
                .isEqualTo("SET NULL");
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_room_materials", "concrete_finishing_material_id"))
                .as("concrete_finishing_material_id FK delete rule (SET NULL)")
                .isEqualTo("SET NULL");

        // Material TYPE reference FKs: RESTRICT (a referenced type cannot be silently removed).
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_room_materials", "construction_type_id"))
                .as("construction_type_id FK delete rule (RESTRICT)")
                .isIn("RESTRICT", "NO ACTION");
        assertThat(deleteRuleForForeignKeyColumn(
                "estimate_line_room_materials", "finishing_type_id"))
                .as("finishing_type_id FK delete rule (RESTRICT)")
                .isIn("RESTRICT", "NO ACTION");
    }

    // --- work_room_types FK delete rules: both CASCADE ---
    @Test
    @DisplayName("work_room_types FKs (work_item_id, room_type_id) are both ON DELETE CASCADE")
    void workRoomTypesFkDeleteRules() throws Exception {
        assertThat(deleteRuleForForeignKeyColumn("work_room_types", "work_item_id"))
                .as("work_room_types.work_item_id FK delete rule (CASCADE)")
                .isEqualTo("CASCADE");
        assertThat(deleteRuleForForeignKeyColumn("work_room_types", "room_type_id"))
                .as("work_room_types.room_type_id FK delete rule (CASCADE)")
                .isEqualTo("CASCADE");
    }

    // ------------------------------------------------------------------
    // 104 (R17) : full-coverage volume-formula seed
    // ------------------------------------------------------------------

    // --- R17.1 : every work item has exactly one volume formula after migrate ---
    @Test
    @DisplayName("After changeset 104 every work item has exactly one work_volume_formulas row (full coverage)")
    void everyWorkItemHasExactlyOneVolumeFormula() throws Exception {
        long workItemCount = scalarLong("SELECT COUNT(*) FROM work_items");
        assertThat(workItemCount)
                .as("the seeded catalog must contain at least one work item to make coverage meaningful")
                .isGreaterThan(0);

        // No work item is left without a formula (R17.1).
        long uncovered = scalarLong(
                "SELECT COUNT(*) FROM work_items wi "
                        + "WHERE NOT EXISTS "
                        + "(SELECT 1 FROM work_volume_formulas f WHERE f.work_item_id = wi.id)");
        assertThat(uncovered)
                .as("every work item must have a volume formula after 104 (R17.1)")
                .isZero();

        // 0..1 formula per work item is enforced by the work_volume_formulas.work_item_id UNIQUE
        // constraint (changeset 085); combined with the above, coverage is exactly one per work.
        long formulaCount = scalarLong("SELECT COUNT(*) FROM work_volume_formulas");
        assertThat(formulaCount)
                .as("with a UNIQUE work_item_id, full coverage means one formula per work item")
                .isEqualTo(workItemCount);
    }

    // --- R17.2 : every seeded parsed_ast passes FormulaValidator ---
    @Test
    @DisplayName("Every seeded parsed_ast passes FormulaValidator (only the 14 room dimensions, no unknown work refs)")
    void everySeededAstPassesFormulaValidator() throws Exception {
        List<String> asts = allParsedAstJson();
        assertThat(asts)
                .as("there must be seeded volume formulas to validate")
                .isNotEmpty();

        for (String astJson : asts) {
            FormulaAst ast = objectMapper.readValue(astJson, FormulaAst.class);
            // No known work refs: every seeded default formula references only room dimensions
            // (or a numeric constant), so validation must pass with an empty ref set (R17.2).
            FormulaValidator.validate(ast, Set.of());
        }
    }

    // ------------------------------------------------------------------
    // Re-migrate is a no-op AND preserves existing formulas (17.3, 17.4, 19.2)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Re-running the changelog preserves the schema and every existing volume formula unchanged")
    void reRunningChangelogIsNoOpAndPreservesFormulas() throws Exception {
        // Snapshot the seeded formulas (work_item_id -> "source_text||parsed_ast") before re-run.
        Map<Long, String> before = formulaFingerprints();
        long countBefore = before.size();

        // Re-apply the entire changelog. 102/103 are guarded by NOT tableExists + MARK_RAN and 104
        // by a per-row NOT EXISTS + MARK_RAN, so a second application makes no schema change and
        // inserts/overwrites no formula (R17.3, R17.4, R19.2).
        runLiquibase();

        // Schema unchanged.
        for (String table : NEW_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s still exists after re-migrate", table)
                    .isTrue();
        }
        assertThat(uniqueConstraintColumns("estimate_line_room_materials"))
                .as("estimate_line_room_materials UNIQUE constraint unchanged after re-migrate")
                .contains(List.of(
                        "room_qty_id", "branch", "construction_type_id", "finishing_type_id"));
        assertThat(uniqueConstraintColumns("work_room_types"))
                .as("work_room_types UNIQUE constraint unchanged after re-migrate")
                .contains(List.of("work_item_id", "room_type_id"));

        // Formulas preserved: same count, and each work item's (source_text, parsed_ast) identical.
        Map<Long, String> after = formulaFingerprints();
        assertThat((long) after.size())
                .as("re-migrate must not add or drop any volume formula (R17.3, R17.4)")
                .isEqualTo(countBefore);
        assertThat(after)
                .as("re-migrate must leave every existing work-volume formula byte-for-byte "
                        + "unchanged (non-overwriting seed) (R17.3)")
                .isEqualTo(before);
    }

    // ---------------------------------------------------------------------
    // query helpers
    // ---------------------------------------------------------------------

    /** A map of {@code work_item_id -> "<source_text>||<parsed_ast text>"} for every seeded formula. */
    private Map<Long, String> formulaFingerprints() throws Exception {
        String sql = "SELECT work_item_id, source_text, parsed_ast::text FROM work_volume_formulas";
        Map<Long, String> out = new HashMap<>();
        try (Connection c = newConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong(1), rs.getString(2) + "||" + rs.getString(3));
            }
        }
        return out;
    }

    /** The {@code parsed_ast} JSON text of every seeded volume formula. */
    private List<String> allParsedAstJson() throws Exception {
        String sql = "SELECT parsed_ast::text FROM work_volume_formulas";
        List<String> out = new ArrayList<>();
        try (Connection c = newConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection c = newConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

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
