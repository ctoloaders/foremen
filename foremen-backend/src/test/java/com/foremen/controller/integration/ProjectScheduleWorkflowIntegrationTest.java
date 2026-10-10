package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.PermissionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-10 task 7.4 — end-to-end integration test for the Schedule_API
 * ({@code /api/project-schedules}) exercising the full planning-Gantt lifecycle against a real
 * Testcontainers PostgreSQL instance through {@link MockMvc}.
 *
 * <p>Where the sibling {@link ProjectScheduleControllerAbacIntegrationTest} proves the per-role ×
 * per-endpoint ABAC matrix, this test proves the <em>business workflow</em> of
 * {@link com.foremen.controller.ProjectScheduleController} +
 * {@link com.foremen.service.ProjectScheduleService} end to end: the full application context (the
 * real security filter chain, the {@code PermissionInterceptor}, the {@code ForemenPermissionEvaluator},
 * JPA, and the pure schedule calculators) runs over a one-project fixture with estimate lines in
 * three work categories and two ACTIVE WORKER crew members, and the test drives every write / read
 * path a user would:
 *
 * <ol>
 *   <li><b>Read (unscheduled)</b> — the first GET returns 3 rows, all unscheduled, version 0, and
 *       creates no {@code project_schedules} row (R4.4, R5.6).</li>
 *   <li><b>Auto-create</b> — POST {@code /auto-create} lays out 3 contiguous bars from day 1 with no
 *       gaps. This is the <em>first</em> write, so it inserts the {@code project_schedules} row at
 *       version 0 (an insert, not an increment) (R8.1, R8.3).</li>
 *   <li><b>Drag-like save</b> — PUT {@code /bars} moves one bar; being the second write it increments
 *       the version to 1 (R7.1).</li>
 *   <li><b>Stale-version 409</b> — a save carrying an old version (0, now stale) is rejected with 409
 *       {@code error.schedule.conflict} and changes nothing (R11.3).</li>
 *   <li><b>Concurrent first write (simulated)</b> — a write that still carries version 0 after the
 *       schedule already advanced loses with 409 {@code error.schedule.conflict}; this is exactly how
 *       a losing concurrent first write surfaces (its stale version 0 or the {@code project_id} unique
 *       violation both map to the one conflict code) (R6.4, R11.3).</li>
 *   <li><b>Readiness transitions</b> — BLOCKED (nothing scheduled) → DONE (every row scheduled) →
 *       PARTIAL (after a brand-new estimate category adds a fourth, unscheduled row) (R12.1).</li>
 *   <li><b>Project → ACTIVE locks writes</b> — moving the project to {@code ACTIVE} makes a
 *       subsequent save and auto-create return 409 {@code error.schedule.locked} (R10.2).</li>
 * </ol>
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the {@code WORK_SCHEDULE} ABAC matrix changeset 153 would seed
 * is NOT present. The caller here is an ADMIN, which bypasses both the permission matrix (at the
 * {@code ForemenPermissionEvaluator} level) and the project-scope gate, so no WORK_SCHEDULE grants
 * or project membership need to be seeded for the ADMIN to reach every endpoint; the test seeds only
 * the ADMIN role (to mint a ROLE_ADMIN token) and the business fixture (project + estimate + lines +
 * three categories + two ACTIVE WORKER members). ESTIMATE READ is granted to ADMIN implicitly by the
 * bypass, so the money fields (currency, categoryValue) are present in the view.
 *
 * <p><b>No test-level {@code @Transactional}.</b> Unlike the sibling ABAC test, this workflow test
 * deliberately does NOT wrap the method in a single rolled-back transaction. The planning Gantt's
 * optimistic locking (the {@code @Version} on {@code project_schedules} and the
 * {@code OPTIMISTIC_FORCE_INCREMENT} the service applies per write) only advances a <em>committed</em>
 * version: each MockMvc request must run and commit in its own transaction, exactly as a real HTTP
 * request does, so a later read observes the incremented version and a stale-version write is
 * genuinely rejected. A shared, never-committed test transaction would keep the single persistence
 * context at version 0 forever and could not exercise the version bump or the 409 conflict. The
 * schema is created fresh ({@code ddl-auto=create-drop}) and dropped at context shutdown.
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id combined with unique emails / codes), so no row collides with a prior run and no manual
 * teardown is needed; the permission and project-access caches are invalidated for the seeded ADMIN
 * before each test. The suite is safely re-runnable.
 *
 * <p>Validates: Requirements 4.4, 6.4, 8.1, 10.2, 11.3, 12.1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectScheduleWorkflowIntegrationTest {

    private static final String BASE_PATH = "/api/project-schedules";
    private static final String BARS_PATH = BASE_PATH + "/bars";
    private static final String AUTO_CREATE_PATH = BASE_PATH + "/auto-create";
    private static final String READINESS_PATH = BASE_PATH + "/readiness";

    private static final String ADMIN = "ADMIN";
    private static final String WORKER = "WORKER";

    // Localized (PL base bundle) texts of the two 409 workflow errors asserted below.
    private static final String PL_CONFLICT =
            "Harmonogram został zmieniony przez innego użytkownika. Odśwież dane i spróbuj ponownie.";
    private static final String PL_LOCKED =
            "Harmonogram jest zablokowany w bieżącym statusie projektu i nie można go modyfikować.";

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
        // The schedule rate is a @Validated @NotNull @DecimalMin(exclusive 0) property; pin it so the
        // auto-create durations below are deterministic (rate 2000, crew 2 => 4000 net/day).
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
    private EstimateDao estimateDao;

    @Autowired
    private EstimateLineDao estimateLineDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private PermissionCache permissionCache;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    /** The DRAFT project under test (Schedule_Editable_Status until moved to ACTIVE). */
    private ProjectEntity project;

    /** The ADMIN caller id and its bearer token (reused across a single test method). */
    private long adminUserId;
    private String adminToken;

    /** The estimate of the project, so a fourth category can be added mid-test (PARTIAL transition). */
    private EstimateEntity estimate;

    /** A reusable measurement unit / work-item currency for the fixture. */
    private MeasurementUnitEntity unit;

    /** The three category ids of the three estimate categories, in orderNo order. */
    private Long categoryId1;
    private Long categoryId2;
    private Long categoryId3;

    @BeforeEach
    void seedProjectEstimateAndCrew() {
        adminUserId = persistUser(ADMIN);
        adminToken = bearer(ADMIN, adminUserId);
        // ADMIN bypasses the matrix and scoping, but invalidate the caches so no sibling test's
        // cached state leaks into this per-run ADMIN id.
        permissionCache.invalidate(ADMIN);
        projectAccessCache.invalidate(adminUserId);

        project = persistProjectWithThreeCategoriesAndTwoWorkers();
    }

    /**
     * Drives the whole planning-Gantt lifecycle in one method so each step observes the state the
     * previous step committed (version bumps, readiness transitions, the lifecycle lock): read →
     * auto-create → drag-like save → stale 409 → simulated concurrent-first-write 409 → readiness
     * BLOCKED → DONE → PARTIAL → project ACTIVE locks writes.
     */
    @Test
    @DisplayName("full Schedule_API lifecycle: read, auto-create, save, conflicts, readiness, lifecycle lock")
    void scheduleLifecycle_endToEnd() throws Exception {
        // --- 1. Read (unscheduled): 3 rows, all unscheduled, version 0, no row created (R4.4, R5.6) ---
        JsonNode view = readView();
        org.assertj.core.api.Assertions.assertThat(view.get("projectId").asLong()).isEqualTo(project.getId());
        org.assertj.core.api.Assertions.assertThat(view.get("version").asLong()).isEqualTo(0L);
        org.assertj.core.api.Assertions.assertThat(view.get("crewSize").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(view.get("rows")).hasSize(3);
        for (JsonNode row : view.get("rows")) {
            org.assertj.core.api.Assertions.assertThat(row.hasNonNull("startDay"))
                    .as("every row is unscheduled on the first read").isFalse();
            org.assertj.core.api.Assertions.assertThat(row.hasNonNull("durationDays")).isFalse();
            // ADMIN is a Money_Viewer, so the net value is present on each row (R5.3).
            org.assertj.core.api.Assertions.assertThat(row.hasNonNull("categoryValue")).isTrue();
        }
        // Money fields present for the ADMIN Money_Viewer.
        org.assertj.core.api.Assertions.assertThat(view.hasNonNull("currency")).isTrue();

        // Readiness is BLOCKED when nothing is scheduled (R12.1 / D14).
        JsonNode readinessBefore = readReadiness();
        org.assertj.core.api.Assertions.assertThat(readinessBefore.get("key").asText()).isEqualTo("schedule");
        org.assertj.core.api.Assertions.assertThat(readinessBefore.get("state").asText()).isEqualTo("BLOCKED");
        org.assertj.core.api.Assertions.assertThat(readinessBefore.get("rowCount").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(readinessBefore.get("scheduledCount").asInt()).isEqualTo(0);

        // --- 2. Auto-create (first write, version 0): 3 contiguous bars from day 1 with no gaps
        // (R8.1, R8.3). The write succeeds and commits a version bump; a fresh read observes the
        // committed version. ---
        JsonNode autoView = asJson(autoCreate(adminToken, 0L).andExpect(status().isOk()));
        org.assertj.core.api.Assertions.assertThat(autoView.get("rows")).hasSize(3);

        // Each category value is 8000 net; rate 2000 × crew 2 = 4000/day => duration = ceil(8000/4000) = 2.
        // Rows are laid out contiguously in orderNo order: (1,2), (3,2), (5,2).
        int expectedStart = 1;
        for (JsonNode row : autoView.get("rows")) {
            int startDay = row.get("startDay").asInt();
            int durationDays = row.get("durationDays").asInt();
            int finishDay = row.get("finishDay").asInt();
            org.assertj.core.api.Assertions.assertThat(durationDays)
                    .as("duration = ceil(8000 / (2000*2)) = 2").isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(startDay)
                    .as("bars are contiguous from day 1 with no gaps (R8.3)").isEqualTo(expectedStart);
            org.assertj.core.api.Assertions.assertThat(finishDay).isEqualTo(startDay + durationDays - 1);
            expectedStart = finishDay + 1;
        }
        // Schedule finish = last contiguous finish = 6.
        org.assertj.core.api.Assertions.assertThat(autoView.get("finishDay").asInt()).isEqualTo(6);

        // Auto-create is the FIRST write: it INSERTS the project_schedules row at version 0 (a plain
        // insert — a forced increment adds no extra UPDATE on a brand-new row), so the committed
        // version a fresh read observes is 0, exactly as the sibling ABAC test observes a first write
        // leaving version 0. The next (updating) write is what first bumps it (R8.1).
        long versionAfterAutoCreate = readView().get("version").asLong();
        org.assertj.core.api.Assertions.assertThat(versionAfterAutoCreate)
                .as("the first write inserts the schedule at the committed version 0").isEqualTo(0L);

        // Readiness is DONE once every row has a bar (R12.1 / D14).
        JsonNode readinessDone = readReadiness();
        org.assertj.core.api.Assertions.assertThat(readinessDone.get("state").asText()).isEqualTo("DONE");
        org.assertj.core.api.Assertions.assertThat(readinessDone.get("rowCount").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(readinessDone.get("scheduledCount").asInt()).isEqualTo(3);

        // --- 3. Drag-like save: move category 1's bar to day 10, carrying the version just read.
        // The save succeeds and commits a further version bump (R7.1). ---
        String moveBody = """
                {"version": %d, "bars": [{"workCategoryId": %d, "startDay": 10, "durationDays": 3}]}
                """.formatted(versionAfterAutoCreate, categoryId1);
        saveBars(adminToken, moveBody).andExpect(status().isOk());

        JsonNode afterSave = readView();
        long versionAfterSave = afterSave.get("version").asLong();
        org.assertj.core.api.Assertions.assertThat(versionAfterSave)
                .as("the save bumps the committed version again (R7.1)").isGreaterThan(versionAfterAutoCreate);
        JsonNode movedRow = rowByCategory(afterSave, categoryId1);
        org.assertj.core.api.Assertions.assertThat(movedRow.get("startDay").asInt()).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThat(movedRow.get("durationDays").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(movedRow.get("finishDay").asInt()).isEqualTo(12);
        // The two unlisted bars are left unchanged (R7.1).
        org.assertj.core.api.Assertions.assertThat(rowByCategory(afterSave, categoryId2).get("startDay").asInt())
                .isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(rowByCategory(afterSave, categoryId3).get("startDay").asInt())
                .isEqualTo(5);

        // --- 4. Stale-version 409: a save carrying the OLD (pre-save) version is rejected (R11.3)
        // and changes nothing. ---
        String staleBody = """
                {"version": %d, "bars": [{"workCategoryId": %d, "startDay": 20, "durationDays": 1}]}
                """.formatted(versionAfterAutoCreate, categoryId2);
        saveBars(adminToken, staleBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(PL_CONFLICT));
        // The rejected save changed nothing: category 2 is still at its auto-created start day 3 and
        // the version did not move.
        JsonNode afterStale = readView();
        org.assertj.core.api.Assertions.assertThat(afterStale.get("version").asLong()).isEqualTo(versionAfterSave);
        org.assertj.core.api.Assertions.assertThat(rowByCategory(afterStale, categoryId2).get("startDay").asInt())
                .isEqualTo(3);

        // --- 5. Concurrent first write (simulated): a write that still carries version 0 after the
        // schedule already exists loses with 409 error.schedule.conflict (R6.4, R11.3). A real
        // concurrent FIRST write races on the project_id unique constraint; both the lost first
        // insert (its stale version 0) and this stale-version-0 write map to the same conflict code. ---
        String firstWriteLoserBody = """
                {"version": 0, "bars": [{"workCategoryId": %d, "startDay": 30, "durationDays": 1}]}
                """.formatted(categoryId3);
        saveBars(adminToken, firstWriteLoserBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(PL_CONFLICT));

        // --- 6. Readiness PARTIAL: add a brand-new estimate category so a fourth, unscheduled row
        // appears; three of four rows have a bar => PARTIAL (R12.1 / D14, R4.4). ---
        addFourthEstimateCategory();
        JsonNode viewFour = readView();
        org.assertj.core.api.Assertions.assertThat(viewFour.get("rows"))
                .as("the new estimate category appears as a fourth, unscheduled row (R4.4)").hasSize(4);

        JsonNode readinessPartial = readReadiness();
        org.assertj.core.api.Assertions.assertThat(readinessPartial.get("state").asText()).isEqualTo("PARTIAL");
        org.assertj.core.api.Assertions.assertThat(readinessPartial.get("rowCount").asInt()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(readinessPartial.get("scheduledCount").asInt()).isEqualTo(3);

        // --- 7. Project -> ACTIVE locks writes: both a save and an auto-create return 409
        // error.schedule.locked (R10.2). Read the current version first so the version check cannot
        // mask the lock (the lock gate runs before the version gate, R3.5). ---
        long currentVersion = readView().get("version").asLong();
        moveProjectToActive();

        String lockedSaveBody = """
                {"version": %d, "bars": [{"workCategoryId": %d, "startDay": 1, "durationDays": 1}]}
                """.formatted(currentVersion, categoryId1);
        saveBars(adminToken, lockedSaveBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(PL_LOCKED));

        autoCreate(adminToken, currentVersion)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(PL_LOCKED));

        // A read is still allowed in a locked status, and the stored bars are unchanged (R10 read-only).
        JsonNode lockedView = readView();
        org.assertj.core.api.Assertions.assertThat(lockedView.get("version").asLong()).isEqualTo(currentVersion);
        org.assertj.core.api.Assertions.assertThat(lockedView.get("editable").asBoolean())
                .as("a locked project reports the schedule as not editable").isFalse();
        org.assertj.core.api.Assertions.assertThat(rowByCategory(lockedView, categoryId1).get("startDay").asInt())
                .as("the locked save changed nothing").isEqualTo(10);
    }

    // =======================================================================
    // Endpoint helpers
    // =======================================================================

    private JsonNode readView() throws Exception {
        String body = mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", adminToken)
                        .param("projectId", projectId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private JsonNode readReadiness() throws Exception {
        String body = mockMvc.perform(get(READINESS_PATH)
                        .header("Authorization", adminToken)
                        .param("projectId", projectId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private ResultActions saveBars(String token, String body) throws Exception {
        return mockMvc.perform(put(BARS_PATH)
                .header("Authorization", token)
                .param("projectId", projectId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions autoCreate(String token, long version) throws Exception {
        return mockMvc.perform(post(AUTO_CREATE_PATH)
                .header("Authorization", token)
                .param("projectId", projectId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"version": %d}
                        """.formatted(version)));
    }

    private JsonNode asJson(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** The row of the given work category id in a Schedule_View payload. */
    private static JsonNode rowByCategory(JsonNode view, Long categoryId) {
        for (JsonNode row : view.get("rows")) {
            if (row.get("workCategoryId").asLong() == categoryId) {
                return row;
            }
        }
        throw new AssertionError("no row for work category " + categoryId);
    }

    // =======================================================================
    // Seed helpers
    // =======================================================================

    private static long nextId() {
        return RUN_ID.incrementAndGet();
    }

    private String projectId() {
        return String.valueOf(project.getId());
    }

    private String bearer(String roleCode, long userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }

    private long persistUser(String roleCode) {
        RoleEntity role = ensureRole(roleCode);
        long id = nextId();
        UserEntity user = new UserEntity();
        user.setName("User " + roleCode + " " + id);
        user.setEmail(roleCode.toLowerCase() + "+" + id + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLocale("pl");
        user = userDao.save(user);
        return user.getId();
    }

    /**
     * Builds a DRAFT project with an estimate of three work categories (one line each, net value
     * 8000 per category) and two ACTIVE WORKER crew members. Records the three category ids and the
     * estimate so a fourth category can be added mid-test for the PARTIAL readiness transition.
     */
    private ProjectEntity persistProjectWithThreeCategoriesAndTwoWorkers() {
        long suffix = nextId();

        ProjectEntity p = new ProjectEntity();
        p.setName("Schedule Workflow Project " + suffix);
        p.setStatus(ProjectStatus.DRAFT);
        p = projectDao.save(p);

        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("CUR" + suffix);
        currency.setSymbol("z");
        currency.setNameRU("Валюта");
        currency.setNamePL("Waluta");
        currency.setActive(true);
        currency = currencyDao.save(currency);

        unit = new MeasurementUnitEntity();
        unit.setCode("u" + suffix);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        unit = measurementUnitDao.save(unit);

        estimate = new EstimateEntity();
        estimate.setProject(p);
        estimate.setCurrency(currency);
        estimate = estimateDao.save(estimate);

        // Three categories (orderNo 1, 2, 3), one estimate line each at net value 8000.
        categoryId1 = persistCategoryWithLine(suffix + "-1", 1, new BigDecimal("8000.00"), 1);
        categoryId2 = persistCategoryWithLine(suffix + "-2", 2, new BigDecimal("8000.00"), 2);
        categoryId3 = persistCategoryWithLine(suffix + "-3", 3, new BigDecimal("8000.00"), 3);

        // Two ACTIVE WORKER members => Crew_Size = 2.
        persistActiveWorker(p, suffix + "-w1");
        persistActiveWorker(p, suffix + "-w2");

        return p;
    }

    /** Persists a work category + a work item + one estimate line of the given net value. */
    private Long persistCategoryWithLine(String tag, int orderNo, BigDecimal valueNet, int lineNo) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode("C" + tag);
        category.setOrderNo(orderNo);
        category.setNameRU("Категория " + tag);
        category.setNamePL("Kategoria " + tag);
        category.setActive(true);
        category = workCategoryDao.save(category);

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setWorkCategory(category);
        workItem.setUnit(unit);
        workItem.setNameRU("работа " + tag);
        workItem.setNamePL("praca " + tag);
        workItem.setCode("WI" + tag);
        workItem.setActive(true);
        workItem = workItemDao.save(workItem);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(workItem);
        line.setUnit(unit);
        line.setLineNo(lineNo);
        line.setUnitPrice(new BigDecimal("800.00"));
        line.setQuantity(new BigDecimal("10"));
        line.setValueNet(valueNet);
        estimateLineDao.save(line);

        return category.getId();
    }

    /**
     * Adds a fourth work category with one estimate line to the project's estimate so that the next
     * Schedule_View read surfaces a fourth, unscheduled row (R4.4) and readiness becomes PARTIAL.
     */
    private void addFourthEstimateCategory() {
        persistCategoryWithLine(nextId() + "-4", 4, new BigDecimal("8000.00"), 4);
    }

    private void persistActiveWorker(ProjectEntity p, String tag) {
        UserEntity crew = new UserEntity();
        crew.setName("Crew " + tag);
        crew.setEmail("crew+" + tag + "@example.com");
        crew.setRole(ensureRole(WORKER));
        crew.setActive(true);
        crew.setStatus(UserStatus.ACTIVE);
        crew.setLocale("pl");
        crew = userDao.save(crew);

        ProjectMemberEntity crewMember = new ProjectMemberEntity();
        crewMember.setUser(crew);
        crewMember.setProjectId(p.getId());
        crewMember.setProjectRole(ensureRole(WORKER));
        crewMember.setAssignmentStatus(AssignmentStatus.ACTIVE);
        projectMemberDao.save(crewMember);
    }

    /** Moves the project to ACTIVE (a Schedule_Locked_Status) so subsequent writes are rejected. */
    private void moveProjectToActive() {
        ProjectEntity reloaded = projectDao.findById(project.getId()).orElseThrow();
        reloaded.setStatus(ProjectStatus.ACTIVE);
        projectDao.save(reloaded);
    }

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
}
