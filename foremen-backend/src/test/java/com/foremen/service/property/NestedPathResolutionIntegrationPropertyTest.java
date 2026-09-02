package com.foremen.service.property;

import com.foremen.ForemenApplication;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.scoping.ScopedFixtureDao;
import com.foremen.scoping.ScopedFixtureEntity;
import com.foremen.scoping.ScopedFixtureProject;
import com.foremen.scoping.ScopedFixtureProjectDao;
import com.foremen.scoping.ScopedFixtureService;
import com.foremen.scoping.ScopedFixtureServiceModel;
import com.foremen.service.ProjectAccessCache;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration property test for nested project-id path resolution through the real
 * {@link ScopedFixtureService} / {@link com.foremen.service.ProjectScopedService} against a real
 * PostgreSQL database (Testcontainers).
 *
 * <p>Covers the design property assigned to task 12.2:</p>
 * <ul>
 *   <li><b>Property 2: Nested path resolution filters by the resolved project id</b>
 *       &mdash; Validates Requirements 6.2, 7.1, 7.2, 7.3.</li>
 * </ul>
 *
 * <p>For every generated try, fixture rows are seeded under a unique {@code run-id} — some rows
 * carry an <em>in-set</em> project id and some an <em>out-of-set</em> project id — reachable through
 * either the single-segment path {@code "projectId"} (Requirement 7.1) or the dotted association
 * path {@code "project.id"} (Requirement 7.2). A non-ADMIN caller with a generated non-empty allowed
 * set then reads through {@link ScopedFixtureService#find} /
 * {@link ScopedFixtureService#getCount} (both flowing through {@code buildFinalSpecification}), and
 * the test asserts the read returns EXACTLY the rows whose resolved project id is in the allowed set:
 * no out-of-set row survives and every in-set row is retained (Requirements 6.2, 7.3).</p>
 *
 * <p>Unlike the pure-decision Property 1 test (which inspects the produced {@code Specification}
 * against mocked criteria objects), this test proves the filter end to end at the SQL level: the
 * path is resolved by Hibernate into a real column reference / join and the {@code IN} predicate is
 * executed by PostgreSQL.</p>
 *
 * <h2>Bootstrapping</h2>
 * <p>Because {@code jqwik-spring} is not on the classpath, the Spring context cannot be injected
 * into {@code @Property} methods by an extension. Instead the full application context is booted
 * ONCE per container (jqwik {@link BeforeContainer}) via {@link SpringApplicationBuilder}, wired to
 * a single Testcontainers PostgreSQL instance and the {@code integration-test} profile (Hibernate
 * {@code create-drop} builds the schema, including the test-only fixture tables), mirroring the
 * {@code @SpringBootTest} + Testcontainers convention of {@code ProjectMemberDaoIntegrationTest}.
 * Beans are pulled out of the context and reused across tries; each try seeds and then removes its
 * own rows (unique {@code run-id}) so the suite re-runs without manual clean-up.</p>
 */
// Feature: FOR-03-04-project-ownership, Property 2: Nested path resolution filters by the resolved project id
class NestedPathResolutionIntegrationPropertyTest {

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext context;

    private static ScopedFixtureService scopedFixtureService;
    private static ScopedFixtureDao scopedFixtureDao;
    private static ScopedFixtureProjectDao scopedFixtureProjectDao;
    private static ProjectMemberDao projectMemberDao;
    private static ProjectAccessCache projectAccessCache;
    private static UserDao userDao;
    private static RoleDao roleDao;
    private static TransactionTemplate transactionTemplate;

    /** Monotonic run-id source so every seeded row across every try is unique. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    @BeforeContainer
    static void startContext() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("foremen_test")
                .withUsername("test")
                .withPassword("test")
                // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON
                // converter's text value into the jsonb display_preferences column, matching the
                // deployed app and ProjectMemberDaoIntegrationTest.
                .withUrlParam("stringtype", "unspecified");
        postgres.start();

        context = new SpringApplicationBuilder(ForemenApplication.class)
                .profiles("integration-test")
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        // No web server needed for a service-level read test.
                        "spring.main.web-application-type=none")
                .run();

        scopedFixtureService = context.getBean(ScopedFixtureService.class);
        scopedFixtureDao = context.getBean(ScopedFixtureDao.class);
        scopedFixtureProjectDao = context.getBean(ScopedFixtureProjectDao.class);
        projectMemberDao = context.getBean(ProjectMemberDao.class);
        projectAccessCache = context.getBean(ProjectAccessCache.class);
        userDao = context.getBean(UserDao.class);
        roleDao = context.getBean(RoleDao.class);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Single-segment path {@code "projectId"} (Requirement 7.1): rows carry a plain
     * {@code project_id} column. A non-ADMIN caller with a non-empty allowed set reads exactly the
     * rows whose {@code projectId} is in the allowed set — every in-set row retained, no out-of-set
     * row leaked (Requirements 6.2, 7.1, 7.3).
     *
     * <p><b>Validates: Requirements 6.2, 7.1, 7.3</b></p>
     */
    @Property(tries = 100)
    void singleSegmentPath_returnsExactlyInSetRows(@ForAll("scenarios") Scenario scenario) {
        long run = RUN_ID.incrementAndGet();
        List<Long> createdEntityIds = new ArrayList<>();
        UserEntity user = seedUser(run);
        try {
            Set<Long> expectedIds = new HashSet<>();
            // Seed in-set rows: each must survive the filter.
            for (long projectId : scenario.allowed()) {
                for (int i = 0; i < scenario.rowsPerProject(); i++) {
                    Long id = seedPlainRow(run, projectId);
                    createdEntityIds.add(id);
                    expectedIds.add(id);
                }
            }
            // Seed out-of-set rows: none may survive the filter.
            for (long projectId : scenario.forbidden()) {
                for (int i = 0; i < scenario.rowsPerProject(); i++) {
                    createdEntityIds.add(seedPlainRow(run, projectId));
                }
            }

            wireAllowedProjects(user.getId(), scenario.allowed());
            authenticateNonAdmin(user.getId());
            scopedFixtureService.setProjectIdPath("projectId");

            assertFilteredReadMatches(run, expectedIds, scenario);
        } finally {
            cleanUp(user, createdEntityIds, List.of());
        }
    }

    /**
     * Dotted association path {@code "project.id"} (Requirement 7.2): rows reach their project id
     * through a {@code @ManyToOne} join to {@link ScopedFixtureProject}, whose own id is the
     * project id. A non-ADMIN caller with a non-empty allowed set reads exactly the rows whose
     * joined {@code project.id} is in the allowed set (Requirements 6.2, 7.2, 7.3).
     *
     * <p>Because the project id here is the auto-generated {@link ScopedFixtureProject} id (not a
     * caller-chosen value), the allowed set is derived from the ids of the seeded in-set projects,
     * and separate out-of-set projects are seeded whose ids are excluded from the allowed set.</p>
     *
     * <p><b>Validates: Requirements 6.2, 7.2, 7.3</b></p>
     */
    @Property(tries = 100)
    void dottedPath_returnsExactlyInSetRows(@ForAll("dottedScenarios") DottedScenario scenario) {
        long run = RUN_ID.incrementAndGet();
        List<Long> createdEntityIds = new ArrayList<>();
        List<Long> createdProjectIds = new ArrayList<>();
        UserEntity user = seedUser(run);
        try {
            Set<Long> allowedProjectIds = new HashSet<>();
            Set<Long> expectedIds = new HashSet<>();

            // In-set projects: their ids form the allowed set, and every fixture row pointing at
            // them must survive the filter.
            for (int p = 0; p < scenario.inSetProjects(); p++) {
                Long projectId = seedProject(run);
                createdProjectIds.add(projectId);
                allowedProjectIds.add(projectId);
                for (int i = 0; i < scenario.rowsPerProject(); i++) {
                    Long id = seedAssociatedRow(run, projectId);
                    createdEntityIds.add(id);
                    expectedIds.add(id);
                }
            }
            // Out-of-set projects: excluded from the allowed set; none of their rows may survive.
            for (int p = 0; p < scenario.outOfSetProjects(); p++) {
                Long projectId = seedProject(run);
                createdProjectIds.add(projectId);
                for (int i = 0; i < scenario.rowsPerProject(); i++) {
                    createdEntityIds.add(seedAssociatedRow(run, projectId));
                }
            }

            wireAllowedProjects(user.getId(), allowedProjectIds);
            authenticateNonAdmin(user.getId());
            scopedFixtureService.setProjectIdPath("project.id");

            List<ScopedFixtureServiceModel> rows = readRunRows(run);
            Set<Long> returnedIds = rows.stream()
                    .map(ScopedFixtureServiceModel::getId)
                    .collect(Collectors.toSet());

            assertThat(returnedIds)
                    .as("dotted path 'project.id': the read must return exactly the rows joined to "
                            + "an in-set project — every in-set row retained, no out-of-set row leaked")
                    .isEqualTo(expectedIds);

            long count = countRunRows(run);
            assertThat(count)
                    .as("getCount must apply the same project filter as find (Requirement 6.2 via buildFinalSpecification)")
                    .isEqualTo(expectedIds.size());
        } finally {
            cleanUp(user, createdEntityIds, createdProjectIds);
        }
    }

    // --- Assertions shared by the single-segment scenario ---

    private void assertFilteredReadMatches(long run, Set<Long> expectedIds, Scenario scenario) {
        List<ScopedFixtureServiceModel> rows = readRunRows(run);
        Set<Long> returnedIds = rows.stream()
                .map(ScopedFixtureServiceModel::getId)
                .collect(Collectors.toSet());

        assertThat(returnedIds)
                .as("single-segment path 'projectId': the read must return exactly the rows whose "
                        + "projectId is in the allowed set %s — every in-set row retained, no out-of-set "
                        + "row (from %s) leaked", scenario.allowed(), scenario.forbidden())
                .isEqualTo(expectedIds);

        long count = countRunRows(run);
        assertThat(count)
                .as("getCount must apply the same project filter as find (Requirement 6.2 via buildFinalSpecification)")
                .isEqualTo(expectedIds.size());
    }

    // --- Reads scoped to this run so parallel/other rows never interfere ---

    /**
     * Reads all fixture rows created by this run through the project-scoped {@code find}, narrowing
     * to the run's label with the user query so a try only ever sees its own seeded rows. The
     * project filter is combined via {@code buildFinalSpecification}; the label predicate is the
     * "user query" half.
     */
    private List<ScopedFixtureServiceModel> readRunRows(long run) {
        Page<ScopedFixtureServiceModel> page =
                scopedFixtureService.find(PageRequest.of(0, 500), "label==" + runLabel(run));
        List<ScopedFixtureServiceModel> result = new ArrayList<>();
        for (ScopedFixtureServiceModel model : page.getContent()) {
            if (model != null) {
                result.add(model);
            }
        }
        return result;
    }

    private long countRunRows(long run) {
        return scopedFixtureService.getCount("label==" + runLabel(run));
    }

    // --- Seeding helpers (unique per run) ---

    private UserEntity seedUser(long run) {
        return transactionTemplate.execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("SCOPED-FIX-ROLE-" + run + "-" + System.nanoTime());
            role.setNameRU("Роль");
            role.setNamePL("Rola");
            role.setSystem(false);
            RoleEntity savedRole = roleDao.save(role);

            UserEntity user = new UserEntity();
            user.setName("Scoped Fixture User");
            user.setEmail("scoped-fixture+" + run + "-" + System.nanoTime() + "@example.com");
            user.setRole(savedRole);
            return userDao.save(user);
        });
    }

    private Long seedPlainRow(long run, long projectId) {
        return transactionTemplate.execute(status -> {
            ScopedFixtureEntity entity = new ScopedFixtureEntity();
            entity.setLabel(runLabel(run));
            entity.setProjectId(projectId);
            return scopedFixtureDao.save(entity).getId();
        });
    }

    private Long seedProject(long run) {
        return transactionTemplate.execute(status -> {
            ScopedFixtureProject project = new ScopedFixtureProject();
            project.setTitle("scoped-fixture-project-" + run + "-" + System.nanoTime());
            return scopedFixtureProjectDao.save(project).getId();
        });
    }

    private Long seedAssociatedRow(long run, long projectId) {
        return transactionTemplate.execute(status -> {
            ScopedFixtureProject project = scopedFixtureProjectDao.findById(projectId).orElseThrow();
            ScopedFixtureEntity entity = new ScopedFixtureEntity();
            entity.setLabel(runLabel(run));
            entity.setProject(project);
            return scopedFixtureDao.save(entity).getId();
        });
    }

    /**
     * Persists the user's memberships so the production {@link ProjectAccessCache} self-loads the
     * expected allowed set, then invalidates any stale cache entry so the very next
     * {@code get(userId)} reflects the freshly-seeded rows.
     */
    private void wireAllowedProjects(Long userId, Set<Long> projectIds) {
        transactionTemplate.executeWithoutResult(status -> {
            UserEntity user = userDao.findById(userId).orElseThrow();
            RoleEntity role = user.getRole();
            for (Long projectId : projectIds) {
                ProjectMemberEntity member = new ProjectMemberEntity();
                member.setUser(user);
                member.setProjectId(projectId);
                member.setProjectRole(role);
                projectMemberDao.save(member);
            }
        });
        projectAccessCache.invalidate(userId);
    }

    // --- Clean-up (teardown) so the suite re-runs without manual DB cleanup ---

    private void cleanUp(UserEntity user, List<Long> entityIds, List<Long> projectIds) {
        SecurityContextHolder.clearContext();
        projectAccessCache.invalidate(user.getId());
        transactionTemplate.executeWithoutResult(status -> {
            for (Long id : entityIds) {
                scopedFixtureDao.findById(id).ifPresent(scopedFixtureDao::delete);
            }
        });
        // Remove memberships and the user/role, plus any association projects, in their own tx.
        transactionTemplate.executeWithoutResult(status -> {
            Set<Long> memberProjectIds = projectMemberDao.findDistinctProjectIdsByUserId(user.getId());
            for (Long projectId : memberProjectIds) {
                projectMemberDao.findByUserIdAndProjectId(user.getId(), projectId)
                        .ifPresent(projectMemberDao::delete);
            }
            for (Long projectId : projectIds) {
                scopedFixtureProjectDao.findById(projectId).ifPresent(scopedFixtureProjectDao::delete);
            }
            userDao.findById(user.getId()).ifPresent(u -> {
                RoleEntity role = u.getRole();
                userDao.delete(u);
                if (role != null) {
                    roleDao.findById(role.getId()).ifPresent(roleDao::delete);
                }
            });
        });
    }

    private String runLabel(long run) {
        return "scoped-fixture-run-" + run;
    }

    // --- SecurityContext helper ---

    private void authenticateNonAdmin(Long userId) {
        SecurityContextHolder.clearContext();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_MANAGER"));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // --- Generated scenarios ---

    /**
     * A single-segment scenario: a non-empty set of allowed project ids, a disjoint set of
     * forbidden (out-of-set) project ids, and how many rows to seed per project id.
     */
    record Scenario(Set<Long> allowed, Set<Long> forbidden, int rowsPerProject) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Set<Long>> allowed = Arbitraries.longs().between(1L, 5_000L)
                .set().ofMinSize(1).ofMaxSize(4);
        Arbitrary<Set<Long>> forbidden = Arbitraries.longs().between(5_001L, 10_000L)
                .set().ofMinSize(0).ofMaxSize(4);
        Arbitrary<Integer> rowsPerProject = Arbitraries.integers().between(1, 3);
        return Combinators.combine(allowed, forbidden, rowsPerProject).as(Scenario::new);
    }

    /**
     * A dotted-path scenario: how many in-set projects (their ids form the allowed set), how many
     * out-of-set projects, and how many fixture rows point at each project. At least one in-set
     * project keeps the allowed set non-empty (Requirement 6.2's non-empty precondition).
     */
    record DottedScenario(int inSetProjects, int outOfSetProjects, int rowsPerProject) {
    }

    @Provide
    Arbitrary<DottedScenario> dottedScenarios() {
        Arbitrary<Integer> inSet = Arbitraries.integers().between(1, 3);
        Arbitrary<Integer> outOfSet = Arbitraries.integers().between(0, 3);
        Arbitrary<Integer> rowsPerProject = Arbitraries.integers().between(1, 3);
        return Combinators.combine(inSet, outOfSet, rowsPerProject).as(DottedScenario::new);
    }
}
