package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * FOR-06-12 task 10.2 — {@code CLIENT_MESSAGES} ABAC-seed migration + seed-idempotency integration
 * test (design §ABAC seed; Requirements 8.1, 8.2, 8.4; entity-creation-rules steps 1-2).
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers the
 * FOR-06-12 changesets {@code 183}–{@code 186} last — {@code 186-seed-client-messages-resource.xml}
 * seeds the resource + the per-role grants) against a Testcontainers PostgreSQL, proves the end state
 * the seed changeset describes, and then proves re-running the full changelog changes nothing (the
 * double-run idempotency check this task calls for):
 *
 * <ul>
 *   <li><b>resource</b> — {@code 186} seeds the {@code CLIENT_MESSAGES} resource row exactly once,
 *       with the PL/RU names/descriptions;</li>
 *   <li><b>matrix (R8.1, R8.4)</b> — ADMIN CREATE/READ/UPDATE/DELETE; CLIENT CREATE/READ; MANAGER
 *       CREATE/READ/UPDATE; FOREMAN READ; FINANCIER READ; ESTIMATOR READ; WORKER no grant
 *       (deny-by-default);</li>
 *   <li><b>idempotency (double-run)</b> — a second application of the full changelog inserts no
 *       duplicate resource / role_resource / operation-grant row and errors nowhere, guarded by the
 *       {@code onFail="MARK_RAN"} {@code COUNT(*)=0} preconditions plus the per-row
 *       {@code NOT EXISTS} self-guards in changeset 186.</li>
 * </ul>
 *
 * <p>Harness mirrors {@link PayrollMigrationIntegrationTest} /
 * {@link OfferApprovalMigrationIntegrationTest}: it drives Liquibase directly against the container
 * (no Spring context) and queries via raw JDBC, so the assertions observe exactly what the migration
 * writes. The full changelog includes changeset {@code 160} (seed document templates) whose
 * {@code uploaded_by_id} subselect needs a seeded ADMIN user (created by the app at startup in the
 * real stack, not by the changelog); a bare apply against an empty DB therefore stops at 160, so the
 * harness seeds a minimal ADMIN once the {@code users}/{@code roles} tables exist and resumes,
 * exactly as {@code PayrollMigrationIntegrationTest} does. Each JVM run gets a fresh container, so it
 * is inherently repeatable with no manual cleanup.
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.4
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-06-12-client-communication, task 10.2: CLIENT_MESSAGES seed idempotency + ABAC matrix")
class ClientMessagesSeedMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE = "CLIENT_MESSAGES";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applyFullChangelog();
    }

    /**
     * Applies the full changelog, seeding a prerequisite ADMIN user if the first pass stops at the
     * document-template seed (changeset 160), then resuming so the FOR-06-12 changesets 183–186 are
     * reached.
     */
    private static void applyFullChangelog() throws Exception {
        try {
            runChangelog();
        } catch (Exception first) {
            // Expected on an empty DB: changeset 160 needs an ADMIN user. Seed one and resume.
            seedAdminUserIfPossible();
            runChangelog();
        }
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

    /**
     * Seeds a single ADMIN {@code users} row (referencing the already-seeded ADMIN role) so the
     * document-template changeset's {@code uploaded_by_id} subselect resolves. No-op if the ADMIN
     * role is not yet present or an ADMIN user already exists.
     */
    private static void seedAdminUserIfPossible() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
                    "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
            stmt.executeUpdate(
                    "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                            + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId
                            + ", true, 'pl', NOW(), 'test')");
        }
    }

    // --- 186 ChangeSet A: CLIENT_MESSAGES resource ----------------------------------------------

    @Test
    @DisplayName("186 seeds the CLIENT_MESSAGES resource exactly once with PL/RU names")
    void resourceSeeded() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = '" + RESOURCE + "'"))
                .as("the CLIENT_MESSAGES resource must be seeded exactly once (R8.2)").isEqualTo(1L);
        assertThat(queryForString(
                "SELECT name_pl FROM resources WHERE code = '" + RESOURCE + "'"))
                .as("the resource carries a PL name").isEqualTo("Komunikacja z klientem");
        assertThat(queryForString(
                "SELECT name_ru FROM resources WHERE code = '" + RESOURCE + "'"))
                .as("the resource carries a RU name").isEqualTo("Общение с клиентом");
    }

    // --- 186 ChangeSet B: the CLIENT_MESSAGES ABAC matrix (R8.1, R8.4) --------------------------

    @Test
    @DisplayName("186 seeds the CLIENT_MESSAGES ABAC matrix per the design table (R8.1, R8.4)")
    void abacMatrixSeeded() throws Exception {
        assertThat(operationsFor("ADMIN", RESOURCE))
                .as("ADMIN holds full CRUD on CLIENT_MESSAGES (matrix completeness; evaluator bypasses ADMIN)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE");
        assertThat(operationsFor("MANAGER", RESOURCE))
                .as("MANAGER holds CREATE/READ/UPDATE (post, read, mark-read)")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE");
        assertThat(operationsFor("CLIENT", RESOURCE))
                .as("CLIENT holds CREATE/READ (post questions + read own thread; no reply/mark-read/delete)")
                .containsExactlyInAnyOrder("CREATE", "READ");
        assertThat(operationsFor("FOREMAN", RESOURCE))
                .as("FOREMAN holds READ only (full visibility, no post/reply/mark-read)")
                .containsExactly("READ");
        assertThat(operationsFor("FINANCIER", RESOURCE))
                .as("FINANCIER holds READ only (admin-staff visibility)")
                .containsExactly("READ");
        assertThat(operationsFor("ESTIMATOR", RESOURCE))
                .as("ESTIMATOR holds READ only (admin-staff visibility)")
                .containsExactly("READ");
    }

    @Test
    @DisplayName("WORKER holds no CLIENT_MESSAGES grant (deny-by-default)")
    void workerHasNoGrant() throws Exception {
        assertThat(operationsFor("WORKER", RESOURCE))
                .as("WORKER must have no grant on CLIENT_MESSAGES (deny-by-default)")
                .isEmpty();
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN roles r ON rr.role_id = r.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE r.code = 'WORKER' AND res.code = '" + RESOURCE + "'"))
                .as("no WORKER role_resources row may exist on CLIENT_MESSAGES")
                .isEqualTo(0L);
    }

    // --- double-run: re-running the changelog is a no-op ----------------------------------------

    @Test
    @DisplayName("re-running the changelog duplicates no CLIENT_MESSAGES resource/grant and errors nowhere")
    void reRunIsNoOp() throws Exception {
        long resourceBefore = queryForLong(
                "SELECT COUNT(*) FROM resources WHERE code = '" + RESOURCE + "'");
        long roleResBefore = queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = '" + RESOURCE + "'");
        long grantsBefore = queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = '" + RESOURCE + "'");
        int adminBefore = operationsFor("ADMIN", RESOURCE).size();
        int managerBefore = operationsFor("MANAGER", RESOURCE).size();
        int clientBefore = operationsFor("CLIENT", RESOURCE).size();

        // Apply the same changelog a second time — must not error, must not change anything.
        runChangelog();

        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = '" + RESOURCE + "'"))
                .as("re-run must not duplicate the CLIENT_MESSAGES resource")
                .isEqualTo(resourceBefore).isEqualTo(1L);
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = '" + RESOURCE + "'"))
                .as("re-run must not duplicate any CLIENT_MESSAGES role_resources row")
                .isEqualTo(roleResBefore);
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE res.code = '" + RESOURCE + "'"))
                .as("re-run must not duplicate any CLIENT_MESSAGES operation grant")
                .isEqualTo(grantsBefore);
        assertThat(operationsFor("ADMIN", RESOURCE).size())
                .as("re-run must not change ADMIN grants").isEqualTo(adminBefore);
        assertThat(operationsFor("MANAGER", RESOURCE).size())
                .as("re-run must not change MANAGER grants").isEqualTo(managerBefore);
        assertThat(operationsFor("CLIENT", RESOURCE).size())
                .as("re-run must not change CLIENT grants").isEqualTo(clientBefore);
        // WORKER stays ungranted on the re-run.
        assertThat(operationsFor("WORKER", RESOURCE))
                .as("re-run must not grant WORKER anything").isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    /** The operation codes a role holds on a resource (empty when the role has no grant). */
    private List<String> operationsFor(String roleCode, String resourceCode) throws Exception {
        List<String> ops = new ArrayList<>();
        String sql =
                "SELECT o.code FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN roles r ON rr.role_id = r.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "JOIN operations o ON rro.operation_id = o.id "
                        + "WHERE r.code = '" + roleCode + "' AND res.code = '" + resourceCode + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ops.add(rs.getString(1));
            }
        }
        return ops;
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

    private String queryForString(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
