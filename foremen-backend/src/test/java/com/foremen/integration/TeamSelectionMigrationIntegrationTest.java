package com.foremen.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-05-09 task 1.4 — team-selection Liquibase migration idempotence integration test.
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers
 * changesets {@code 149}–{@code 151} last) against a Testcontainers PostgreSQL and proves the end
 * state produced by that migration slice is exactly the three changesets describe, and that a
 * second application changes no schema and no data:
 *
 * <ul>
 *   <li><b>149</b> adds the three nullable worker-attribute columns to {@code users} —
 *       {@code worker_kind} (VARCHAR), {@code contact_person} (VARCHAR(255)), {@code nip}
 *       (VARCHAR(10)) — each nullable, with no backfill: every pre-existing user keeps them
 *       {@code NULL} (R13.16);</li>
 *   <li><b>150</b> adds {@code project_members.worker_type_id} (BIGINT, nullable, FK →
 *       {@code worker_types(id)} ON DELETE RESTRICT) with NO backfill (an existing WORKER member
 *       becomes an Uncategorized_Worker — R14.12), and {@code project_members.assignment_status}
 *       (VARCHAR, NOT NULL, DEFAULT {@code 'ACTIVE'}) backfilled to {@code 'ACTIVE'} for every
 *       pre-existing row (R27.1);</li>
 *   <li><b>151</b> creates the {@code project_member_tags} child table with the documented columns
 *       ({@code id}, {@code project_member_id} NOT NULL FK ON DELETE CASCADE, {@code tag}
 *       VARCHAR(50) NOT NULL, {@code order_no} INT NOT NULL) (R15.1);</li>
 *   <li>re-running the full changelog is a no-op: the schema (column presence, data type, and
 *       nullability of every added column, plus the new table) and the data (a probe
 *       {@code project_members} row's backfilled status, and the no-worker-type-backfill) are
 *       byte-identical before and after the second apply (idempotency via {@code NOT columnExists}
 *       / {@code NOT tableExists} + {@code onFail="MARK_RAN"}).</li>
 * </ul>
 *
 * <p><b>Backfill verification approach.</b> The harness applies the whole changelog in a single
 * pass, so there is no pre-150 row to observe directly. Instead the test exercises the exact
 * mechanism that backfills pre-existing rows: {@code assignment_status} is {@code NOT NULL DEFAULT
 * 'ACTIVE'}, so (a) a {@code project_members} row inserted without the column receives
 * {@code 'ACTIVE'} — the same default Liquibase applies to every existing row when it adds a
 * non-null column with a default — and (b) the column cannot be {@code NULL}. The same probe row
 * leaves {@code worker_type_id} {@code NULL}, proving the no-worker-type-backfill (R14.12). The
 * probe row is created once with a unique email/project id per JVM run and is left in place; the
 * re-run assertions read it back to prove the second apply changed no data.
 *
 * <p>Harness mirrors {@code OfferApprovalMigrationIntegrationTest} /
 * {@code MaterialCostNetMigrationIntegrationTest}: it drives Liquibase directly against the
 * container (no Spring context) via the Liquibase API and queries via raw JDBC, so the assertions
 * observe exactly what the migration writes. Each JVM run gets a fresh container, so it is
 * inherently repeatable with no manual cleanup.
 *
 * <p>Validates: Requirements 13.16, 14.12, 27.1, 15.1
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("Feature: FOR-05-09-team-selection, task 1.4: migration idempotence")
class TeamSelectionMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    /** id of the probe project_members row inserted after the first migrate. */
    private long probeMemberId;

    @BeforeAll
    void migrateAndSeedProbe() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        runChangelog();
        probeMemberId = seedProbeMember();
    }

    /** Applies the full Liquibase changelog once against the running container. */
    private static void runChangelog() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    // ------------------------------------------------------------------
    // 149 — users worker attributes (R13.16)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("149 adds worker_kind, contact_person, nip to users as nullable columns of the documented types")
    void usersWorkerAttributesAddedAsNullable() throws Exception {
        assertColumn("users", "worker_kind", "character varying", /* nullable */ true);
        assertColumn("users", "contact_person", "character varying", true);
        assertColumn("users", "nip", "character varying", true);

        // Documented lengths: contact_person VARCHAR(255), nip VARCHAR(10). worker_kind is an
        // unqualified VARCHAR (no length), so it has no character_maximum_length.
        assertThat(charMaxLength("users", "contact_person"))
                .as("contact_person is VARCHAR(255)").isEqualTo(255);
        assertThat(charMaxLength("users", "nip"))
                .as("nip is VARCHAR(10)").isEqualTo(10);
    }

    @Test
    @DisplayName("149 performs no backfill: the seeded ADMIN user keeps worker_kind/contact_person/nip NULL")
    void usersWorkerAttributesHaveNoBackfill() throws Exception {
        // The changelog seeds system users (e.g. the bootstrap ADMIN); none is a worker record, so
        // all three worker attributes must be NULL on every pre-existing user.
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM users "
                        + "WHERE worker_kind IS NOT NULL OR contact_person IS NOT NULL OR nip IS NOT NULL"))
                .as("no pre-existing user may carry a worker attribute (no backfill)")
                .isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // 150 — project_members worker_type_id + assignment_status (R14.12, R27.1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("150 adds worker_type_id (BIGINT, nullable) with an FK to worker_types")
    void projectMembersWorkerTypeAddedAsNullableFk() throws Exception {
        assertColumn("project_members", "worker_type_id", "bigint", /* nullable */ true);
        assertThat(foreignKeyExists("fk_project_members_worker_type"))
                .as("150 must add the worker_type_id FK to worker_types")
                .isTrue();
    }

    @Test
    @DisplayName("150 adds assignment_status (VARCHAR, NOT NULL, DEFAULT 'ACTIVE')")
    void projectMembersAssignmentStatusAddedNotNullWithDefault() throws Exception {
        assertColumn("project_members", "assignment_status", "character varying", /* nullable */ false);
        assertThat(columnDefault("project_members", "assignment_status"))
                .as("assignment_status default must be 'ACTIVE'")
                .contains("ACTIVE");
    }

    @Test
    @DisplayName("27.1: a row inserted without assignment_status is backfilled to ACTIVE (the pre-existing-row default)")
    void assignmentStatusBackfillsToActive() throws Exception {
        // The probe row was inserted WITHOUT an assignment_status, exactly as a pre-existing row
        // would be when the non-null DEFAULT 'ACTIVE' column is added — it must read back as ACTIVE.
        assertThat(statusOf(probeMemberId))
                .as("a member inserted without a status gets the ACTIVE backfill default")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("14.12: no worker-type backfill — the probe member's worker_type_id is NULL (Uncategorized_Worker)")
    void workerTypeIsNotBackfilled() throws Exception {
        assertThat(workerTypeIdIsNull(probeMemberId))
                .as("a member created without a worker type stays Uncategorized (worker_type_id NULL)")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // 151 — project_member_tags child table (R15.1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("151 creates project_member_tags with the documented columns, types, and nullability")
    void projectMemberTagsTableCreated() throws Exception {
        assertThat(tableExists("project_member_tags"))
                .as("151 must create project_member_tags").isTrue();

        assertColumn("project_member_tags", "id", "bigint", /* nullable */ false);
        assertColumn("project_member_tags", "project_member_id", "bigint", /* nullable */ false);
        assertColumn("project_member_tags", "tag", "character varying", /* nullable */ false);
        assertColumn("project_member_tags", "order_no", "integer", /* nullable */ false);

        assertThat(charMaxLength("project_member_tags", "tag"))
                .as("tag is VARCHAR(50)").isEqualTo(50);
        assertThat(foreignKeyExists("fk_project_member_tags_member"))
                .as("151 must add the owner FK to project_members")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // idempotency — re-running the changelog changes no schema and no data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("re-running the changelog changes no schema (column types/nullability, new table) and no data")
    void reRunIsNoOp() throws Exception {
        Map<String, String> schemaBefore = schemaSnapshot();
        String probeStatusBefore = statusOf(probeMemberId);
        boolean probeWorkerTypeNullBefore = workerTypeIdIsNull(probeMemberId);
        long usersWithWorkerAttrsBefore = queryForLong(
                "SELECT COUNT(*) FROM users "
                        + "WHERE worker_kind IS NOT NULL OR contact_person IS NOT NULL OR nip IS NOT NULL");
        long memberCountBefore = queryForLong("SELECT COUNT(*) FROM project_members");
        long tagCountBefore = queryForLong("SELECT COUNT(*) FROM project_member_tags");

        // Apply the same changelog a second time — must not error, must not change anything.
        runChangelog();

        assertThat(schemaSnapshot())
                .as("re-run must leave the added columns and the new table byte-identical")
                .isEqualTo(schemaBefore);
        assertThat(statusOf(probeMemberId))
                .as("re-run must not change the probe member's backfilled status")
                .isEqualTo(probeStatusBefore)
                .isEqualTo("ACTIVE");
        assertThat(workerTypeIdIsNull(probeMemberId))
                .as("re-run must not backfill a worker type onto the probe member")
                .isEqualTo(probeWorkerTypeNullBefore)
                .isTrue();
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM users "
                        + "WHERE worker_kind IS NOT NULL OR contact_person IS NOT NULL OR nip IS NOT NULL"))
                .as("re-run must not backfill any user worker attribute")
                .isEqualTo(usersWithWorkerAttrsBefore)
                .isEqualTo(0L);
        assertThat(queryForLong("SELECT COUNT(*) FROM project_members"))
                .as("re-run must not add or drop project_members rows")
                .isEqualTo(memberCountBefore);
        assertThat(queryForLong("SELECT COUNT(*) FROM project_member_tags"))
                .as("re-run must not add project_member_tags rows")
                .isEqualTo(tagCountBefore);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * Inserts a minimal {@code users} + {@code project_members} pair and returns the member id. The
     * membership is created WITHOUT an {@code assignment_status} and WITHOUT a {@code worker_type_id}
     * so the test can observe the ACTIVE backfill default and the no-worker-type-backfill. Uses a
     * unique email/project id per JVM run so the fixture is repeatable without cleanup. Any seeded
     * role satisfies the NOT NULL {@code project_role_id} FK; the WORKER system role is used since a
     * worker type is only meaningful for a WORKER member.
     */
    private long seedProbeMember() throws Exception {
        long roleId = queryForLong("SELECT id FROM roles WHERE code = 'WORKER' LIMIT 1");
        long uniq = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement()) {
            long userId;
            try (ResultSet rs = stmt.executeQuery(
                    "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                            + "VALUES ('mig-probe-" + uniq + "', 'mig-probe-" + uniq + "@example.com', "
                            + roleId + ", true, 'ru', NOW(), 'test') RETURNING id")) {
                rs.next();
                userId = rs.getLong(1);
            }
            // project_id is a plain BIGINT (no FK), so a synthetic unique value is fine.
            try (ResultSet rs = stmt.executeQuery(
                    "INSERT INTO project_members (user_id, project_id, project_role_id, created_date, created_by) "
                            + "VALUES (" + userId + ", " + uniq + ", " + roleId + ", NOW(), 'test') RETURNING id")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String statusOf(long memberId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT assignment_status FROM project_members WHERE id = " + memberId)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean workerTypeIdIsNull(long memberId) throws Exception {
        return queryForBoolean(
                "SELECT worker_type_id IS NULL FROM project_members WHERE id = " + memberId);
    }

    // ------------------------------------------------------------------
    // schema / catalog helpers
    // ------------------------------------------------------------------

    /**
     * A stable snapshot of the columns and table this migration adds: for each added column the
     * key is {@code table.column} and the value is {@code dataType|nullability|maxLength}, plus one
     * entry per added table marking its existence. Comparing two snapshots proves the re-run altered
     * no column type, nullability, or length and created no second table.
     */
    private Map<String, String> schemaSnapshot() throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("users.worker_kind", columnSignature("users", "worker_kind"));
        snapshot.put("users.contact_person", columnSignature("users", "contact_person"));
        snapshot.put("users.nip", columnSignature("users", "nip"));
        snapshot.put("project_members.worker_type_id", columnSignature("project_members", "worker_type_id"));
        snapshot.put("project_members.assignment_status", columnSignature("project_members", "assignment_status"));
        snapshot.put("project_member_tags.id", columnSignature("project_member_tags", "id"));
        snapshot.put("project_member_tags.project_member_id", columnSignature("project_member_tags", "project_member_id"));
        snapshot.put("project_member_tags.tag", columnSignature("project_member_tags", "tag"));
        snapshot.put("project_member_tags.order_no", columnSignature("project_member_tags", "order_no"));
        snapshot.put("table:project_member_tags", Boolean.toString(tableExists("project_member_tags")));
        return snapshot;
    }

    /** {@code dataType|isNullable|charMaxLength} for a column, read from information_schema. */
    private String columnSignature(String table, String column) throws Exception {
        String sql = "SELECT data_type, is_nullable, COALESCE(character_maximum_length, -1) "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                return "<absent>";
            }
            return rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3);
        }
    }

    private void assertColumn(String table, String column, String expectedDataType, boolean expectedNullable)
            throws Exception {
        assertThat(columnExists(table, column))
                .as("%s.%s must exist", table, column).isTrue();
        assertThat(dataType(table, column))
                .as("%s.%s data type", table, column).isEqualTo(expectedDataType);
        assertThat(isNullable(table, column))
                .as("%s.%s nullability", table, column).isEqualTo(expectedNullable);
    }

    private boolean columnExists(String table, String column) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "')");
    }

    private String dataType(String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT data_type FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean isNullable(String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            rs.next();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private long charMaxLength(String table, String column) throws Exception {
        return queryForLong(
                "SELECT COALESCE(character_maximum_length, -1) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "'");
    }

    private String columnDefault(String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COALESCE(column_default, '') FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean tableExists(String table) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "')");
    }

    private boolean foreignKeyExists(String constraintName) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND constraint_type = 'FOREIGN KEY' "
                        + "AND constraint_name = '" + constraintName + "')");
    }

    private long queryForLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean queryForBoolean(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
