package com.foremen.scoping;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FOR-03-04a task 5.2 — no-partial-mutation and multi-id abort integration tests for the
 * action-level project-ownership check on {@link com.foremen.service.ProjectScopedService}.
 *
 * <p>These tests prove that the ownership decision ({@code assertProjectAccess}) runs BEFORE any
 * write reaches the DAO, so a denied single-entity action leaves the database untouched, and that a
 * multi-id action ({@code updateAll} / {@code deleteAll} / {@code softDelete}) aborts the WHOLE
 * operation as soon as any one targeted id is out of the caller's scope — no row in the batch is
 * mutated, not even the in-scope ones.
 *
 * <p>Exercised against the throwaway {@link ScopedFixtureService} / {@link ScopedFixtureEntity}
 * fixture (no real project-scoped entity exists yet — those arrive in FOR-06), wiring
 * {@code allowedProjectIds(userId)} to the real {@link ProjectAccessCache} with genuine
 * {@code project_members} rows so the decision reads its allowed set exactly as production would.
 * The principal name is the seeded user's numeric id, mirroring how the JWT layer populates the
 * {@code SecurityContext}.
 *
 * <p>Coverage:
 * <ul>
 *     <li>denied {@code update} leaves the target row's fields unchanged (Requirements 4.2, 4.5, 8.3);</li>
 *     <li>denied {@code deleteById} leaves the row present (Requirements 5.2, 5.5, 8.3);</li>
 *     <li>{@code updateAll} aborts the whole batch when any one id is out of scope — no row updated
 *         (Requirements 4.5, 8.3);</li>
 *     <li>{@code deleteAll} aborts the whole batch when any one id is out of scope — no row deleted
 *         (Requirements 5.5, 8.3);</li>
 *     <li>{@code softDelete} aborts the whole batch when any one id is out of scope — no row touched
 *         (Requirements 5.5, 8.3).</li>
 * </ul>
 *
 * <p>Each row is seeded under a unique {@code run-id} suffix and the {@link ProjectAccessCache}
 * entry is invalidated per test, so the suite re-runs without manual clean-up.
 *
 * <p>Validates: Requirements 4.2, 4.5, 5.2, 5.5, 8.3
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectActionNoPartialMutationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
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

    /** Distinct project ids: A is in the caller's scope, B is out of scope. */
    private long projectInScope;
    private long projectOutOfScope;

    /** The non-ADMIN caller whose only membership is {@code projectInScope}. */
    private UserEntity user;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();
        scopedFixtureService.setProjectIdPath("projectId");

        long base = System.nanoTime();
        projectInScope = base + 1;
        projectOutOfScope = base + 2;

        user = seedUser();
        saveMembership(user, projectInScope);
        projectAccessCache.invalidate(user.getId());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Denied update leaves the target row's fields unchanged — no write reaches the DAO (4.2, 8.3)")
    void deniedUpdateLeavesRowUnchanged() {
        ScopedFixtureEntity target = seedFixture("orig-" + runId, projectOutOfScope);
        authenticate(user.getId(), "ROLE_FOREMAN");

        ScopedFixtureServiceExtendedModel change = new ScopedFixtureServiceExtendedModel();
        change.setLabel("mutated-" + runId);
        change.setProjectId(projectOutOfScope);

        assertThatThrownBy(() -> scopedFixtureService.update(target.getId(), change))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> {
                    ForemenApiException ex = (ForemenApiException) t;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
                });

        // The row must be exactly as seeded — nothing was persisted.
        ScopedFixtureEntity reloaded = reload(target.getId());
        assertThat(reloaded.getLabel()).isEqualTo("orig-" + runId);
        assertThat(reloaded.getProjectId()).isEqualTo(projectOutOfScope);
    }

    @Test
    @DisplayName("Denied deleteById leaves the row present — no delete reaches the DAO (5.2, 8.3)")
    void deniedDeleteLeavesRowPresent() {
        ScopedFixtureEntity target = seedFixture("keep-" + runId, projectOutOfScope);
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertThatThrownBy(() -> scopedFixtureService.deleteById(target.getId()))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> {
                    ForemenApiException ex = (ForemenApiException) t;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
                });

        // The row is still present.
        assertThat(scopedFixtureDao.findById(target.getId())).isPresent();
    }

    @Test
    @DisplayName("updateAll aborts the WHOLE batch when any one id is out of scope — no row updated (4.5, 8.3)")
    void updateAllAbortsWholeBatchOnFirstDeny() {
        ScopedFixtureEntity inScope = seedFixture("in-" + runId, projectInScope);
        ScopedFixtureEntity outOfScope = seedFixture("out-" + runId, projectOutOfScope);
        authenticate(user.getId(), "ROLE_FOREMAN");

        ScopedFixtureServiceExtendedModel change = new ScopedFixtureServiceExtendedModel();
        change.setLabel("batch-mutated-" + runId);

        assertThatThrownBy(() ->
                scopedFixtureService.updateAll(List.of(inScope.getId(), outOfScope.getId()), change))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> assertThat(((ForemenApiException) t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // NEITHER row was updated — the in-scope row must not be partially mutated.
        assertThat(reload(inScope.getId()).getLabel()).isEqualTo("in-" + runId);
        assertThat(reload(outOfScope.getId()).getLabel()).isEqualTo("out-" + runId);
    }

    @Test
    @DisplayName("deleteAll aborts the WHOLE batch when any one id is out of scope — no row deleted (5.5, 8.3)")
    void deleteAllAbortsWholeBatchOnFirstDeny() {
        ScopedFixtureEntity inScope = seedFixture("del-in-" + runId, projectInScope);
        ScopedFixtureEntity outOfScope = seedFixture("del-out-" + runId, projectOutOfScope);
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertThatThrownBy(() ->
                scopedFixtureService.deleteAll(List.of(inScope.getId(), outOfScope.getId())))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> assertThat(((ForemenApiException) t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // BOTH rows are still present — the in-scope row must not be deleted.
        assertThat(scopedFixtureDao.findById(inScope.getId())).isPresent();
        assertThat(scopedFixtureDao.findById(outOfScope.getId())).isPresent();
    }

    @Test
    @DisplayName("softDelete aborts the WHOLE batch when any one id is out of scope — no row touched (5.5, 8.3)")
    void softDeleteAbortsWholeBatchOnFirstDeny() {
        ScopedFixtureEntity inScope = seedFixture("soft-in-" + runId, projectInScope);
        ScopedFixtureEntity outOfScope = seedFixture("soft-out-" + runId, projectOutOfScope);
        authenticate(user.getId(), "ROLE_FOREMAN");

        // The batch is denied on the out-of-scope id; assertProjectAccess throws before any
        // CriteriaUpdate is issued, so neither row is mutated (and the fixture's absence of a
        // soft-delete column is never reached).
        assertThatThrownBy(() ->
                scopedFixtureService.softDelete("deleted", Set.of(inScope.getId(), outOfScope.getId())))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> assertThat(((ForemenApiException) t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // Both rows remain exactly as seeded.
        assertThat(reload(inScope.getId()).getLabel()).isEqualTo("soft-in-" + runId);
        assertThat(reload(outOfScope.getId()).getLabel()).isEqualTo("soft-out-" + runId);
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
        r.setCode("NPM-FIXTURE-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("No-Partial-Mutation Test User");
        u.setEmail("npm-fixture+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private ScopedFixtureEntity seedFixture(String label, Long projectId) {
        ScopedFixtureEntity entity = new ScopedFixtureEntity();
        entity.setLabel(label);
        entity.setProjectId(projectId);
        return scopedFixtureDao.save(entity);
    }

    private void saveMembership(UserEntity user, Long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        projectMemberDao.save(member);
    }

    private ScopedFixtureEntity reload(Long id) {
        return scopedFixtureDao.findById(id).orElseThrow();
    }
}
