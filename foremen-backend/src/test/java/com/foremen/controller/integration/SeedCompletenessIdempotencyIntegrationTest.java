package com.foremen.controller.integration;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12.3 — Seed completeness and idempotency integration test.
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}) against a
 * throwaway PostgreSQL container ({@code postgres:16-alpine}) using the same
 * {@code ClassLoaderResourceAccessor} + {@code DatabaseFactory} pattern as
 * {@link UnguardedRequiresAuthIntegrationTest}'s {@code applyMigrationsOnce()} helper, then
 * verifies the seed's <b>completeness</b> and <b>idempotency</b> at the raw-SQL level:
 *
 * <ol>
 *   <li><b>Completeness</b> — after the first migration, the matrix resource codes
 *       ({@code USERS}, {@code ROLES}, {@code AUDIT}, {@code RESOURCES}, {@code OPERATIONS},
 *       {@code PROJECT_MEMBERS}, and the FOR-05-10 {@code WORK_SCHEDULE}) all exist, and the
 *       {@code ADMIN} role holds the full {@code CREATE}/{@code READ}/{@code UPDATE}/{@code DELETE}
 *       grant on {@code USERS} (Requirements 11.1, 11.2, 11.3, 11.4).</li>
 *   <li><b>FOR-05-10 REG-03 (Requirement 18.3) — the {@code WORK_SCHEDULE} matrix row is exactly the
 *       design-D13 grants.</b> After the full changelog (changeset 153 seeds ADMIN / MANAGER CRUD,
 *       FOREMAN / ESTIMATOR READ + UPDATE, and WORKER / FINANCIER / CLIENT READ) the
 *       {@code WORK_SCHEDULE} resource exists once and holds <em>exactly</em> those grants and no
 *       others.</li>
 *   <li><b>Idempotency</b> — running {@code liquibase.update} a second time against the
 *       already-seeded database does not insert a duplicate {@code USERS} resource row and does
 *       not add extra {@code ADMIN}/{@code USERS} grants; the counts are identical before and
 *       after the re-run, proving the changeset {@code preConditions} guards (Requirement 11.4).</li>
 *   <li><b>FOR-05-09 REG-05 — the {@code PROJECT_MEMBERS} matrix row is exactly the Requirement 1
 *       grants with no {@code PROJECT_TEAM} row.</b> After the full changelog (015 seeds ADMIN /
 *       MANAGER CRUD + FOREMAN / FINANCIER READ; 136 mirrors FOREMAN's READ onto the new ESTIMATOR
 *       role) the {@code PROJECT_MEMBERS} resource exists once and holds <em>exactly</em>
 *       ADMIN&nbsp;CRUD, MANAGER&nbsp;CRUD, FOREMAN&nbsp;READ, ESTIMATOR&nbsp;READ,
 *       FINANCIER&nbsp;READ, and no {@code WORKER} or {@code CLIENT} grant (FOR-05-09 Requirement 1
 *       criteria 2–5, 7). No {@code PROJECT_TEAM} resource row is ever seeded (decision D1,
 *       Requirement 1 criterion 1).</li>
 * </ol>
 *
 * <p>The test queries via plain JDBC and does not boot a Spring context. It creates no persistent
 * state outside its own disposable container, so it is fully repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3, 11.4 (FOR-03-08); 1.1, 1.7 (FOR-05-09 REG-05)
 */
@Testcontainers
class SeedCompletenessIdempotencyIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> EXPECTED_RESOURCE_CODES = List.of(
            "USERS", "ROLES", "AUDIT", "RESOURCES", "OPERATIONS", "PROJECT_MEMBERS",
            // FOR-05-10 Requirement 18.3: the planning-Gantt WORK_SCHEDULE resource (changeset 153).
            "WORK_SCHEDULE");

    private static final List<String> ADMIN_USERS_OPERATIONS = List.of(
            "CREATE", "READ", "UPDATE", "DELETE");

    /** The full CRUD operation set, in canonical order (ADMIN / MANAGER grant on PROJECT_MEMBERS). */
    private static final List<String> CRUD = List.of("CREATE", "READ", "UPDATE", "DELETE");

    /** READ only (FOREMAN / ESTIMATOR / FINANCIER grant on PROJECT_MEMBERS). */
    private static final List<String> READ_ONLY = List.of("READ");

    /** READ + UPDATE (FOREMAN / ESTIMATOR grant on WORK_SCHEDULE — changeset 153). */
    private static final List<String> READ_UPDATE = List.of("READ", "UPDATE");

    /** The exact Requirement 1 grant map for PROJECT_MEMBERS: role code -> granted operations. */
    private static final java.util.Map<String, List<String>> EXPECTED_PROJECT_MEMBERS_GRANTS =
            java.util.Map.of(
                    "ADMIN", CRUD,
                    "MANAGER", CRUD,
                    "FOREMAN", READ_ONLY,
                    "ESTIMATOR", READ_ONLY,
                    "FINANCIER", READ_ONLY);

    /** Roles that must hold NO PROJECT_MEMBERS grant at all (Requirement 1 criterion 5). */
    private static final List<String> PROJECT_MEMBERS_UNGRANTED_ROLES = List.of("WORKER", "CLIENT");

    /**
     * The exact FOR-05-10 Requirement 1 (design D13) grant map for WORK_SCHEDULE: role code ->
     * granted operations, as seeded by changeset 153. ADMIN / MANAGER hold full CRUD; FOREMAN /
     * ESTIMATOR hold READ + UPDATE; WORKER / FINANCIER / CLIENT hold READ only. Every system role is
     * granted something, so WORK_SCHEDULE has no ungranted-role list (unlike PROJECT_MEMBERS).
     */
    private static final java.util.Map<String, List<String>> EXPECTED_WORK_SCHEDULE_GRANTS =
            java.util.Map.of(
                    "ADMIN", CRUD,
                    "MANAGER", CRUD,
                    "FOREMAN", READ_UPDATE,
                    "ESTIMATOR", READ_UPDATE,
                    "WORKER", READ_ONLY,
                    "FINANCIER", READ_ONLY,
                    "CLIENT", READ_ONLY);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    static void applyMigrations() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        runChangelog();
    }

    private static void runChangelog() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // --- Completeness (Req 11.1, 11.2, 11.3) ---

    @Test
    @DisplayName("All matrix resource codes (incl. WORK_SCHEDULE) exist after migration")
    void sixResourceCodesExist() throws Exception {
        try (Connection connection = newConnection()) {
            for (String code : EXPECTED_RESOURCE_CODES) {
                assertThat(countResourcesByCode(connection, code))
                        .as("resource code '%s' must exist exactly once after seeding", code)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("ADMIN holds the full CREATE/READ/UPDATE/DELETE grant on USERS after migration")
    void adminHoldsUsersCrudGrants() throws Exception {
        try (Connection connection = newConnection()) {
            assertThat(adminUsersRoleResourceCount(connection))
                    .as("ADMIN must have exactly one role_resources row for USERS")
                    .isEqualTo(1);

            for (String op : ADMIN_USERS_OPERATIONS) {
                assertThat(adminUsersOperationCount(connection, op))
                        .as("ADMIN must hold the '%s' operation grant on USERS", op)
                        .isEqualTo(1);
            }
        }
    }

    // --- FOR-05-09 REG-05: PROJECT_MEMBERS grants are exactly Requirement 1, no PROJECT_TEAM ---

    @Test
    @DisplayName("PROJECT_MEMBERS exists exactly once and no PROJECT_TEAM resource is ever seeded (D1)")
    void projectMembersExistsAndNoProjectTeam() throws Exception {
        try (Connection connection = newConnection()) {
            assertThat(countResourcesByCode(connection, "PROJECT_MEMBERS"))
                    .as("the PROJECT_MEMBERS resource must exist exactly once (FOR-05-09 Req 1.1)")
                    .isEqualTo(1);
            assertThat(countResourcesByCode(connection, "PROJECT_TEAM"))
                    .as("no PROJECT_TEAM resource row may be seeded — team management reuses "
                            + "PROJECT_MEMBERS (decision D1, FOR-05-09 Req 1.1)")
                    .isEqualTo(0);
        }
    }

    @Test
    @DisplayName("PROJECT_MEMBERS holds exactly ADMIN/MANAGER CRUD + FOREMAN/ESTIMATOR/FINANCIER READ")
    void projectMembersGrantsAreExactlyRequirementOne() throws Exception {
        try (Connection connection = newConnection()) {
            // Each granted role holds a single role_resources link whose operation set is EXACTLY
            // the expected one — no missing and no extra operation (Req 1 criteria 2–4, 7).
            for (var entry : EXPECTED_PROJECT_MEMBERS_GRANTS.entrySet()) {
                String roleCode = entry.getKey();
                List<String> expectedOperations = entry.getValue();

                assertThat(roleResourceCount(connection, roleCode, "PROJECT_MEMBERS"))
                        .as("role %s must hold exactly one PROJECT_MEMBERS role_resources link", roleCode)
                        .isEqualTo(1);

                assertThat(grantedOperations(connection, roleCode, "PROJECT_MEMBERS"))
                        .as("role %s must hold exactly the operations %s on PROJECT_MEMBERS "
                                + "(no missing, no extra) — FOR-05-09 Req 1 criteria 2–4, 7",
                                roleCode, expectedOperations)
                        .containsExactlyInAnyOrderElementsOf(expectedOperations);
            }

            // WORKER / CLIENT (and any role not in the expected map) hold NO grant at all
            // (Req 1 criterion 5).
            for (String roleCode : PROJECT_MEMBERS_UNGRANTED_ROLES) {
                assertThat(roleResourceCount(connection, roleCode, "PROJECT_MEMBERS"))
                        .as("role %s must hold NO PROJECT_MEMBERS role_resources link "
                                + "(deny-by-default, FOR-05-09 Req 1 criterion 5)", roleCode)
                        .isZero();
                assertThat(grantedOperations(connection, roleCode, "PROJECT_MEMBERS"))
                        .as("role %s must hold NO PROJECT_MEMBERS operation grant", roleCode)
                        .isEmpty();
            }

            // No role OTHER than the five expected ones holds any PROJECT_MEMBERS grant — this
            // catches an accidental grant to a custom/system role beyond Requirement 1.
            assertThat(rolesWithProjectMembersGrant(connection))
                    .as("exactly the five Requirement 1 roles may hold a PROJECT_MEMBERS grant; "
                            + "no other (system or custom) role may (FOR-05-09 Req 1 criterion 5)")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_PROJECT_MEMBERS_GRANTS.keySet());
        }
    }

    // --- FOR-05-10 Req 18.3: WORK_SCHEDULE grants are exactly Requirement 1 (changeset 153) ---

    @Test
    @DisplayName("WORK_SCHEDULE holds exactly ADMIN/MANAGER CRUD + FOREMAN/ESTIMATOR READ+UPDATE + WORKER/FINANCIER/CLIENT READ")
    void workScheduleGrantsAreExactlyRequirementOne() throws Exception {
        try (Connection connection = newConnection()) {
            // Each granted role holds a single role_resources link whose operation set is EXACTLY
            // the expected one — no missing and no extra operation (FOR-05-10 Req 1, design D13).
            for (var entry : EXPECTED_WORK_SCHEDULE_GRANTS.entrySet()) {
                String roleCode = entry.getKey();
                List<String> expectedOperations = entry.getValue();

                assertThat(roleResourceCount(connection, roleCode, "WORK_SCHEDULE"))
                        .as("role %s must hold exactly one WORK_SCHEDULE role_resources link", roleCode)
                        .isEqualTo(1);

                assertThat(grantedOperations(connection, roleCode, "WORK_SCHEDULE"))
                        .as("role %s must hold exactly the operations %s on WORK_SCHEDULE "
                                + "(no missing, no extra) — FOR-05-10 Req 1 / design D13",
                                roleCode, expectedOperations)
                        .containsExactlyInAnyOrderElementsOf(expectedOperations);
            }

            // No role OTHER than the seven expected ones holds any WORK_SCHEDULE grant — this
            // catches an accidental grant to a custom/system role beyond Requirement 1.
            assertThat(rolesWithGrantOn(connection, "WORK_SCHEDULE"))
                    .as("exactly the seven Requirement 1 roles may hold a WORK_SCHEDULE grant; "
                            + "no other (system or custom) role may (FOR-05-10 Req 1 / design D13)")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_WORK_SCHEDULE_GRANTS.keySet());
        }
    }

    // --- Idempotency (Req 11.4) ---

    @Test
    @DisplayName("Re-running the changelog against a seeded DB inserts no duplicate USERS resource or ADMIN grants")
    void rerunningChangelogIsIdempotent() throws Exception {
        long usersResourcesBefore;
        long adminUsersRoleResourcesBefore;
        long adminUsersOperationsBefore;

        try (Connection connection = newConnection()) {
            usersResourcesBefore = countResourcesByCode(connection, "USERS");
            adminUsersRoleResourcesBefore = adminUsersRoleResourceCount(connection);
            adminUsersOperationsBefore = adminUsersOperationTotalCount(connection);
        }

        // Apply the entire changelog a second time against the already-seeded database.
        runChangelog();

        try (Connection connection = newConnection()) {
            assertThat(countResourcesByCode(connection, "USERS"))
                    .as("re-running the changelog must not insert a duplicate USERS resource row")
                    .isEqualTo(usersResourcesBefore)
                    .isEqualTo(1);

            assertThat(adminUsersRoleResourceCount(connection))
                    .as("re-running the changelog must not add a duplicate ADMIN/USERS role_resources row")
                    .isEqualTo(adminUsersRoleResourcesBefore)
                    .isEqualTo(1);

            assertThat(adminUsersOperationTotalCount(connection))
                    .as("re-running the changelog must not add duplicate ADMIN/USERS operation grants")
                    .isEqualTo(adminUsersOperationsBefore)
                    .isEqualTo(ADMIN_USERS_OPERATIONS.size());
        }
    }

    // --- JDBC query helpers ---

    private static long countResourcesByCode(Connection connection, String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM resources WHERE code = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, code);
            return singleLong(ps);
        }
    }

    private static long adminUsersRoleResourceCount(Connection connection) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resources rr
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                WHERE r.code = 'ADMIN' AND res.code = 'USERS'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            return singleLong(ps);
        }
    }

    private static long adminUsersOperationCount(Connection connection, String operationCode) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resource_operations rro
                JOIN role_resources rr ON rro.role_resource_id = rr.id
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                JOIN operations o ON rro.operation_id = o.id
                WHERE r.code = 'ADMIN' AND res.code = 'USERS' AND o.code = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationCode);
            return singleLong(ps);
        }
    }

    private static long adminUsersOperationTotalCount(Connection connection) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resource_operations rro
                JOIN role_resources rr ON rro.role_resource_id = rr.id
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                WHERE r.code = 'ADMIN' AND res.code = 'USERS'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            return singleLong(ps);
        }
    }

    /** Number of role_resources links the given role holds on the given resource. */
    private static long roleResourceCount(Connection connection, String roleCode, String resourceCode)
            throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM role_resources rr
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                WHERE r.code = ? AND res.code = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, resourceCode);
            return singleLong(ps);
        }
    }

    /** The operation codes the given role is granted on the given resource. */
    private static List<String> grantedOperations(
            Connection connection, String roleCode, String resourceCode) throws Exception {
        String sql = """
                SELECT o.code
                FROM role_resource_operations rro
                JOIN role_resources rr ON rro.role_resource_id = rr.id
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                JOIN operations o ON rro.operation_id = o.id
                WHERE r.code = ? AND res.code = ?
                """;
        List<String> operations = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    operations.add(rs.getString(1));
                }
            }
        }
        return operations;
    }

    /** The codes of every role that holds any PROJECT_MEMBERS role_resources link. */
    private static List<String> rolesWithProjectMembersGrant(Connection connection) throws Exception {
        return rolesWithGrantOn(connection, "PROJECT_MEMBERS");
    }

    /** The codes of every role that holds any role_resources link on the given resource. */
    private static List<String> rolesWithGrantOn(Connection connection, String resourceCode)
            throws Exception {
        String sql = """
                SELECT DISTINCT r.code
                FROM role_resources rr
                JOIN roles r ON rr.role_id = r.id
                JOIN resources res ON rr.resource_id = res.id
                WHERE res.code = ?
                """;
        List<String> roles = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, resourceCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        }
        return roles;
    }

    private static long singleLong(PreparedStatement ps) throws Exception {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
