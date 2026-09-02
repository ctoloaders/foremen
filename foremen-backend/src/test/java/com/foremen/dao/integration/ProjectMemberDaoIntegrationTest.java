package com.foremen.dao.integration;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DAO-level integration test for {@link ProjectMemberDao}, exercising the real repository against a
 * real PostgreSQL instance (Testcontainers). It complements
 * {@code ProjectMemberMigrationIntegrationTest} (which verifies the {@code 014} Liquibase migration
 * itself) by driving the mapped entity and the {@code findDistinctProjectIdsByUserId} query the
 * production {@code ProjectAccessCache} loader relies on.
 *
 * <ul>
 *     <li>{@code findDistinctProjectIdsByUserId} returns the deduplicated set of project ids for a
 *         user and never leaks another user's memberships (Requirement 2.6);</li>
 *     <li>the {@code (user_id, project_id)} unique constraint rejects a second membership row for
 *         the same pair at the ORM layer (Requirement 1.5).</li>
 * </ul>
 *
 * <p>Mirrors the {@code @SpringBootTest} + Testcontainers + {@code @ActiveProfiles("integration-test")}
 * convention of {@code InviteCreateAtomicityIntegrationTest} /
 * {@code PermissionInvalidationRoundTripIntegrationTest}: Hibernate {@code create-drop} builds the
 * schema from the mapped entities (including {@code ProjectMemberEntity}'s
 * {@code uk_project_members_user_project} constraint), and every row is seeded under a unique
 * {@code run-id} suffix so the suite re-runs without manual clean-up.
 *
 * <p>Validates: Requirements 1.5, 2.6
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectMemberDaoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column, matching the deployed app.
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private ProjectMemberDao projectMemberDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    /** Unique run identifier so seeded rows never collide across repeated runs. */
    private String runId;
    private RoleEntity role;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();
    }

    @Test
    @DisplayName("findDistinctProjectIdsByUserId returns the deduplicated project-id set for the user (2.6)")
    void findDistinctProjectIdsByUserId_returnsDistinctSetForUser() {
        UserEntity user = seedUser();
        UserEntity otherUser = seedUser();

        // The user belongs to projects 101 and 102 (with a duplicate-project row on a distinct id
        // impossible due to the unique constraint, so distinctness is exercised via the DISTINCT
        // keyword over the query rather than duplicate pairs). A membership for another user on
        // project 999 must not leak into this user's set.
        saveMembership(user, 101L);
        saveMembership(user, 102L);
        saveMembership(otherUser, 999L);

        Set<Long> projectIds = projectMemberDao.findDistinctProjectIdsByUserId(user.getId());

        assertThat(projectIds)
                .as("distinct project ids for the user")
                .containsExactlyInAnyOrder(101L, 102L)
                .doesNotContain(999L);
    }

    @Test
    @DisplayName("findDistinctProjectIdsByUserId returns an empty set for a user with no memberships (2.6)")
    void findDistinctProjectIdsByUserId_emptyForUserWithoutMemberships() {
        UserEntity user = seedUser();

        assertThat(projectMemberDao.findDistinctProjectIdsByUserId(user.getId()))
                .as("a user with no memberships has an empty project-id set")
                .isEmpty();
    }

    @Test
    @DisplayName("The (user_id, project_id) unique constraint rejects a duplicate membership row (1.5)")
    void uniqueConstraint_rejectsDuplicateMembership() {
        UserEntity user = seedUser();
        long projectId = 202L;

        saveMembership(user, projectId);

        // A second membership for the same (user_id, project_id) pair violates
        // uk_project_members_user_project and must be rejected. Each repository save commits in its
        // own transaction (the test is not @Transactional), so the INSERT is flushed to the database
        // and the constraint violation surfaces as a DataIntegrityViolationException.
        assertThatThrownBy(() -> saveMembership(user, projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Seeding helpers (unique per run via runId) ---

    private RoleEntity seedRole() {
        RoleEntity r = new RoleEntity();
        r.setCode("PM-DAO-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("PM DAO Test User");
        u.setEmail("pm-dao+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private ProjectMemberEntity saveMembership(UserEntity user, Long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        return projectMemberDao.save(member);
    }
}
