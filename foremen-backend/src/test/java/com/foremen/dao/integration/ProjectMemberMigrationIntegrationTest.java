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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that applies the full Liquibase changelog against a real PostgreSQL instance
 * (Testcontainers) and verifies the {@code project_members} schema introduced by changeset
 * {@code 014-create-project-members}:
 * <ul>
 *     <li>the table and its columns/constraints are created (Requirement 1.1);</li>
 *     <li>the {@code (user_id, project_id)} unique constraint rejects a duplicate membership
 *         (Requirement 1.5);</li>
 *     <li>the distinct-project-ids-by-user query returns the deduplicated project-id set for a
 *         user, matching {@code ProjectMemberDao.findDistinctProjectIdsByUserId}'s JPQL
 *         ({@code SELECT DISTINCT pm.projectId ... WHERE pm.user.id = :userId}) (Requirement 2.6).</li>
 * </ul>
 *
 * <p>Mirrors {@code InviteTokenMigrationIntegrationTest} (changeset 013) and
 * {@code AuthMigrationIntegrationTest} (010/011/012), which established the pattern for
 * structural migration verification in this module: run the real changelog against a real
 * database and assert against the live catalog, rather than relying on Hibernate DDL.
 *
 * <p>Validates: Requirements 1.1, 1.5, 2.6
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMemberMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

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

    // --- Requirement 1.1 : project_members table, columns and constraints ---
    @Test
    @DisplayName("Changeset 014 creates the project_members table with its columns and constraints")
    void migrationCreatesProjectMembersTable() throws Exception {
        assertThat(tableExists("project_members")).as("project_members table").isTrue();

        // Columns with exact types and nullability (1.1).
        assertColumnType("project_members", "id", "bigint", null, false);
        assertColumnType("project_members", "user_id", "bigint", null, false);
        // project_id is a plain BIGINT with no FK (Requirement 1.4): the projects table arrives in FOR-06.
        assertColumnType("project_members", "project_id", "bigint", null, false);
        assertColumnType("project_members", "project_role_id", "bigint", null, false);
        assertColumnType("project_members", "created_date", "timestamp without time zone", null, false);
        assertColumnType("project_members", "created_by", "character varying", 255, true);
        assertColumnType("project_members", "updated_date", "timestamp without time zone", null, true);
        assertColumnType("project_members", "updated_by", "character varying", 255, true);

        // Constraints: PK on id, unique on (user_id, project_id), FKs to users and roles.
        assertThat(hasPrimaryKey("project_members", "id")).as("project_members PK on id").isTrue();
        assertThat(constraintExists("uk_project_members_user_project"))
                .as("named unique constraint uk_project_members_user_project").isTrue();
        // project_id must NOT carry a foreign key (Requirement 1.4).
        assertThat(hasAnyForeignKey("project_members", "project_id"))
                .as("project_members.project_id must have no FK").isFalse();
        assertThat(hasForeignKey("project_members", "user_id", "users"))
                .as("project_members.user_id FK -> users").isTrue();
        assertThat(hasForeignKey("project_members", "project_role_id", "roles"))
                .as("project_members.project_role_id FK -> roles").isTrue();
    }

    // --- Requirement 1.5 : (user_id, project_id) unique constraint rejects a duplicate row ---
    @Test
    @DisplayName("The (user_id, project_id) unique constraint rejects a second membership row for the same pair")
    void uniqueConstraintRejectsDuplicateMembership() throws Exception {
        long userId = insertUser();
        long roleId = clientRoleId();
        long projectId = 4001L + (System.nanoTime() % 1_000_000L);

        // First membership row for (userId, projectId) succeeds.
        insertMembership(userId, projectId, roleId);

        // A second row for the SAME user_id and project_id pair must be rejected by the named
        // constraint uk_project_members_user_project; the project role is irrelevant because the
        // pair is what the constraint makes unique (Requirement 1.5).
        assertThatThrownBy(() -> insertMembership(userId, projectId, roleId))
                .isInstanceOf(SQLException.class)
                .as("second membership for the same (user_id, project_id) is rejected");

        // Exactly one row survives; the duplicate was never persisted.
        assertThat(countMembershipsFor(userId, projectId)).isEqualTo(1);
    }

    // --- Requirement 2.6 : distinct project ids for a user ---
    @Test
    @DisplayName("The distinct-project-ids query returns the deduplicated project-id set for a user")
    void distinctProjectIdsByUserReturnsDeduplicatedSet() throws Exception {
        long userId = insertUser();
        long otherUserId = insertUser();
        long roleId = clientRoleId();

        long base = 5000L + (System.nanoTime() % 1_000_000L);
        long projectA = base;
        long projectB = base + 1;
        long projectC = base + 2;

        // The user belongs to projects A and B; a row for another user on project C must not leak in.
        insertMembership(userId, projectA, roleId);
        insertMembership(userId, projectB, roleId);
        insertMembership(otherUserId, projectC, roleId);

        // Mirrors ProjectMemberDao.findDistinctProjectIdsByUserId JPQL:
        // SELECT DISTINCT pm.projectId FROM ProjectMemberEntity pm WHERE pm.user.id = :userId
        List<Long> projectIds = distinctProjectIdsByUser(userId);

        assertThat(projectIds)
                .as("distinct project ids for the user")
                .containsExactlyInAnyOrder(projectA, projectB)
                .doesNotContain(projectC);

        // A user with no memberships yields an empty set.
        long userWithoutMemberships = insertUser();
        assertThat(distinctProjectIdsByUser(userWithoutMemberships))
                .as("a user with no memberships has an empty project-id set")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // seeding helpers
    // ---------------------------------------------------------------------

    private long insertUser() throws Exception {
        // Reuse the CLIENT role seeded by changeset 006; a valid role_id keeps the FK satisfied.
        String sql = "INSERT INTO users (name, email, role_id, status, created_date) "
                + "VALUES (?, ?, (SELECT id FROM roles WHERE code = 'CLIENT' LIMIT 1), 'INVITED', NOW()) "
                + "RETURNING id";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "Project Member Migration Test User");
            ps.setString(2, "project-member-migration+" + System.nanoTime() + "@example.com");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long clientRoleId() throws Exception {
        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM roles WHERE code = 'CLIENT' LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertMembership(long userId, long projectId, long projectRoleId) throws Exception {
        String sql = "INSERT INTO project_members (user_id, project_id, project_role_id, created_date) "
                + "VALUES (?, ?, ?, NOW())";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, projectId);
            ps.setLong(3, projectRoleId);
            ps.executeUpdate();
        }
    }

    private int countMembershipsFor(long userId, long projectId) throws Exception {
        String sql = "SELECT COUNT(*) FROM project_members WHERE user_id = ? AND project_id = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<Long> distinctProjectIdsByUser(long userId) throws Exception {
        String sql = "SELECT DISTINCT project_id FROM project_members WHERE user_id = ?";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
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

    private boolean hasAnyForeignKey(String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' "
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
}
