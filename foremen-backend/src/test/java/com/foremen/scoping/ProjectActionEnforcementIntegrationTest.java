package com.foremen.scoping;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * End-to-end integration test proving the FOR-03-04a <em>action-level</em> project-ownership check is
 * enforced on the single-entity CRUD paths (read-by-id, update-by-id, delete-by-id) that flow through
 * {@link com.foremen.service.ProjectScopedService}, exercised against the throwaway
 * {@link ScopedFixtureService} / {@link ScopedFixtureEntity} fixture (no real project-scoped entity
 * exists yet — those arrive in FOR-06).
 *
 * <p>The fixture service implements exactly one CRUD contract — {@code ProjectScopedService} — and
 * supplies only {@code getProjectIdPath()} + plumbing, relying on the inherited by-id overrides and
 * the default {@code getProjectId} resolver. Real {@code project_members} rows are seeded (via
 * {@link ProjectMemberDao}) so the {@link ProjectAccessCache} self-loads genuine memberships, and the
 * principal name is set to the seeded user's numeric id, mirroring how the JWT layer populates the
 * {@code SecurityContext}.
 *
 * <p>For each of {@code findById}, {@code update}, and {@code deleteById} the suite asserts:
 * <ul>
 *     <li>an ADMIN caller is allowed regardless of ownership (Requirements 3.4, 4.4, 5.4);</li>
 *     <li>a non-ADMIN caller acting on an in-scope entity is allowed (Requirements 3.2, 4.3, 5.3);</li>
 *     <li>a non-ADMIN caller acting on an out-of-scope entity is denied with 404
 *         {@code error.entity.not.found} (Requirements 3.3, 4.2, 5.2);</li>
 *     <li>a non-ADMIN caller with no memberships is denied (Requirement 2.5);</li>
 *     <li>an unauthenticated caller is denied (Requirement 2.3).</li>
 * </ul>
 *
 * <p>Every row is seeded under a unique {@code run-id} suffix and the {@link ProjectAccessCache} entry
 * is invalidated per test, so the suite re-runs without manual clean-up.
 *
 * <p>Validates: Requirements 2.3, 2.5, 3.2, 3.3, 3.4, 4.2, 4.3, 4.4, 5.2, 5.3, 5.4, 8.2
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectActionEnforcementIntegrationTest {

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
    private ScopedFixtureService scopedFixtureService;

    @Autowired
    private ScopedFixtureDao scopedFixtureDao;

    @Autowired
    private ProjectMemberDao projectMemberDao;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    /** Unique run identifier so seeded rows never collide across repeated runs. */
    private String runId;
    private RoleEntity role;

    /** Distinct project ids: the caller is a member of {@code inScopeProject} but not {@code outScopeProject}. */
    private long inScopeProject;
    private long outScopeProject;

    /** A fixture row in the in-scope project and one in the out-of-scope project. */
    private Long inScopeEntityId;
    private Long outScopeEntityId;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();

        long base = System.nanoTime();
        inScopeProject = base + 1;
        outScopeProject = base + 2;

        // Filter on the single-segment "projectId" path (the fixture service's default).
        scopedFixtureService.setProjectIdPath("projectId");
        inScopeEntityId = seedFixture("IN-" + runId, inScopeProject);
        outScopeEntityId = seedFixture("OUT-" + runId, outScopeProject);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------------
    // findById (Requirements 3.2, 3.3, 3.4)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("findById: ADMIN caller allowed regardless of ownership (3.4)")
    void findByIdAdminAllowedRegardlessOfOwnership() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        // ADMIN bypass -> reads both the in-scope AND the out-of-scope entity.
        assertThat(scopedFixtureService.findById(inScopeEntityId).getLabel()).isEqualTo("IN-" + runId);
        assertThat(scopedFixtureService.findById(outScopeEntityId).getLabel()).isEqualTo("OUT-" + runId);
    }

    @Test
    @DisplayName("findById: non-ADMIN allowed on an in-scope entity (3.2)")
    void findByIdNonAdminAllowedInScope() {
        UserEntity user = seedUser();
        saveMembership(user, inScopeProject);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        ScopedFixtureServiceExtendedModel found = scopedFixtureService.findById(inScopeEntityId);
        assertThat(found.getLabel()).isEqualTo("IN-" + runId);
        assertThat(found.getProjectId()).isEqualTo(inScopeProject);
    }

    @Test
    @DisplayName("findById: non-ADMIN denied on an out-of-scope entity with 404 error.entity.not.found (3.3)")
    void findByIdNonAdminDeniedOutOfScope() {
        UserEntity user = seedUser();
        saveMembership(user, inScopeProject);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> scopedFixtureService.findById(outScopeEntityId));
    }

    @Test
    @DisplayName("findById: non-ADMIN with no memberships denied (2.5)")
    void findByIdNonAdminWithoutMembershipsDenied() {
        UserEntity user = seedUser();
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> scopedFixtureService.findById(inScopeEntityId));
    }

    @Test
    @DisplayName("findById: unauthenticated caller denied (2.3)")
    void findByIdUnauthenticatedDenied() {
        SecurityContextHolder.clearContext();

        assertDenied(() -> scopedFixtureService.findById(inScopeEntityId));
    }

    // ----------------------------------------------------------------------
    // update (Requirements 4.2, 4.3, 4.4)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("update: ADMIN caller allowed regardless of ownership (4.4)")
    void updateAdminAllowedRegardlessOfOwnership() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        ScopedFixtureServiceExtendedModel updated =
                scopedFixtureService.update(outScopeEntityId, modelWithLabel("OUT-updated-" + runId));

        assertThat(updated.getLabel()).isEqualTo("OUT-updated-" + runId);
        assertThat(scopedFixtureDao.findById(outScopeEntityId).orElseThrow().getLabel())
                .isEqualTo("OUT-updated-" + runId);
    }

    @Test
    @DisplayName("update: non-ADMIN allowed on an in-scope entity (4.3)")
    void updateNonAdminAllowedInScope() {
        UserEntity user = seedUser();
        saveMembership(user, inScopeProject);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        ScopedFixtureServiceExtendedModel updated =
                scopedFixtureService.update(inScopeEntityId, modelWithLabel("IN-updated-" + runId));

        assertThat(updated.getLabel()).isEqualTo("IN-updated-" + runId);
        assertThat(scopedFixtureDao.findById(inScopeEntityId).orElseThrow().getLabel())
                .isEqualTo("IN-updated-" + runId);
    }

    @Test
    @DisplayName("update: non-ADMIN denied on an out-of-scope entity with 404 error.entity.not.found (4.2)")
    void updateNonAdminDeniedOutOfScope() {
        UserEntity user = seedUser();
        saveMembership(user, inScopeProject);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> scopedFixtureService.update(outScopeEntityId, modelWithLabel("nope-" + runId)));
    }

    @Test
    @DisplayName("update: non-ADMIN with no memberships denied (2.5)")
    void updateNonAdminWithoutMembershipsDenied() {
        UserEntity user = seedUser();
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> scopedFixtureService.update(inScopeEntityId, modelWithLabel("nope-" + runId)));
    }

    @Test
    @DisplayName("update: unauthenticated caller denied (2.3)")
    void updateUnauthenticatedDenied() {
        SecurityContextHolder.clearContext();

        assertDenied(() -> scopedFixtureService.update(inScopeEntityId, modelWithLabel("nope-" + runId)));
    }

    // ----------------------------------------------------------------------
    // deleteById (Requirements 5.2, 5.3, 5.4)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("deleteById: ADMIN caller allowed regardless of ownership (5.4)")
    void deleteByIdAdminAllowedRegardlessOfOwnership() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        scopedFixtureService.deleteById(outScopeEntityId);

        assertThat(scopedFixtureDao.findById(outScopeEntityId)).isEmpty();
    }

    @Test
    @DisplayName("deleteById: non-ADMIN allowed on an in-scope entity (5.3)")
    void deleteByIdNonAdminAllowedInScope() {
        UserEntity user = seedUser();
        saveMembership(user, inScopeProject);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        scopedFixtureService.deleteById(inScopeEntityId);

        assertThat(scopedFixtureDao.findById(inScopeEntityId)).isEmpty();
    }

    @Test
    @DisplayName("deleteById: non-ADMIN denied on an out-of-scope entity with 404 error.entity.not.found (5.2)")
    void deleteByIdNonAdminDeniedOutOfScope() {
        UserEntity user = seedUser();
        saveMembership(user, inScopeProject);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> scopedFixtureService.deleteById(outScopeEntityId));
        // Row still present: the check throws before the delete reaches the DAO.
        assertThat(scopedFixtureDao.findById(outScopeEntityId)).isPresent();
    }

    @Test
    @DisplayName("deleteById: non-ADMIN with no memberships denied (2.5)")
    void deleteByIdNonAdminWithoutMembershipsDenied() {
        UserEntity user = seedUser();
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> scopedFixtureService.deleteById(inScopeEntityId));
        assertThat(scopedFixtureDao.findById(inScopeEntityId)).isPresent();
    }

    @Test
    @DisplayName("deleteById: unauthenticated caller denied (2.3)")
    void deleteByIdUnauthenticatedDenied() {
        SecurityContextHolder.clearContext();

        assertDenied(() -> scopedFixtureService.deleteById(inScopeEntityId));
        assertThat(scopedFixtureDao.findById(inScopeEntityId)).isPresent();
    }

    // --- Assertion helpers ---

    /** Asserts the action throws the Access_Denied_Outcome: 404 with message code error.entity.not.found. */
    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        ForemenApiException ex = catchThrowableOfType(action, ForemenApiException.class);
        assertThat(ex).as("expected a ForemenApiException to be thrown").isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
    }

    // --- Seeding / auth helpers (unique per run via runId) ---

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private RoleEntity seedRole() {
        RoleEntity r = new RoleEntity();
        r.setCode("ACTION-ENFORCE-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("Action Enforcement Test User");
        u.setEmail("action-enforce+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private Long seedFixture(String label, Long projectId) {
        ScopedFixtureEntity entity = new ScopedFixtureEntity();
        entity.setLabel(label);
        entity.setProjectId(projectId);
        return scopedFixtureDao.save(entity).getId();
    }

    private void saveMembership(UserEntity user, Long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        projectMemberDao.save(member);
    }

    private ScopedFixtureServiceExtendedModel modelWithLabel(String label) {
        ScopedFixtureServiceExtendedModel model = new ScopedFixtureServiceExtendedModel();
        model.setLabel(label);
        return model;
    }
}
