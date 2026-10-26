package com.foremen.controller.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.PermissionCache;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-07-01 task 5.2 — integration tests for {@link com.foremen.controller.CrossProjectScheduleController}
 * ({@code GET /api/company-schedule}), the single read-only endpoint behind the cross-project
 * staffing projection.
 *
 * <p>Boots the full application context with the real Spring Security filter chain, the
 * {@code PermissionInterceptor}, and the real {@code ForemenPermissionEvaluator} against a
 * Testcontainers PostgreSQL instance, mirroring {@link ProjectScheduleControllerAbacIntegrationTest}.
 * Requests hit the endpoint through {@link MockMvc} so the class-level
 * {@code @PermissionResource("COMPANY_SCHEDULE")} + handler {@code @PermissionOperation("READ")}
 * enforcement runs exactly as for a real HTTP request, and the project-scope resolution
 * ({@link ProjectAccessCache}) runs against real {@code project_members} rows.
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the {@code COMPANY_SCHEDULE} ABAC matrix that changeset
 * {@code 187-seed-company-schedule-resource.xml} would normally seed is NOT present at runtime. This
 * test therefore seeds the matrix directly via the JPA layer per test (mirroring the sibling ABAC
 * test): the {@code COMPANY_SCHEDULE} resource, the READ operation, and the per-role READ grants of
 * changeset 187 — ADMIN + MANAGER READ only (no CREATE/UPDATE/DELETE). FOREMAN is deliberately left
 * without the grant so it drives the 403 case; ADMIN bypasses the matrix at the evaluator level.
 *
 * <p>Covered cases (Requirements 7.1, 7.8, 9.4, 11.1, 11.2, 11.4):
 * <ul>
 *   <li>a MANAGER with the {@code COMPANY_SCHEDULE} READ grant who is a project member gets 200 with
 *       a well-formed {@code CompanyScheduleView} (rows + legend);</li>
 *   <li>a {@code from > to} Date_Window is rejected with HTTP 400
 *       {@code error.company.schedule.window.invalid} and changes no data (R7.8);</li>
 *   <li>a FOREMAN caller without the {@code COMPANY_SCHEDULE} READ grant gets 403 (R9.4);</li>
 *   <li>the same filter set replayed twice yields a byte-identical projection
 *       (Persisted_View_State deterministic replay, R11.2).</li>
 * </ul>
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id combined with unique emails and codes), the permission / access caches are invalidated for
 * the seeded role codes / per-run user ids before each test, and {@code @Transactional} rolls the
 * whole database back after each test, so the suite is safely re-runnable without manual cleanup.
 *
 * <p>Validates: Requirements 7.1, 7.8, 9.4, 11.1, 11.2, 11.4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class CrossProjectScheduleControllerIntegrationTest {

    private static final String BASE_PATH = "/api/company-schedule";

    private static final String COMPANY_SCHEDULE = "COMPANY_SCHEDULE";

    private static final String ADMIN = "ADMIN";
    private static final String MANAGER = "MANAGER";
    private static final String FOREMAN = "FOREMAN";
    private static final String WORKER = "WORKER";

    /** The invalid Date_Window error code the controller raises for {@code from > to} (R7.8). */
    private static final String WINDOW_INVALID_CODE = "error.company.schedule.window.invalid";

    /** Per-run unique id source keeping every scenario's data collision-free and re-runnable. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column (mirrors the sibling tests).
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // ScheduleProperties is a @Validated @NotNull @DecimalMin(exclusive 0) property; pin it so the
        // bean is unambiguously valid under this profile regardless of environment overrides.
        registry.add("foremen.schedule.daily-output-per-worker", () -> "2000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private ProjectDao projectDao;

    @Autowired
    private ProjectMemberDao projectMemberDao;

    @Autowired
    private ProjectScheduleDao projectScheduleDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private PermissionCache permissionCache;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    @PersistenceContext
    private EntityManager entityManager;

    /** The anchored project every scenario reads; carries a worker bar and a foreman span. */
    private ProjectEntity project;

    /** The WORKER user owning the project's single worker-category bar. */
    private long workerUserId;

    /** The FOREMAN user leading the project (source of the Foreman_Span_Bar). */
    private long foremanUserId;

    /** The work category of the worker bar. */
    private WorkCategoryEntity category;

    @BeforeEach
    void seedMatrixAndProject() {
        // Liquibase is disabled under the integration-test profile, so seed the COMPANY_SCHEDULE ABAC
        // matrix directly via JPA, per test, rolled back by @Transactional (mirrors the sibling ABAC
        // test). Changeset 187 grants READ on COMPANY_SCHEDULE to ADMIN + MANAGER only.
        ResourceEntity companySchedule = createResource(COMPANY_SCHEDULE);
        OperationEntity read = createOperation("READ");

        ensureRole(ADMIN);          // ADMIN bypasses the matrix; the role must exist to mint a token.
        grantRole(MANAGER, companySchedule, read);
        ensureRole(FOREMAN);        // FOREMAN gets NO COMPANY_SCHEDULE grant → 403 case.

        // Invalidate the long-lived permission cache for every seeded role so this test's freshly
        // seeded (and later rolled-back) matrix is read, never a sibling test's cached PermissionSet.
        for (String code : new String[] {ADMIN, MANAGER, FOREMAN, WORKER}) {
            permissionCache.invalidate(code);
        }

        // An anchored project (both dates present) with one ACTIVE WORKER owning a worker-category bar
        // and one ACTIVE FOREMAN leading it — so an authorized read yields a well-formed projection
        // with both a worker row and a foreman-span row, plus a project legend entry.
        project = persistAnchoredProjectWithWorkerBarAndForeman();
        entityManager.flush();
    }

    // =======================================================================
    // 200 — authorized caller gets a well-formed CompanyScheduleView (R7.1)
    // =======================================================================

    @Nested
    @DisplayName("authorized caller gets 200 with a well-formed projection (R7.1)")
    class AuthorizedRead {

        @Test
        @DisplayName("a MANAGER member with COMPANY_SCHEDULE READ gets 200 with rows + legend")
        void managerMember_getsProjection() throws Exception {
            long userId = persistMemberWithRole(MANAGER);

            mockMvc.perform(get(BASE_PATH).header("Authorization", bearer(MANAGER, userId)))
                    .andExpect(status().isOk())
                    // Two contributing users (the WORKER and the FOREMAN) → two member rows.
                    .andExpect(jsonPath("$.rows").isArray())
                    .andExpect(jsonPath("$.rows.length()").value(2))
                    // The single project appears once in the legend with a color index.
                    .andExpect(jsonPath("$.legend.length()").value(1))
                    .andExpect(jsonPath("$.legend[0].projectId").value(project.getId()))
                    .andExpect(jsonPath("$.legend[0].projectName").value(project.getName()))
                    .andExpect(jsonPath("$.legend[0].colorIndex").value(0));
        }

        @Test
        @DisplayName("an ADMIN (matrix bypass) gets 200 with a well-formed projection")
        void admin_getsProjection() throws Exception {
            // An ADMIN bypasses both the ABAC matrix and the project-scope cache (sees all projects).
            long adminUserId = persistUserWithRole(ADMIN);

            mockMvc.perform(get(BASE_PATH).header("Authorization", bearer(ADMIN, adminUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows").isArray())
                    .andExpect(jsonPath("$.legend.length()").value(1))
                    .andExpect(jsonPath("$.legend[0].projectId").value(project.getId()));
        }
    }

    // =======================================================================
    // 400 — from > to Date_Window is rejected before any read (R7.8)
    // =======================================================================

    @Nested
    @DisplayName("an inverted Date_Window (from > to) is rejected with 400 and changes no data (R7.8)")
    class InvalidWindow {

        @Test
        @DisplayName("from > to -> 400 error.company.schedule.window.invalid, no data change")
        void fromAfterTo_returns400() throws Exception {
            long userId = persistMemberWithRole(MANAGER);

            long projectsBefore = projectDao.count();
            long schedulesBefore = projectScheduleDao.count();
            Long versionBefore = scheduleVersion();

            mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", bearer(MANAGER, userId))
                            .param("from", "2026-05-10")
                            .param("to", "2026-05-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            // The guard runs before any read; no schedule row is created/deleted and no version bumps.
            entityManager.flush();
            entityManager.clear();
            org.assertj.core.api.Assertions.assertThat(projectDao.count())
                    .as("the rejected request must not change the project count (R7.8)")
                    .isEqualTo(projectsBefore);
            org.assertj.core.api.Assertions.assertThat(projectScheduleDao.count())
                    .as("the rejected request must not change the schedule count (R7.8)")
                    .isEqualTo(schedulesBefore);
            org.assertj.core.api.Assertions.assertThat(scheduleVersion())
                    .as("the rejected request must not bump the schedule version (R7.8)")
                    .isEqualTo(versionBefore);
        }

        @Test
        @DisplayName("from == to is a valid (single-day) window -> 200, not 400")
        void fromEqualsTo_isValid() throws Exception {
            long userId = persistMemberWithRole(MANAGER);

            mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", bearer(MANAGER, userId))
                            .param("from", "2026-05-01")
                            .param("to", "2026-05-01"))
                    .andExpect(status().isOk());
        }
    }

    // =======================================================================
    // 403 — caller without the COMPANY_SCHEDULE READ grant (R9.4)
    // =======================================================================

    @Nested
    @DisplayName("a caller without the COMPANY_SCHEDULE READ grant gets 403 (R9.4)")
    class Forbidden {

        @Test
        @DisplayName("a FOREMAN member (no COMPANY_SCHEDULE grant) -> 403")
        void foremanWithoutGrant_returns403() throws Exception {
            // FOREMAN is a project member (passes any scope gate) but holds no COMPANY_SCHEDULE grant,
            // so the PermissionInterceptor rejects READ with 403 before any business logic.
            long userId = persistMemberWithRole(FOREMAN);

            mockMvc.perform(get(BASE_PATH).header("Authorization", bearer(FOREMAN, userId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // =======================================================================
    // Deterministic replay — identical filter set twice => identical body (R11.2)
    // =======================================================================

    @Nested
    @DisplayName("replaying the same filter set twice yields an identical projection (R11.1, R11.2, R11.4)")
    class DeterministicReplay {

        @Test
        @DisplayName("the same caller + same roles/projectIds/memberIds/from/to returns a byte-identical body")
        void sameFilterSetReplayed_returnsIdenticalProjection() throws Exception {
            long userId = persistMemberWithRole(MANAGER);
            String token = bearer(MANAGER, userId);

            // A fully-specified Persisted_View_State: every filter dimension the read contract exposes
            // (roles / projectIds / memberIds / from / to) — exactly the dimensions a saved Gantt view
            // replays (R11.1, R11.4). The window spans the whole project so both bars survive.
            String first = mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", token)
                            .param("roles", WORKER, FOREMAN)
                            .param("projectIds", String.valueOf(project.getId()))
                            .param("memberIds", String.valueOf(workerUserId), String.valueOf(foremanUserId))
                            .param("from", "2026-01-01")
                            .param("to", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Clear the persistence context so the replay reloads from the DB rather than the 1st-level
            // cache — a true re-execution of the read, as a replayed saved view would be.
            entityManager.flush();
            entityManager.clear();

            String second = mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", token)
                            .param("roles", WORKER, FOREMAN)
                            .param("projectIds", String.valueOf(project.getId()))
                            .param("memberIds", String.valueOf(workerUserId), String.valueOf(foremanUserId))
                            .param("from", "2026-01-01")
                            .param("to", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Byte-identical: the projection is a pure deterministic function of the filters (R11.2).
            org.assertj.core.api.Assertions.assertThat(second)
                    .as("a replayed Persisted_View_State yields a byte-identical projection (R11.2)")
                    .isEqualTo(first);

            // And the body is a well-formed, non-empty projection (the two seeded members survived).
            JsonNode body = MAPPER.readTree(second);
            org.assertj.core.api.Assertions.assertThat(body.get("rows")).hasSize(2);
            org.assertj.core.api.Assertions.assertThat(body.get("legend")).hasSize(1);
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static long nextId() {
        return RUN_ID.incrementAndGet();
    }

    /** The current optimistic-lock version of the test project's schedule row (for the no-change check). */
    private Long scheduleVersion() {
        return projectScheduleDao.findByProjectId(project.getId())
                .map(ProjectScheduleEntity::getVersion)
                .orElse(null);
    }

    /** Mints a JWT whose principal (sub) is the given numeric user id and authority is ROLE_<code>. */
    private String bearer(String roleCode, long userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }

    /** Persists a user with the given system role, returning its id. */
    private long persistUserWithRole(String roleCode) {
        RoleEntity role = ensureRole(roleCode);
        long id = nextId();
        UserEntity user = new UserEntity();
        user.setName("User " + roleCode + " " + id);
        user.setEmail(roleCode.toLowerCase() + "+" + id + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        user = userDao.save(user);
        entityManager.flush();
        return user.getId();
    }

    /**
     * Persists a user with the given system role AND makes that user a member of the test project, so
     * a non-ADMIN caller's project-scope resolution ({@link ProjectAccessCache}) admits the project.
     * The membership's project-role is WORKER (unrelated to the system role driving the ABAC matrix).
     * The access cache for the fresh user id is invalidated so the gate reloads the just-created
     * membership from the current transaction.
     */
    private long persistMemberWithRole(String roleCode) {
        long userId = persistUserWithRole(roleCode);
        UserEntity user = userDao.findById(userId).orElseThrow();

        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(project.getId());
        member.setProjectRole(ensureRole(WORKER));
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        projectMemberDao.save(member);
        entityManager.flush();

        projectAccessCache.invalidate(userId);
        return userId;
    }

    /**
     * Builds an anchored project (both {@code startDate} and {@code endDate} present) with one ACTIVE
     * WORKER owning a single worker-category schedule bar and one ACTIVE FOREMAN leading it. Records
     * the two user ids in {@link #workerUserId} / {@link #foremanUserId} and the category in
     * {@link #category}. An authorized read therefore returns a worker-category bar row and a
     * foreman-span bar row, both colored by the one project legend entry.
     */
    private ProjectEntity persistAnchoredProjectWithWorkerBarAndForeman() {
        long suffix = nextId();

        ProjectEntity p = new ProjectEntity();
        p.setName("Company Schedule Project " + suffix);
        p.setStatus(ProjectStatus.DRAFT);
        p.setStartDate(LocalDate.of(2026, 5, 1));
        p.setEndDate(LocalDate.of(2026, 6, 30));
        p = projectDao.save(p);

        category = new WorkCategoryEntity();
        category.setCode("C" + suffix);
        category.setOrderNo(1);
        category.setNameRU("Демонтаж");
        category.setNamePL("Rozbiórka");
        category.setActive(true);
        category = workCategoryDao.save(category);

        // The WORKER member owning the worker-category bar.
        UserEntity worker = new UserEntity();
        worker.setName("Worker " + suffix);
        worker.setEmail("worker+" + suffix + "@example.com");
        worker.setRole(ensureRole(WORKER));
        worker.setActive(true);
        worker.setStatus(UserStatus.ACTIVE);
        worker.setLocale(java.util.Locale.forLanguageTag("pl"));
        worker = userDao.save(worker);
        this.workerUserId = worker.getId();

        ProjectMemberEntity workerMember = new ProjectMemberEntity();
        workerMember.setUser(worker);
        workerMember.setProjectId(p.getId());
        workerMember.setProjectRole(ensureRole(WORKER));
        workerMember.setAssignmentStatus(AssignmentStatus.ACTIVE);
        workerMember = projectMemberDao.save(workerMember);

        // The FOREMAN member leading the project (source of the synthesized Foreman_Span_Bar).
        UserEntity foreman = new UserEntity();
        foreman.setName("Foreman " + suffix);
        foreman.setEmail("foreman+" + suffix + "@example.com");
        foreman.setRole(ensureRole(FOREMAN));
        foreman.setActive(true);
        foreman.setStatus(UserStatus.ACTIVE);
        foreman.setLocale(java.util.Locale.forLanguageTag("pl"));
        foreman = userDao.save(foreman);
        this.foremanUserId = foreman.getId();

        ProjectMemberEntity foremanMember = new ProjectMemberEntity();
        foremanMember.setUser(foreman);
        foremanMember.setProjectId(p.getId());
        foremanMember.setProjectRole(ensureRole(FOREMAN));
        foremanMember.setAssignmentStatus(AssignmentStatus.ACTIVE);
        projectMemberDao.save(foremanMember);

        // The schedule aggregate with a single worker-category bar for the WORKER member.
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setProject(p);
        ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
        bar.setSchedule(schedule);
        bar.setWorkCategory(category);
        bar.setProjectMember(workerMember);
        bar.setStartHalfDays(1);
        bar.setDurationHalfDays(4);
        schedule.getBars().add(bar);
        projectScheduleDao.save(schedule);

        return p;
    }

    /** Ensures a system role with the given code exists, returning it (idempotent within a test). */
    private RoleEntity ensureRole(String code) {
        return roleDao.findByCode(code).orElseGet(() -> {
            RoleEntity role = new RoleEntity();
            role.setCode(code);
            role.setNameRU("Роль " + code);
            role.setNamePL("Rola " + code);
            role.setSystem(true);
            return roleDao.save(role);
        });
    }

    /** Ensures a role exists and grants it the given operations on the resource. */
    private void grantRole(String code, ResourceEntity resource, OperationEntity... operations) {
        RoleEntity role = ensureRole(code);
        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setRole(role);
        rr.setResource(resource);
        for (OperationEntity op : operations) {
            rr.getOperations().add(op);
        }
        role.getRoleResources().add(rr);
        roleDao.save(role);
    }

    private ResourceEntity createResource(String code) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(code);
        resource.setNameRU("Ресурс " + code);
        resource.setNamePL("Zasób " + code);
        resource.setDescriptionRU("Описание " + code);
        resource.setDescriptionPL("Opis " + code);
        entityManager.persist(resource);
        return resource;
    }

    private OperationEntity createOperation(String code) {
        OperationEntity operation = new OperationEntity();
        operation.setCode(code);
        operation.setNameRU("Операция " + code);
        operation.setNamePL("Operacja " + code);
        entityManager.persist(operation);
        return operation;
    }
}
