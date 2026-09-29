package com.foremen.dao.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
 * (Testcontainers) and verifies the FOR-05-05 <b>Room_Type_Attachment seed</b> introduced by
 * changeset {@code 105} (R10.6) — the meaningful category → room-type seed of the
 * {@code work_room_types} M:N (mapping B).
 *
 * <p>After migrate the seed asserts, per work category (resolved by {@code work_categories.code},
 * matched to each work via {@code work_items.work_category_id}):
 * <ul>
 *     <li><b>Wet works</b> ({@code TILING}, {@code PLUMBING_ROUGH}, {@code PLUMBING_FINISH}) — each
 *         such work item is attached to <em>exactly</em> the wet rooms {@code {kuchnia, lazienka}}.</li>
 *     <li><b>FLOORS</b> — each work item is attached to <em>exactly</em> the 6 dry rooms
 *         {@code {przedpokoj, hol, salon, biuro, master, pokoj}} (nothing in kitchen/bathroom).</li>
 *     <li><b>CARPENTRY</b> — each work item is attached to <em>exactly</em> the same 6 dry
 *         living/circulation rooms.</li>
 *     <li><b>Universal categories</b> (e.g. {@code PAINTING_DECOR}, {@code ELECTRICAL_ROUGH},
 *         {@code PRELIMINARY}) — every such work item has ZERO attachment rows (empty ⇒ all rooms
 *         on apply, R10.3).</li>
 * </ul>
 *
 * <p><b>Idempotency (R10.6, R19.2).</b> Re-running the whole changelog is a no-op: the total
 * {@code work_room_types} row count is unchanged after a second application (the changeset is
 * guarded by {@code onFail="MARK_RAN"} + {@code tableExists} and each INSERT by a per-work
 * {@code NOT EXISTS} guard, so nothing is duplicated or overwritten).
 *
 * <p>Mirrors {@code BillOfMaterialsSchemaMigrationIntegrationTest}: run the real changelog against
 * a real database via raw Liquibase and assert against the live data through raw JDBC.
 *
 * <p>Validates: Requirements 10.6, 19.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkRoomTypeSeedSchemaMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final Set<String> WET_ROOMS = Set.of("kuchnia", "lazienka");
    private static final Set<String> DRY_ROOMS =
            Set.of("przedpokoj", "hol", "salon", "biuro", "master", "pokoj");

    private static final List<String> WET_CATEGORIES =
            List.of("TILING", "PLUMBING_ROUGH", "PLUMBING_FINISH");
    private static final List<String> UNIVERSAL_CATEGORIES = List.of(
            "PRELIMINARY", "CONSTRUCTIONS_GK", "ELECTRICAL_ROUGH", "ELECTRICAL_FINISH",
            "PLASTERING", "PAINTING_DECOR", "EXTRAS", "OTHER");

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
    // 105 (R10.6) : wet works attach to exactly {kuchnia, lazienka}
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Every TILING/PLUMBING_ROUGH/PLUMBING_FINISH work item is attached to exactly {kuchnia, lazienka}")
    void wetWorksAttachedToWetRoomsOnly() throws Exception {
        List<Long> wetWorkItems = workItemIdsForCategories(WET_CATEGORIES);
        assertThat(wetWorkItems)
                .as("the seeded catalog must contain at least one wet-category work item")
                .isNotEmpty();

        for (Long workItemId : wetWorkItems) {
            assertThat(attachedRoomCodes(workItemId))
                    .as("wet work item %s attaches to exactly the wet rooms", workItemId)
                    .isEqualTo(new TreeSet<>(WET_ROOMS));
        }
    }

    // ------------------------------------------------------------------
    // 105 (R10.6) : FLOORS attach to exactly the 6 dry rooms
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Every FLOORS work item is attached to exactly the 6 dry rooms (nothing in kuchnia/lazienka)")
    void floorsAttachedToDryRoomsOnly() throws Exception {
        List<Long> floorWorkItems = workItemIdsForCategories(List.of("FLOORS"));
        assertThat(floorWorkItems)
                .as("the seeded catalog must contain at least one FLOORS work item")
                .isNotEmpty();

        for (Long workItemId : floorWorkItems) {
            assertThat(attachedRoomCodes(workItemId))
                    .as("FLOORS work item %s attaches to exactly the 6 dry rooms", workItemId)
                    .isEqualTo(new TreeSet<>(DRY_ROOMS));
        }
    }

    // ------------------------------------------------------------------
    // 105 (R10.6) : CARPENTRY attach to exactly the 6 dry living/circulation rooms
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Every CARPENTRY work item is attached to exactly the 6 dry living/circulation rooms")
    void carpentryAttachedToDryRoomsOnly() throws Exception {
        List<Long> carpentryWorkItems = workItemIdsForCategories(List.of("CARPENTRY"));
        assertThat(carpentryWorkItems)
                .as("the seeded catalog must contain at least one CARPENTRY work item")
                .isNotEmpty();

        for (Long workItemId : carpentryWorkItems) {
            assertThat(attachedRoomCodes(workItemId))
                    .as("CARPENTRY work item %s attaches to exactly the 6 dry rooms", workItemId)
                    .isEqualTo(new TreeSet<>(DRY_ROOMS));
        }
    }

    // ------------------------------------------------------------------
    // 105 (R10.6, R10.3) : universal categories have ZERO attachment rows
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Every work item in a universal category has ZERO attachment rows (empty = all rooms)")
    void universalCategoriesHaveNoAttachmentRows() throws Exception {
        long orphanRows = scalarLong(
                "SELECT COUNT(*) FROM work_room_types wrt "
                        + "JOIN work_items wi ON wi.id = wrt.work_item_id "
                        + "JOIN work_categories wc ON wc.id = wi.work_category_id "
                        + "WHERE wc.code IN ("
                        + "'PRELIMINARY','CONSTRUCTIONS_GK','ELECTRICAL_ROUGH','ELECTRICAL_FINISH',"
                        + "'PLASTERING','PAINTING_DECOR','EXTRAS','OTHER')");
        assertThat(orphanRows)
                .as("universal categories must be left with no work_room_types rows (empty = all rooms, R10.3)")
                .isZero();

        // And each universal category actually has work items in the catalog (so the check is meaningful).
        assertThat(workItemIdsForCategories(UNIVERSAL_CATEGORIES))
                .as("the seeded catalog must contain at least one universal-category work item")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Re-migrate is a no-op (idempotency, R10.6, R19.2)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Re-running the changelog does not duplicate work_room_types rows (idempotent)")
    void reRunningChangelogIsNoOp() throws Exception {
        long before = scalarLong("SELECT COUNT(*) FROM work_room_types");
        assertThat(before)
                .as("the 105 seed must have inserted attachment rows")
                .isGreaterThan(0);

        runLiquibase();

        long after = scalarLong("SELECT COUNT(*) FROM work_room_types");
        assertThat(after)
                .as("re-migrate must not add or duplicate any work_room_types row (R10.6, R19.2)")
                .isEqualTo(before);
    }

    // ---------------------------------------------------------------------
    // query helpers
    // ---------------------------------------------------------------------

    /** All work-item ids whose work category code is one of the given codes. */
    private List<Long> workItemIdsForCategories(List<String> categoryCodes) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT wi.id FROM work_items wi "
                        + "JOIN work_categories wc ON wc.id = wi.work_category_id WHERE wc.code IN (");
        for (int i = 0; i < categoryCodes.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");

        List<Long> out = new ArrayList<>();
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < categoryCodes.size(); i++) {
                ps.setString(i + 1, categoryCodes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    /** The set of room-type codes attached to the given work item via work_room_types. */
    private TreeSet<String> attachedRoomCodes(long workItemId) throws Exception {
        String sql = "SELECT rt.code FROM work_room_types wrt "
                + "JOIN room_types rt ON rt.id = wrt.room_type_id "
                + "WHERE wrt.work_item_id = ?";
        TreeSet<String> out = new TreeSet<>();
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, workItemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
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
}
