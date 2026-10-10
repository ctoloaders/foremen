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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the FOR-05-10 planning-Gantt migration — changeset
 * {@code 152-create-project-schedules} (schema) and changeset
 * {@code 153-seed-work-schedule-resource} (ABAC seed):
 * <ul>
 *     <li>{@code project_schedules} is created with its columns, PK, the UNIQUE +
 *         {@code ON DELETE CASCADE} FK to {@code projects}, and the {@code version} default
 *         (Requirement 6.1);</li>
 *     <li>{@code project_schedule_bars} is created with its columns, PK, the two
 *         {@code ON DELETE CASCADE} / plain FKs, the UNIQUE
 *         {@code (schedule_id, work_category_id)} constraint, the {@code schedule_id} index, and
 *         the two {@code >= 1} CHECK constraints (Requirement 6.2);</li>
 *     <li>the {@code WORK_SCHEDULE} resource row and the full role matrix (ADMIN/MANAGER CRUD,
 *         FOREMAN/ESTIMATOR READ+UPDATE, WORKER/FINANCIER/CLIENT READ) are seeded (Requirements
 *         1.1–1.4);</li>
 *     <li>re-running the whole changelog changes no schema and no seed row — every changeset is
 *         guarded by {@code NOT tableExists} / {@code COUNT = 0} + {@code MARK_RAN} or a
 *         {@code NOT EXISTS} insert (Requirements 1.5, 6.x);</li>
 *     <li>the {@code start_day >= 1} and {@code duration_days >= 1} CHECK constraints and the
 *         UNIQUE {@code (schedule_id, work_category_id)} constraint reject bad rows (Requirement
 *         6.2);</li>
 *     <li>deleting a project cascades to its {@code project_schedules} row and, transitively, to
 *         its {@code project_schedule_bars} rows (Requirements 6.1, 6.2).</li>
 * </ul>
 *
 * <p>Mirrors {@code ProjectMemberMigrationIntegrationTest} (schema/constraint catalog helpers) and
 * {@code WorkCategoriesResourceSeedIntegrationTest} (seed + grant-snapshot idempotence): run the
 * real changelog against a real database and assert against the live catalog via raw JDBC, rather
 * than relying on Hibernate DDL.
 *
 * <p>Validates: Requirements 1.5, 6.1, 6.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectScheduleMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "WORK_SCHEDULE";

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

    // --- Requirement 6.1 : project_schedules table, columns and constraints ---
    @Test
    @DisplayName("Changeset 152 creates project_schedules with its columns, PK, unique CASCADE FK, "
            + "and version default")
    void migrationCreatesProjectSchedulesTable() throws Exception {
        assertThat(tableExists("project_schedules")).as("project_schedules table").isTrue();

        assertColumnType("project_schedules", "id", "bigint", null, false);
        assertColumnType("project_schedules", "project_id", "bigint", null, false);
        assertColumnType("project_schedules", "version", "bigint", null, false);
        assertColumnType("project_schedules", "created_date", "timestamp without time zone", null, false);
        assertColumnType("project_schedules", "created_by", "character varying", 255, true);
        assertColumnType("project_schedules", "updated_date", "timestamp without time zone", null, true);
        assertColumnType("project_schedules", "updated_by", "character varying", 255, true);

        // PK on id; UNIQUE on project_id; project_id FK -> projects.
        assertThat(hasPrimaryKey("project_schedules", "id"))
                .as("project_schedules PK on id").isTrue();
        assertThat(constraintExists("ux_project_schedules_project"))
                .as("named unique constraint ux_project_schedules_project").isTrue();
        assertThat(isColumnUnique("project_schedules", "project_id"))
                .as("project_schedules.project_id is unique").isTrue();
        assertThat(hasForeignKey("project_schedules", "project_id", "projects"))
                .as("project_schedules.project_id FK -> projects").isTrue();

        // The FK to projects must be ON DELETE CASCADE (R6.1).
        assertThat(foreignKeyDeleteRule("fk_project_schedules_project"))
                .as("fk_project_schedules_project delete rule")
                .isEqualTo("CASCADE");

        // version defaults to 0 at the DB level.
        assertThat(columnDefault("project_schedules", "version"))
                .as("project_schedules.version default")
                .contains("0");
    }

    // --- Requirement 6.2 : project_schedule_bars table, columns and constraints ---
    @Test
    @DisplayName("Changeset 152 creates project_schedule_bars with its columns, FKs, UNIQUE, index, "
            + "and the two >= 1 CHECK constraints")
    void migrationCreatesProjectScheduleBarsTable() throws Exception {
        assertThat(tableExists("project_schedule_bars")).as("project_schedule_bars table").isTrue();

        assertColumnType("project_schedule_bars", "id", "bigint", null, false);
        assertColumnType("project_schedule_bars", "schedule_id", "bigint", null, false);
        assertColumnType("project_schedule_bars", "work_category_id", "bigint", null, false);
        assertColumnType("project_schedule_bars", "start_day", "integer", null, false);
        assertColumnType("project_schedule_bars", "duration_days", "integer", null, false);
        assertColumnType("project_schedule_bars", "created_date", "timestamp without time zone", null, false);
        assertColumnType("project_schedule_bars", "created_by", "character varying", 255, true);
        assertColumnType("project_schedule_bars", "updated_date", "timestamp without time zone", null, true);
        assertColumnType("project_schedule_bars", "updated_by", "character varying", 255, true);

        assertThat(hasPrimaryKey("project_schedule_bars", "id"))
                .as("project_schedule_bars PK on id").isTrue();

        // schedule_id FK -> project_schedules, ON DELETE CASCADE.
        assertThat(hasForeignKey("project_schedule_bars", "schedule_id", "project_schedules"))
                .as("project_schedule_bars.schedule_id FK -> project_schedules").isTrue();
        assertThat(foreignKeyDeleteRule("fk_project_schedule_bars_schedule"))
                .as("fk_project_schedule_bars_schedule delete rule")
                .isEqualTo("CASCADE");

        // work_category_id FK -> work_categories (no cascade; a used category cannot be deleted).
        assertThat(hasForeignKey("project_schedule_bars", "work_category_id", "work_categories"))
                .as("project_schedule_bars.work_category_id FK -> work_categories").isTrue();
        assertThat(foreignKeyDeleteRule("fk_project_schedule_bars_work_category"))
                .as("fk_project_schedule_bars_work_category delete rule")
                .isEqualTo("NO ACTION");

        // UNIQUE (schedule_id, work_category_id), the schedule_id index, and the two CHECK rules.
        assertThat(constraintExists("ux_project_schedule_bars_schedule_category"))
                .as("named unique constraint ux_project_schedule_bars_schedule_category").isTrue();
        assertThat(indexExists("ix_project_schedule_bars_schedule"))
                .as("ix_project_schedule_bars_schedule index").isTrue();
        assertThat(checkConstraintExists("ck_project_schedule_bars_start_day_min"))
                .as("start_day >= 1 CHECK constraint").isTrue();
        assertThat(checkConstraintExists("ck_project_schedule_bars_duration_days_min"))
                .as("duration_days >= 1 CHECK constraint").isTrue();
    }

    // --- Requirements 1.1-1.4 : the WORK_SCHEDULE resource and full role matrix are seeded ---
    @Test
    @DisplayName("Changeset 153 seeds the WORK_SCHEDULE resource and the full role matrix")
    void seedsWorkScheduleResourceAndGrants() throws Exception {
        assertThat(countResource(RESOURCE_CODE))
                .as("WORK_SCHEDULE resource row exists exactly once").isEqualTo(1);

        // D13 role matrix.
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on WORK_SCHEDULE")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on WORK_SCHEDULE")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("FOREMAN"))
                .as("FOREMAN operations on WORK_SCHEDULE")
                .containsExactlyInAnyOrder("READ", "UPDATE");
        assertThat(operationsFor("ESTIMATOR"))
                .as("ESTIMATOR operations on WORK_SCHEDULE")
                .containsExactlyInAnyOrder("READ", "UPDATE");
        assertThat(operationsFor("WORKER"))
                .as("WORKER operations on WORK_SCHEDULE")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER"))
                .as("FINANCIER operations on WORK_SCHEDULE")
                .containsExactly("READ");
        assertThat(operationsFor("CLIENT"))
                .as("CLIENT operations on WORK_SCHEDULE")
                .containsExactly("READ");
    }

    // --- Requirement 1.5 / 6.x : re-running the changelog changes nothing ---
    @Test
    @DisplayName("Re-running the changelog changes no schema and no WORK_SCHEDULE seed row")
    void reRunningChangelogIsNoOp() throws Exception {
        int resourceBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. Changeset 152 is guarded by NOT tableExists + MARK_RAN,
        // and the 153 seed by COUNT=0 + MARK_RAN / NOT EXISTS, so a second application changes
        // nothing (R1.5, R6).
        runLiquibase();

        // Schema unchanged: tables and their defining constraints still present.
        assertThat(tableExists("project_schedules")).as("project_schedules still present").isTrue();
        assertThat(tableExists("project_schedule_bars")).as("project_schedule_bars still present").isTrue();
        assertThat(constraintExists("ux_project_schedules_project"))
                .as("ux_project_schedules_project still present").isTrue();
        assertThat(constraintExists("ux_project_schedule_bars_schedule_category"))
                .as("ux_project_schedule_bars_schedule_category still present").isTrue();
        assertThat(checkConstraintExists("ck_project_schedule_bars_start_day_min"))
                .as("start_day CHECK still present").isTrue();
        assertThat(checkConstraintExists("ck_project_schedule_bars_duration_days_min"))
                .as("duration_days CHECK still present").isTrue();

        // Seed unchanged: one resource row, same grants.
        assertThat(countResource(RESOURCE_CODE))
                .as("WORK_SCHEDULE resource count unchanged after re-run")
                .isEqualTo(resourceBefore)
                .isEqualTo(1);
        assertThat(countRoleResources(RESOURCE_CODE))
                .as("role_resources count unchanged after re-run")
                .isEqualTo(roleResourcesBefore);
        assertThat(countRoleResourceOperations(RESOURCE_CODE))
                .as("role_resource_operations count unchanged after re-run")
                .isEqualTo(roleResourceOperationsBefore);
        assertThat(snapshotGrants())
                .as("per-role grant set unchanged after re-run")
                .isEqualTo(grantsBefore);
    }

    // --- Requirement 6.2 : CHECK and UNIQUE constraints reject bad rows ---
    @Test
    @DisplayName("start_day = 0 is rejected by the start_day >= 1 CHECK constraint")
    void startDayBelowOneIsRejected() throws Exception {
        long projectId = insertProject();
        long scheduleId = insertSchedule(projectId);
        long categoryId = anyWorkCategoryId();

        assertThatThrownBy(() -> insertBar(scheduleId, categoryId, 0, 5))
                .isInstanceOf(SQLException.class)
                .as("start_day < 1 violates ck_project_schedule_bars_start_day_min");

        deleteProject(projectId);
    }

    @Test
    @DisplayName("duration_days = 0 is rejected by the duration_days >= 1 CHECK constraint")
    void durationDaysBelowOneIsRejected() throws Exception {
        long projectId = insertProject();
        long scheduleId = insertSchedule(projectId);
        long categoryId = anyWorkCategoryId();

        assertThatThrownBy(() -> insertBar(scheduleId, categoryId, 1, 0))
                .isInstanceOf(SQLException.class)
                .as("duration_days < 1 violates ck_project_schedule_bars_duration_days_min");

        deleteProject(projectId);
    }

    @Test
    @DisplayName("A second bar for the same (schedule_id, work_category_id) is rejected by the UNIQUE constraint")
    void duplicateScheduleCategoryBarIsRejected() throws Exception {
        long projectId = insertProject();
        long scheduleId = insertSchedule(projectId);
        long categoryId = anyWorkCategoryId();

        insertBar(scheduleId, categoryId, 1, 3);

        assertThatThrownBy(() -> insertBar(scheduleId, categoryId, 10, 2))
                .isInstanceOf(SQLException.class)
                .as("a second bar for the same (schedule, category) violates the UNIQUE constraint");

        assertThat(countBars(scheduleId)).as("exactly one bar survives").isEqualTo(1);

        deleteProject(projectId);
    }

    @Test
    @DisplayName("A second schedule for the same project is rejected by the UNIQUE project_id constraint")
    void duplicateScheduleForProjectIsRejected() throws Exception {
        long projectId = insertProject();
        insertSchedule(projectId);

        assertThatThrownBy(() -> insertSchedule(projectId))
                .isInstanceOf(SQLException.class)
                .as("a second schedule for the same project violates ux_project_schedules_project");

        deleteProject(projectId);
    }

    // --- Requirements 6.1 / 6.2 : deleting a project cascades to its schedule and bars ---
    @Test
    @DisplayName("Deleting a project cascades to its project_schedules row and its project_schedule_bars rows")
    void deletingProjectCascadesToScheduleAndBars() throws Exception {
        long projectId = insertProject();
        long scheduleId = insertSchedule(projectId);
        long categoryId = anyWorkCategoryId();
        insertBar(scheduleId, categoryId, 1, 4);

        assertThat(scheduleExists(scheduleId)).as("schedule exists before delete").isTrue();
        assertThat(countBars(scheduleId)).as("bar exists before delete").isEqualTo(1);

        // Deleting the project must cascade: schedule gone (direct FK CASCADE), bars gone
        // (transitive CASCADE through the schedule).
        deleteProject(projectId);

        assertThat(scheduleExists(scheduleId))
                .as("project_schedules row removed by project delete cascade").isFalse();
        assertThat(countBars(scheduleId))
                .as("project_schedule_bars rows removed transitively").isEqualTo(0);
    }

    // ---------------------------------------------------------------------
    // seeding helpers
    // ---------------------------------------------------------------------

    private long insertProject() throws Exception {
        String sql = "INSERT INTO projects (name, status, created_date) "
                + "VALUES (?, 'DRAFT', NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "Project Schedule Migration Test " + System.nanoTime());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertSchedule(long projectId) throws Exception {
        String sql = "INSERT INTO project_schedules (project_id, version, created_date) "
                + "VALUES (?, 0, NOW()) RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertBar(long scheduleId, long workCategoryId, int startDay, int durationDays)
            throws Exception {
        String sql = "INSERT INTO project_schedule_bars "
                + "(schedule_id, work_category_id, start_day, duration_days, created_date) "
                + "VALUES (?, ?, ?, ?, NOW())";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, scheduleId);
            ps.setLong(2, workCategoryId);
            ps.setInt(3, startDay);
            ps.setInt(4, durationDays);
            ps.executeUpdate();
        }
    }

    private void deleteProject(long projectId) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM projects WHERE id = ?")) {
            ps.setLong(1, projectId);
            ps.executeUpdate();
        }
    }

    /** Any seeded work_categories id (changeset 027 seeds thirteen defaults). */
    private long anyWorkCategoryId() throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM work_categories ORDER BY id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean scheduleExists(long scheduleId) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM project_schedules WHERE id = ?")) {
            ps.setLong(1, scheduleId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private int countBars(long scheduleId) throws Exception {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM project_schedule_bars WHERE schedule_id = ?")) {
            ps.setLong(1, scheduleId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ---------------------------------------------------------------------
    // seed / grant query helpers
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

    /** A stable snapshot of role_code -> sorted operation codes on WORK_SCHEDULE. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of(
                "ADMIN", "MANAGER", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }

    // ---------------------------------------------------------------------
    // information_schema / catalog helpers
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

    private String columnDefault(String table, String column) throws Exception {
        String sql = "SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String def = rs.getString(1);
                return def == null ? "" : def;
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

    private boolean hasForeignKey(String table, String column, String referencedTable) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON tc.constraint_name = ccu.constraint_name "
                + " AND tc.table_schema = ccu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? "
                + "  AND kcu.column_name = ? AND ccu.table_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ps.setString(3, referencedTable);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The ON DELETE rule of a named referential constraint (CASCADE / NO ACTION / ...). */
    private String foreignKeyDeleteRule(String constraintName) throws Exception {
        String sql = "SELECT delete_rule FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = 'public' AND constraint_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("referential constraint %s should exist", constraintName).isTrue();
                return rs.getString(1);
            }
        }
    }

    private boolean isColumnUnique(String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'UNIQUE' "
                + "  AND tc.table_schema = 'public' AND tc.table_name = ? AND kcu.column_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean constraintExists(String constraintName) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND constraint_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean checkConstraintExists(String constraintName) throws Exception {
        String sql = "SELECT 1 FROM information_schema.check_constraints "
                + "WHERE constraint_schema = 'public' AND constraint_name = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexExists(String indexName) throws Exception {
        String sql = "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
