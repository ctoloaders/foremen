package com.foremen.dao.integration;

import com.foremen.service.execution.amendment.AmendmentService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-06-08 (Requirements 9.2, 9.3, 9.4; design §"ABAC seed (changeset 179)" and §Testing strategy
 * "ABAC — matrix property test") — applies the full Liquibase changelog against a real PostgreSQL
 * instance (Testcontainers) and verifies the {@code AMENDMENTS} ABAC resource seed introduced by
 * changeset {@code 179-seed-amendments-resource}:
 *
 * <ul>
 *   <li><b>R9.2 — the role matrix.</b> A <em>property over the whole system-role space</em>: for
 *       every one of the six system roles, the operations resolved against {@code AMENDMENTS} are
 *       exactly those the matrix prescribes — ADMIN / MANAGER / FINANCIER resolve the full
 *       {@code CREATE, READ, UPDATE, DELETE}; FOREMAN resolves {@code READ} only (no write); WORKER
 *       and CLIENT resolve <em>no</em> operations at all (deny-by-default — not even a
 *       {@code role_resources} row). Driven as a {@code @ParameterizedTest} fed the full matrix so
 *       the assertion holds universally across the role dimension, not only for a hand-picked role.
 *   </li>
 *   <li><b>R9.3 — project-scope enforced.</b> {@code AMENDMENTS} is a project-scoped resource; its
 *       access is additionally gated by project membership in {@link AmendmentService}
 *       (a {@code ProjectScopedService}) whose single mandatory per-entity override
 *       {@link AmendmentService#getProjectIdPath()} returns the path to the owning project id
 *       ({@code "project.id"}). Asserted directly against the production service contract — that is
 *       the mechanism the existing project-scoped resources use to express scope (there is no
 *       per-resource scope column; scope is a service-layer contract).
 *   </li>
 *   <li><b>R9.4 — seed idempotency through the migration run.</b> Re-applying the entire changelog
 *       is a no-op for the {@code AMENDMENTS} resource, its {@code role_resources}, and its
 *       {@code role_resource_operations}: every 179 changeset is guarded by {@code MARK_RAN} +
 *       {@code sqlCheck expectedResult="0"}, so a second application inserts nothing (row / grant
 *       counts and the per-role grant set are unchanged).
 *   </li>
 * </ul>
 *
 * <p>Mirrors {@code PackageAssortmentResourceSeedIntegrationTest}: run the real changelog against a
 * real database via raw Liquibase and assert against the live catalog through raw JDBC on the
 * {@code resources} / {@code role_resources} / {@code role_resource_operations} / {@code roles} /
 * {@code operations} tables.
 *
 * <p><b>Validates: Requirements 9.2, 9.3, 9.4</b>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmendmentsResourceSeedIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "AMENDMENTS";

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

    /**
     * Applies the full changelog. The changelog includes changeset 160 (seed document templates),
     * whose {@code uploaded_by_id} subselect requires a seeded ADMIN <em>user</em> — a user the
     * changelog itself never seeds (in the real stack the ADMIN is created by the app at startup,
     * after migrations). A bare apply against an empty DB therefore stops at 160. We mirror the
     * established harness ({@code WorkReportMigrationIntegrationTest}): run the changelog (it may
     * stop at 160), seed a minimal ADMIN user once {@code users}/{@code roles} exist, then resume —
     * Liquibase retries the un-recorded failed changeset and continues through 179. The re-run in
     * the idempotency test is a full second pass once the ADMIN user already exists, so it applies
     * nothing new.
     */
    private void runLiquibase() throws Exception {
        try {
            runChangelogOnce();
        } catch (Exception first) {
            // Expected on an empty DB: changeset 160 needs an ADMIN user. Seed one and resume.
            seedAdminUserIfPossible();
            runChangelogOnce();
        }
    }

    private void runChangelogOnce() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    /**
     * Seeds a single ADMIN {@code users} row (referencing the already-seeded ADMIN role) so the
     * document-template changeset's {@code uploaded_by_id} subselect resolves. No-op if the ADMIN
     * role is not yet present or an ADMIN user already exists.
     */
    private void seedAdminUserIfPossible() throws Exception {
        try (Connection connection = newConnection();
             var stmt = connection.createStatement()) {
            long adminRoleId;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)")) {
                rs.next();
                adminRoleId = rs.getLong(1);
            }
            if (adminRoleId == 0) {
                return;
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id "
                            + "WHERE r.code = 'ADMIN'")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
            stmt.executeUpdate(
                    "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                            + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId
                            + ", true, 'ru', NOW(), 'test')");
        }
    }

    // --- R9.2 : the AMENDMENTS resource row is seeded exactly once ---
    @Test
    @DisplayName("Changeset 179 seeds exactly one AMENDMENTS resource row")
    void seedsAmendmentsResource() throws Exception {
        assertThat(countResource(RESOURCE_CODE))
                .as("AMENDMENTS resource row count").isEqualTo(1);
    }

    // --- R9.2 : the role matrix holds for EVERY system role (property over the role space) ---
    //
    // The expected matrix: ADMIN / MANAGER / FINANCIER -> CRUD; FOREMAN -> READ only;
    // WORKER / CLIENT -> no grant (deny-by-default). Driven over all six roles so the assertion is
    // universal across the role dimension, not a single hand-picked example.
    static Stream<Arguments> roleMatrix() {
        return Stream.of(
                Arguments.of("ADMIN", List.of("CREATE", "READ", "UPDATE", "DELETE")),
                Arguments.of("MANAGER", List.of("CREATE", "READ", "UPDATE", "DELETE")),
                Arguments.of("FINANCIER", List.of("CREATE", "READ", "UPDATE", "DELETE")),
                Arguments.of("FOREMAN", List.of("READ")),
                Arguments.of("WORKER", List.of()),
                Arguments.of("CLIENT", List.of()));
    }

    @ParameterizedTest(name = "{0} resolves exactly {1} on AMENDMENTS")
    @MethodSource("roleMatrix")
    @DisplayName("R9.2: per-system-role grants on AMENDMENTS match the matrix for every role")
    void grantsPerSystemRoleMatchMatrix(String roleCode, List<String> expectedOps)
            throws Exception {
        List<String> actual = operationsFor(roleCode);
        if (expectedOps.isEmpty()) {
            // deny-by-default: not even a role_resources row exists for this role
            assertThat(hasRoleResource(roleCode))
                    .as("%s must have NO role_resources row on AMENDMENTS (deny-by-default)", roleCode)
                    .isFalse();
            assertThat(actual)
                    .as("%s must resolve no operations on AMENDMENTS", roleCode)
                    .isEmpty();
        } else {
            assertThat(actual)
                    .as("%s operations on AMENDMENTS", roleCode)
                    .containsExactlyInAnyOrderElementsOf(expectedOps);
        }
    }

    // --- R9.3 : AMENDMENTS is project-scoped (service-layer contract) ---
    @Test
    @DisplayName("R9.3: AmendmentService enforces project-scope via getProjectIdPath() = project.id")
    void amendmentsResourceIsProjectScoped() {
        // AMENDMENTS has no per-resource scope column; project-scope is a ProjectScopedService
        // contract. getProjectIdPath() is a pure constant accessor touching no injected field, so a
        // bare instance suffices to assert the production scope path.
        AmendmentService service =
                new AmendmentService(null, null, null, null, null, null, null, null);
        assertThat(service.getProjectIdPath())
                .as("AMENDMENTS owning-project id path (project-scope, R9.3)")
                .isEqualTo("project.id");
    }

    // --- R9.4 : re-running the changelog does not duplicate rows (seed idempotency) ---
    @Test
    @DisplayName("R9.4: re-running the changelog does not duplicate the AMENDMENTS resource or grants")
    void reRunningChangelogIsIdempotent() throws Exception {
        int resourceCountBefore = countResource(RESOURCE_CODE);
        int roleResourcesBefore = countRoleResources(RESOURCE_CODE);
        int roleResourceOperationsBefore = countRoleResourceOperations(RESOURCE_CODE);
        Map<String, List<String>> grantsBefore = snapshotGrants();

        // Re-apply the entire changelog. The 179 changesets are guarded by MARK_RAN + sqlCheck
        // expectedResult="0" preconditions, so a second application inserts nothing new.
        runLiquibase();

        assertThat(countResource(RESOURCE_CODE))
                .as("resource row count unchanged after re-run")
                .isEqualTo(resourceCountBefore)
                .isEqualTo(1);
        assertThat(countRoleResources(RESOURCE_CODE))
                .as("role_resources row count unchanged after re-run")
                .isEqualTo(roleResourcesBefore);
        assertThat(countRoleResourceOperations(RESOURCE_CODE))
                .as("role_resource_operations row count unchanged after re-run")
                .isEqualTo(roleResourceOperationsBefore);
        assertThat(snapshotGrants())
                .as("per-role grant set unchanged after re-run")
                .isEqualTo(grantsBefore);
    }

    // ---------------------------------------------------------------------
    // catalog query helpers
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

    /** Whether the given role has a role_resources row on the AMENDMENTS resource. */
    private boolean hasRoleResource(String roleCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE r.code = ? AND res.code = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, RESOURCE_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** The set of operation codes granted to the given role on the AMENDMENTS resource. */
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

    /** A stable snapshot of role_code -> sorted operation codes on AMENDMENTS. */
    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new java.util.TreeMap<>();
        for (String roleCode : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT")) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
