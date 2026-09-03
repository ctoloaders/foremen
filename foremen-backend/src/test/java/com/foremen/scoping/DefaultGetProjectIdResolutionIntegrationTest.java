package com.foremen.scoping;

import com.foremen.service.ProjectScopedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the FOR-03-04a default {@link ProjectScopedService#getProjectId(Object)}
 * resolver, exercised against the throwaway {@link ScopedFixtureService} / {@link ScopedFixtureEntity}
 * fixture (no real project-scoped entity exists yet — those arrive in FOR-06).
 *
 * <p>The default resolver derives the owning project id from {@code getProjectIdPath()} by running a
 * Criteria query through the inherited {@code EntityManager}. This test proves, against a real
 * Postgres via Testcontainers with the Hibernate-created fixture schema:
 *
 * <ul>
 *     <li>the default resolves the correct owning project id for a single-segment path
 *         ({@code getProjectIdPath() == "projectId"}) — Requirements 1.1, 1.2;</li>
 *     <li>the default resolves the correct owning project id for a dotted/join path
 *         ({@code "project.id"}, reached through the {@code @ManyToOne} association) — Requirement 1.2;</li>
 *     <li>the default returns {@code null} for a non-existent id (no row), never throwing —
 *         Requirement 1.3;</li>
 *     <li>a fixture variant that overrides {@code getProjectId} has its bespoke resolution honored
 *         instead of the default — Requirement 1.4.</li>
 * </ul>
 *
 * <p>{@code getProjectId} is called directly (not through {@code assertProjectAccess} / a CRUD path),
 * so no authenticated caller is needed; the {@code SecurityContext} is cleared per test. Every row is
 * seeded under a unique {@code run-id} suffix and the test runs inside a rolled-back transaction, so
 * the suite re-runs without manual clean-up.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 8.6
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class DefaultGetProjectIdResolutionIntegrationTest {

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

    // OverridingScopedFixtureService extends ScopedFixtureService, so both are ScopedFixtureService
    // beans; @Resource resolves by name ("scopedFixtureService") to pick the base fixture, not the
    // overriding subclass, avoiding the by-type ambiguity.
    @Resource(name = "scopedFixtureService")
    private ScopedFixtureService scopedFixtureService;

    @Autowired
    private OverridingScopedFixtureService overridingScopedFixtureService;

    @Autowired
    private ScopedFixtureDao scopedFixtureDao;

    @Autowired
    private ScopedFixtureProjectDao scopedFixtureProjectDao;

    /** Unique run identifier so seeded rows never collide across repeated runs. */
    private String runId;

    /** Distinct project ids used across the single-segment scenarios, unique per run. */
    private long projectA;
    private long projectB;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        runId = String.valueOf(System.nanoTime());

        long base = System.nanoTime();
        projectA = base + 1;
        projectB = base + 2;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // Restore the default path so a variant test never leaks its path into the shared bean.
        scopedFixtureService.setProjectIdPath("projectId");
    }

    @Test
    @DisplayName("Default getProjectId resolves the owning project id for a single-segment path \"projectId\" (1.1, 1.2)")
    void defaultResolvesSingleSegmentPath() {
        scopedFixtureService.setProjectIdPath("projectId");

        Long idA = seedFixtureWithColumn("A-" + runId, projectA);
        Long idB = seedFixtureWithColumn("B-" + runId, projectB);

        assertThat(scopedFixtureService.getProjectId(idA)).isEqualTo(projectA);
        assertThat(scopedFixtureService.getProjectId(idB)).isEqualTo(projectB);
    }

    @Test
    @DisplayName("Default getProjectId resolves the owning project id for a dotted/join path \"project.id\" (1.2)")
    void defaultResolvesDottedJoinPath() {
        scopedFixtureService.setProjectIdPath("project.id");

        ScopedFixtureProject project = seedProject("P-" + runId);
        Long entityId = seedFixtureWithAssociation("J-" + runId, project);

        // The association target's own id is the "project id" reached via the project.id join path.
        assertThat(scopedFixtureService.getProjectId(entityId)).isEqualTo(project.getId());
    }

    @Test
    @DisplayName("Default getProjectId returns null for a non-existent id without throwing (1.3)")
    void defaultReturnsNullForNonExistentId() {
        scopedFixtureService.setProjectIdPath("projectId");

        // Seed one row so the table is non-empty, then query an id that cannot exist.
        seedFixtureWithColumn("X-" + runId, projectA);
        long nonExistentId = Long.MAX_VALUE;

        assertThat(scopedFixtureService.getProjectId(nonExistentId)).isNull();
    }

    @Test
    @DisplayName("A fixture variant overriding getProjectId has its bespoke resolution honored (1.4)")
    void overrideIsHonoredOverDefault() {
        overridingScopedFixtureService.setProjectIdPath("projectId");
        overridingScopedFixtureService.resetGetProjectIdCalled();

        Long entityId = seedFixtureWithColumn("O-" + runId, projectA);

        // Inject a canned answer that differs from the row's real projectId; the override must win.
        long cannedProjectId = projectA + 5000;
        overridingScopedFixtureService.setOverriddenProjectId(cannedProjectId);

        Long resolved = overridingScopedFixtureService.getProjectId(entityId);

        assertThat(overridingScopedFixtureService.wasGetProjectIdCalled())
                .as("the bespoke override must be invoked, not the default resolver")
                .isTrue();
        assertThat(resolved)
                .as("the override's canned value must be returned, proving the default was bypassed")
                .isEqualTo(cannedProjectId);
        assertThat(resolved).isNotEqualTo(projectA);
    }

    // --- Seeding helpers (inlined; unique per run via runId) ---

    private Long seedFixtureWithColumn(String label, Long projectId) {
        ScopedFixtureEntity entity = new ScopedFixtureEntity();
        entity.setLabel(label);
        entity.setProjectId(projectId);
        return scopedFixtureDao.save(entity).getId();
    }

    private ScopedFixtureProject seedProject(String title) {
        ScopedFixtureProject project = new ScopedFixtureProject();
        project.setTitle(title);
        return scopedFixtureProjectDao.save(project);
    }

    private Long seedFixtureWithAssociation(String label, ScopedFixtureProject project) {
        ScopedFixtureEntity entity = new ScopedFixtureEntity();
        entity.setLabel(label);
        entity.setProject(project);
        return scopedFixtureDao.save(entity).getId();
    }
}
