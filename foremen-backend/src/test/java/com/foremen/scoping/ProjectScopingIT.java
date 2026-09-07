package com.foremen.scoping;

import com.foremen.controller.model.ProjectListDto;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectService;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * End-to-end integration test proving the FOR-04-13 {@code Project} entity behaves as the
 * project-scoped anchor ({@code ProjectService.getProjectIdPath() == "id"}), exercising the real
 * {@link ProjectService} against a Testcontainers PostgreSQL database.
 *
 * <p>Because the project IS the anchor, the membership filter and by-id membership assertion resolve
 * against the {@code projects} table's own id: a non-ADMIN caller sees exactly the projects for which
 * a {@code project_members} row joins them to the project, ADMIN bypasses the filter and sees every
 * project, and a non-member by-id read/update/delete is denied with the {@code Access_Denied_Outcome}
 * (404 {@code error.entity.not.found}, deliberately indistinguishable from a missing row).
 *
 * <p>Coverage:
 * <ul>
 *     <li>non-ADMIN LIST is membership-filtered — {@link ProjectService#findProjected} returns only
 *         the caller's member projects and excludes the rest (Requirement 7.5, 4.2);</li>
 *     <li>ADMIN LIST bypass — {@code findProjected} returns all seeded projects (Requirement 4.3);</li>
 *     <li>non-member by-id read/update/delete are denied with 404 {@code error.entity.not.found}
 *         (Requirement 4.5), and the update/delete denials persist no change.</li>
 * </ul>
 *
 * <p>Mirrors the container/profile setup of {@link ProjectActionEnforcementIntegrationTest} and
 * {@link ScopedFixtureFilteringIntegrationTest}: Hibernate {@code create-drop} builds the schema
 * under {@code @ActiveProfiles("integration-test")}, real {@code project_members} rows are seeded so
 * the {@link ProjectAccessCache} self-loads genuine memberships, and the principal name is the
 * seeded user's numeric id (mirroring how the JWT layer populates the {@code SecurityContext}). Every
 * row is seeded under a unique {@code run-id} suffix and the {@link ProjectAccessCache} entry is
 * invalidated per test, so the suite re-runs without manual clean-up.
 *
 * <p>Validates: Requirements 7.5, 4.3, 4.5
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectScopingIT {

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

    private static final Pageable PAGE = PageRequest.of(0, 100);

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectDao projectDao;

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

    /** Three distinct projects: the non-ADMIN caller is a member of A and B, but NOT C. */
    private Long projectA;
    private Long projectB;
    private Long projectC;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();

        projectA = seedProject("A-" + runId);
        projectB = seedProject("B-" + runId);
        projectC = seedProject("C-" + runId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------------
    // LIST filtering (Requirements 7.5, 4.2, 4.3)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("LIST: non-ADMIN caller sees exactly the projects they are a member of (7.5)")
    void listNonAdminReturnsOnlyMemberProjects() {
        UserEntity user = seedUser();
        // Member of A and B only.
        saveMembership(user, projectA);
        saveMembership(user, projectB);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        Page<ProjectListDto> page = projectService.findProjected(PAGE, null);

        assertThat(page.getContent())
                .extracting(ProjectListDto::id)
                .containsExactlyInAnyOrder(projectA, projectB)
                .doesNotContain(projectC);
    }

    @Test
    @DisplayName("LIST: non-ADMIN caller with no memberships sees an empty collection (7.5, 4.4)")
    void listNonAdminWithoutMembershipsReturnsEmpty() {
        UserEntity user = seedUser();
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        Page<ProjectListDto> page = projectService.findProjected(PAGE, null);

        assertThat(page.getContent())
                .extracting(ProjectListDto::id)
                .doesNotContain(projectA, projectB, projectC);
    }

    @Test
    @DisplayName("LIST: ADMIN caller bypasses membership filtering and sees all projects (4.3)")
    void listAdminSeesAllProjects() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        Page<ProjectListDto> page = projectService.findProjected(PAGE, null);

        assertThat(page.getContent())
                .extracting(ProjectListDto::id)
                .contains(projectA, projectB, projectC);
    }

    // ----------------------------------------------------------------------
    // By-id denial for non-members (Requirement 4.5)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("READ by id: non-member denied with 404 error.entity.not.found (4.5)")
    void readByIdNonMemberDenied() {
        UserEntity user = seedUser();
        saveMembership(user, projectA);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        // Member of A can read A.
        assertThat(projectService.findByIdProjected(projectA).id()).isEqualTo(projectA);

        // Non-member of C is denied — and the denial reveals nothing about C.
        assertDenied(() -> projectService.findByIdProjected(projectC));
    }

    @Test
    @DisplayName("UPDATE by id: non-member denied with 404 and no change persisted (4.5)")
    void updateByIdNonMemberDenied() {
        UserEntity user = seedUser();
        saveMembership(user, projectA);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> projectService.update(projectC, modelWithName("hacked-" + runId)));

        // The out-of-scope project's name is unchanged (the check throws before any write).
        assertThat(projectDao.findById(projectC).orElseThrow().getName()).isEqualTo("C-" + runId);
    }

    @Test
    @DisplayName("DELETE by id: non-member denied with 404 and row still present (4.5)")
    void deleteByIdNonMemberDenied() {
        UserEntity user = seedUser();
        saveMembership(user, projectA);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> projectService.deleteById(projectC));

        // The out-of-scope project still exists (the check throws before the delete reaches the DAO).
        assertThat(projectDao.findById(projectC)).isPresent();
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
        r.setCode("PROJECT-SCOPING-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("Project Scoping Test User");
        u.setEmail("project-scoping+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private Long seedProject(String name) {
        ProjectEntity project = new ProjectEntity();
        project.setName(name);
        project.setStatus(ProjectStatus.DRAFT);
        return projectDao.save(project).getId();
    }

    private void saveMembership(UserEntity user, Long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        projectMemberDao.save(member);
    }

    private ProjectServiceExtendedModel modelWithName(String name) {
        return new ProjectServiceExtendedModel(
                null, name, null, null, null, null, null, null, null, null, ProjectStatus.ACTIVE);
    }
}
