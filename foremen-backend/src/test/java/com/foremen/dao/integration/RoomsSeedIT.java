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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies both the {@code ROOMS} ABAC resource seed introduced by changeset
 * {@code 043-seed-rooms-resource} and the {@code rooms} table created by changeset
 * {@code 042-create-rooms}:
 * <ul>
 *     <li>exactly one {@code ROOMS} resource row is seeded (Requirement 7.8);</li>
 *     <li>the per-system-role grant matrix is exactly ADMIN {@code CREATE, READ, UPDATE, DELETE},
 *         MANAGER {@code CREATE, READ, UPDATE, DELETE}, FOREMAN {@code READ, UPDATE}, and
 *         WORKER/FINANCIER/CLIENT {@code READ}, with CREATE and DELETE granted to ADMIN and MANAGER
 *         only and to no other role (Requirements 6.4, 6.5);</li>
 *     <li>re-applying the whole changelog leaves the resource, {@code role_resources}, and
 *         {@code role_resource_operations} counts unchanged — the seed changesets are idempotent
 *         (Requirement 7.8);</li>
 *     <li>the {@code rooms} table exists with its expected metric/source/audit columns, a
 *         {@code project_id BIGINT NOT NULL} FK to {@code projects} and a
 *         {@code room_type_id BIGINT NOT NULL} FK to {@code room_types} (Requirements 1.7, 1.8).</li>
 * </ul>
 *
 * <p>Mirrors {@link ProjectsSeedIT} (seed matrix + idempotency + table shape via
 * {@code information_schema}): the real changelog runs against a real database and every assertion
 * queries the live catalog, so the Liquibase changesets are actually exercised (the
 * {@code integration-test} profile that disables Liquibase and uses JPA {@code create-drop} would
 * NOT exercise them).
 *
 * <p>Note on audit column names: {@code BaseEntity}'s audit columns are physically named
 * {@code created_date}/{@code updated_date} in every changeset in this project, so this test asserts
 * those names; the Requirement 1.7 intent (four audit columns present) is preserved.
 *
 * <p>Validates: Requirements 7.8, 6.4, 6.5, 1.7, 1.8
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomsSeedIT {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "ROOMS";

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

    // -----------------------------------------------------------------
    // 7.8 — exactly one ROOMS resource row is seeded
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Changeset 043 seeds exactly one ROOMS resource row (7.8)")
    void seedsExactlyOneRoomsResource() throws Exception {
        assertThat(countResource(RESOURCE_CODE))
                .as("exactly one ROOMS resource row")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // 6.4 / 6.5 — exact per-system-role grant matrix; CREATE/DELETE ADMIN+MANAGER-only
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Per-system-role grants on ROOMS: ADMIN CRUD, MANAGER CRUD, FOREMAN READ+UPDATE, "
            + "WORKER/FINANCIER/CLIENT READ, CREATE/DELETE ADMIN+MANAGER-only (6.4, 6.5)")
    void grantMatrixIsExact() throws Exception {
        // ADMIN: full CRUD.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on ROOMS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // MANAGER: full CRUD.
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on ROOMS")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");

        // FOREMAN: READ + UPDATE — no CREATE, no DELETE.
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on ROOMS")
                .containsExactlyInAnyOrder("READ", "UPDATE");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN must not hold CREATE or DELETE on ROOMS")
                .doesNotContain("CREATE", "DELETE");

        // WORKER, FINANCIER, CLIENT: READ only.
        for (String roleCode : List.of("WORKER", "FINANCIER", "CLIENT")) {
            assertThat(operationsFor(roleCode))
                    .as("%s operations on ROOMS", roleCode)
                    .containsExactly("READ");
        }

        // CREATE is ADMIN + MANAGER only (Req 6.5).
        assertThat(rolesHoldingOperation("CREATE"))
                .as("CREATE on ROOMS is granted to ADMIN and MANAGER only")
                .containsExactlyInAnyOrder("ADMIN", "MANAGER");

        // DELETE is ADMIN + MANAGER only (Req 6.5).
        assertThat(rolesHoldingOperation("DELETE"))
                .as("DELETE on ROOMS is granted to ADMIN and MANAGER only")
                .containsExactlyInAnyOrder("ADMIN", "MANAGER");

        // No non-ADMIN/non-MANAGER role holds CREATE or DELETE on ROOMS.
        for (String roleCode : List.of("FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            assertThat(operationsFor(roleCode))
                    .as("%s must not hold CREATE or DELETE on ROOMS", roleCode)
                    .doesNotContain("CREATE", "DELETE");
        }
    }

    // -----------------------------------------------------------------
    // 7.8 — re-applying the changelog is idempotent (counts unchanged)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Re-running the changelog does not duplicate the ROOMS resource or its grants (7.8)")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 043 changesets are guarded by MARK_RAN + sqlCheck
        // expectedResult="0" preconditions, so a second application inserts nothing new.
        runLiquibase();

        assertThat(countResource(RESOURCE_CODE))
                .as("ROOMS resource row count unchanged after re-run")
                .isEqualTo(resourceCountBefore)
                .isEqualTo(1);
        assertThat(countRoleResources(RESOURCE_CODE))
                .as("role_resources row count on ROOMS unchanged after re-run")
                .isEqualTo(roleResourcesBefore);
        assertThat(countRoleResourceOperations(RESOURCE_CODE))
                .as("role_resource_operations row count on ROOMS unchanged after re-run")
                .isEqualTo(roleResourceOperationsBefore);
        assertThat(snapshotGrants())
                .as("per-role grant set on ROOMS unchanged after re-run")
                .isEqualTo(grantsBefore);
    }

    // -----------------------------------------------------------------
    // 1.7 — rooms table created with the expected columns
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Changeset 042 creates the rooms table with the expected columns (1.7)")
    void createsRoomsTableWithExpectedColumns() throws Exception {
        assertThat(tableExists("rooms")).as("rooms table exists").isTrue();

        // Mandatory FK columns (NOT NULL).
        assertColumnType("rooms", "project_id", "bigint", null, false);
        assertColumnType("rooms", "room_type_id", "bigint", null, false);

        // Optional label (VARCHAR(255), nullable) and geometry (jsonb, nullable).
        assertColumnType("rooms", "label", "character varying", 255, true);
        assertColumnType("rooms", "geometry", "jsonb", null, true);

        // Numeric metric columns NUMERIC(12,2), all nullable.
        for (String metric : List.of(
                "ceiling_height", "door_height", "door_width", "window_height", "window_width",
                "wall_gap", "finish_gap", "floor_area", "wall_area", "perimeter",
                "door_area", "window_area")) {
            assertColumnType("rooms", metric, "numeric", null, true);
            assertNumericPrecisionScale("rooms", metric, 12, 2);
        }

        // Integer count columns, nullable.
        for (String count : List.of("internal_corners", "door_count", "window_count")) {
            assertColumnType("rooms", count, "integer", null, true);
        }

        // The five source-flag columns VARCHAR(20), nullable.
        for (String source : List.of(
                "floor_area_source", "wall_area_source", "perimeter_source",
                "door_area_source", "window_area_source")) {
            assertColumnType("rooms", source, "character varying", 20, true);
        }

        // The four BaseEntity audit columns (physically created_date/updated_date in this project).
        assertColumnType("rooms", "created_date", "timestamp without time zone", null, false);
        assertColumnType("rooms", "created_by", "character varying", 255, true);
        assertColumnType("rooms", "updated_date", "timestamp without time zone", null, true);
        assertColumnType("rooms", "updated_by", "character varying", 255, true);

        // Primary key on id.
        assertThat(hasPrimaryKey("rooms", "id")).as("rooms PK on id").isTrue();
    }

    // -----------------------------------------------------------------
    // 1.7 — project_id / room_type_id foreign keys to projects / room_types
    // -----------------------------------------------------------------
    @Test
    @DisplayName("rooms has FK project_id → projects and room_type_id → room_types (1.7)")
    void roomsTableHasProjectAndRoomTypeForeignKeys() throws Exception {
        assertThat(hasForeignKeyReferencing("rooms", "projects"))
                .as("rooms must have a foreign key referencing projects").isTrue();
        assertThat(hasForeignKeyReferencing("rooms", "room_types"))
                .as("rooms must have a foreign key referencing room_types").isTrue();
    }

    // ---------------------------------------------------------------------
    // catalog query helpers — ABAC seed
    // ---------------------------------------------------------------------

    private int countResource(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM resources WHERE code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The sorted list of operation codes granted to the given role on the ROOMS resource. */
    private List<String> operationsFor(String roleCode) throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? "
                + "ORDER BY o.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, RESOURCE_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ops = new ArrayList<>();
                while (rs.next()) {
                    ops.add(rs.getString(1));
                }
                return ops;
            }
        }
    }

    /** The sorted list of role codes granted the given operation on the ROOMS resource. */
    private List<String> rolesHoldingOperation(String operationCode) throws Exception {
        String sql = "SELECT r.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE res.code = ? AND o.code = ? "
                + "ORDER BY r.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, RESOURCE_CODE);
            ps.setString(2, operationCode);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> roles = new ArrayList<>();
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
                return roles;
            }
        }
    }

    private int countRoleResources(String resourceCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resources rr "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countRoleResourceOperations(String resourceCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A stable snapshot of role_code -> sorted operation codes on ROOMS. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }

    // ---------------------------------------------------------------------
    // information_schema / catalog helpers — table shape
    // ---------------------------------------------------------------------

    private boolean tableExists(String table) throws Exception {
        String sql = "SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void assertColumnType(String table, String column, String expectedType,
                                  Integer expectedMaxLength, boolean expectedNullable) throws Exception {
        String sql = "SELECT data_type, character_maximum_length, is_nullable "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("column %s.%s should exist", table, column).isTrue();
                assertThat(rs.getString("data_type"))
                        .as("%s.%s data_type", table, column).isEqualTo(expectedType);
                if (expectedMaxLength != null) {
                    assertThat(rs.getInt("character_maximum_length"))
                            .as("%s.%s length", table, column).isEqualTo(expectedMaxLength);
                }
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                assertThat(nullable)
                        .as("%s.%s nullable", table, column).isEqualTo(expectedNullable);
            }
        }
    }

    private void assertNumericPrecisionScale(String table, String column,
                                             int expectedPrecision, int expectedScale) throws Exception {
        String sql = "SELECT numeric_precision, numeric_scale "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("column %s.%s should exist", table, column).isTrue();
                assertThat(rs.getInt("numeric_precision"))
                        .as("%s.%s precision", table, column).isEqualTo(expectedPrecision);
                assertThat(rs.getInt("numeric_scale"))
                        .as("%s.%s scale", table, column).isEqualTo(expectedScale);
            }
        }
    }

    private boolean hasPrimaryKey(String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? AND kcu.column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Whether the table has any foreign key constraint referencing the given target table. */
    private boolean hasForeignKeyReferencing(String table, String referencedTable) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON tc.constraint_name = ccu.constraint_name "
                + " AND tc.table_schema = ccu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? "
                + "  AND ccu.table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, referencedTable);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
