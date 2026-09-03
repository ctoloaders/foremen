package com.foremen.scoping;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.ProjectAccessCache;
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

/**
 * FOR-03-04a read-filter NON-REGRESSION integration test.
 *
 * <p>FOR-03-04a re-declares {@code ProjectScopedService} to {@code extend AdminService} and adds
 * single-entity action validation, but it MUST leave the FOR-03-04 list-level read filter
 * ({@code addRequiredQuery()} -> {@code find} / {@code findExtended} / {@code getCount}) completely
 * unchanged. This test re-asserts that guarantee end to end against the same throwaway
 * {@link ScopedFixtureService} / {@link ScopedFixtureEntity} fixture, after the refactor in which
 * the fixture now {@code implements ProjectScopedService} only (no separate
 * {@code ReadOnlyAdminService}/{@code AdminService}) and relies on the inherited default
 * {@code addRequiredQuery()}.
 *
 * <p>Coverage (Requirements 7.1, 7.2, 8.4):
 * <ul>
 *     <li>ADMIN caller reads unfiltered across {@code find} / {@code findExtended} / {@code getCount};</li>
 *     <li>non-ADMIN caller WITH memberships sees only in-membership rows on all three;</li>
 *     <li>non-ADMIN caller with NO memberships sees no rows on all three.</li>
 * </ul>
 *
 * <p>This is a deliberately independent test class (task 5.3): it does not modify any shared
 * fixture, DAO, mapper, profile, or the sibling {@link ScopedFixtureFilteringIntegrationTest}. Its
 * container/profile setup and seeding helpers are inlined here. Every row is seeded under a unique
 * {@code run-id} suffix and the {@link ProjectAccessCache} entry is invalidated per test, so the
 * suite re-runs without manual clean-up.
 *
 * <p>Validates: Requirements 7.1, 7.2, 8.4
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ReadFilterNonRegressionIntegrationTest {

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

    /** Distinct project ids used across every scenario. */
    private long projectA;
    private long projectB;
    private long projectC;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();

        // Three distinct project ids, unique per run so cached membership sets never collide.
        long base = System.nanoTime();
        projectA = base + 1;
        projectB = base + 2;
        projectC = base + 3;

        // Six fixture rows: two in each of the three projects, filtered on the single-segment
        // "projectId" path (the fixture service's default).
        scopedFixtureService.setProjectIdPath("projectId");
        seedFixture("A1-" + runId, projectA);
        seedFixture("A2-" + runId, projectA);
        seedFixture("B1-" + runId, projectB);
        seedFixture("B2-" + runId, projectB);
        seedFixture("C1-" + runId, projectC);
        seedFixture("C2-" + runId, projectC);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Read filter unchanged: ADMIN reads every row unfiltered via find/findExtended/getCount (7.1, 7.2, 8.4)")
    void adminReadsUnfilteredAfterRefactor() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        // ADMIN bypass -> addRequiredQuery() returns null -> no project filter combined in.
        Page<ScopedFixtureServiceModel> found = scopedFixtureService.find(PAGE);
        assertThat(labelsOf(found)).hasSize(6);
        assertThat(scopedFixtureService.findExtended(PAGE).getTotalElements()).isEqualTo(6);
        assertThat(scopedFixtureService.getCount(null)).isEqualTo(6);
    }

    @Test
    @DisplayName("Read filter unchanged: non-ADMIN with memberships sees only in-membership rows (7.1, 7.2, 8.4)")
    void nonAdminWithMembershipsSeesOnlyAllowedRowsAfterRefactor() {
        UserEntity user = seedUser();
        // The user belongs to projects A and B, but NOT C.
        saveMembership(user, projectA);
        saveMembership(user, projectB);
        projectAccessCache.invalidate(user.getId());

        authenticate(user.getId(), "ROLE_FOREMAN");

        // find and findExtended return only the four A/B rows; the two C rows are filtered out.
        Page<ScopedFixtureServiceModel> found = scopedFixtureService.find(PAGE);
        assertThat(labelsOf(found)).hasSize(4);
        assertThat(found.getContent())
                .allSatisfy(m -> assertThat(m.getProjectId()).isIn(projectA, projectB));

        assertThat(scopedFixtureService.findExtended(PAGE).getTotalElements()).isEqualTo(4);
        assertThat(scopedFixtureService.getCount(null)).isEqualTo(4);
    }

    @Test
    @DisplayName("Read filter unchanged: non-ADMIN with no memberships sees no rows (7.1, 7.2, 8.4)")
    void nonAdminWithoutMembershipsSeesNoRowsAfterRefactor() {
        UserEntity user = seedUser();
        // No project_members rows for this user -> allowed set is empty -> match-nothing.
        projectAccessCache.invalidate(user.getId());

        authenticate(user.getId(), "ROLE_FOREMAN");

        assertThat(scopedFixtureService.find(PAGE).getTotalElements()).isZero();
        assertThat(scopedFixtureService.findExtended(PAGE).getTotalElements()).isZero();
        assertThat(scopedFixtureService.getCount(null)).isZero();
    }

    // --- Seeding / auth helpers (inlined; unique per run via runId) ---

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private RoleEntity seedRole() {
        RoleEntity r = new RoleEntity();
        r.setCode("READ-FILTER-NONREG-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("Read Filter Non-Regression Test User");
        u.setEmail("read-filter-nonreg+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private void seedFixture(String label, Long projectId) {
        ScopedFixtureEntity entity = new ScopedFixtureEntity();
        entity.setLabel(label);
        entity.setProjectId(projectId);
        scopedFixtureDao.save(entity);
    }

    private void saveMembership(UserEntity user, Long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        projectMemberDao.save(member);
    }

    private List<String> labelsOf(Page<ScopedFixtureServiceModel> page) {
        return page.getContent().stream().map(ScopedFixtureServiceModel::getLabel).toList();
    }
}
