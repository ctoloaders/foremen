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
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-10b task 4.3 — end-to-end integration test for the <em>per-worker</em>
 * {@code POST /api/project-schedules/auto-create} path against a real Testcontainers PostgreSQL
 * instance through {@link MockMvc}. It proves the rewritten {@code autoCreate} business workflow of
 * {@link com.foremen.controller.ProjectScheduleController} +
 * {@link com.foremen.service.ProjectScheduleService}: auto-create now resolves coverage, splits each
 * covered category equally among its {@code Covering_Worker}s, lays each worker's bars on the
 * half-day grid, lays an uncovered category out as a single virtual (null-member) bar, returns an
 * {@code AutoCreateResult { schedule, alert }}, and <b>never 409s on an empty crew</b> (R4.6).
 *
 * <p>Scenarios:
 * <ol>
 *   <li><b>Multi-worker equal split</b> — category A (value 8000) is covered by two workers; with
 *       rate 2000 the category is 8 half-days, split 4/4 → each worker gets a 2.0-day bar sharing the
 *       same start (R4.1, R4.3).</li>
 *   <li><b>Uncovered alert + virtual bar</b> — category B (covered by nobody) is laid out as one
 *       {@code projectMemberId: null} bar and reported in {@code alert.uncoveredCategories} with its
 *       localized name; {@code covered} is {@code false} (R4.2, R4.5).</li>
 *   <li><b>Version + audit</b> — the first auto-create inserts the schedule (committed version 0) and
 *       writes exactly one audit row; a second auto-create bumps the committed version by exactly one
 *       and writes exactly one more audit row (R4.7).</li>
 *   <li><b>Empty crew no-fail</b> — a project whose team has no ACTIVE WORKER returns 200 with
 *       {@code alert.crewEmpty = true}, every category uncovered + a virtual bar, and no 409
 *       (R4.6).</li>
 *   <li><b>Horizon reject</b> — a category whose workload overruns the 3650-day horizon is rejected
 *       409 {@code error.schedule.too.long}, changing nothing (R4.4).</li>
 *   <li><b>Rows empty 409</b> — a project whose estimate has no lines is rejected 409
 *       {@code error.schedule.rows.empty} (R4.7).</li>
 * </ol>
 *
 * <p>The {@code integration-test} profile builds the schema from JPA DDL ({@code create-drop}); the
 * ADMIN caller bypasses the ABAC matrix and project-scope gate, so no WORK_SCHEDULE grants need to
 * be seeded. The test is not {@code @Transactional}: each MockMvc request must commit in its own
 * transaction so the optimistic {@code @Version} actually advances between requests (see
 * {@link ProjectSchedulePerWorkerSaveBarsIntegrationTest} for the rationale).
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id), so no row collides with a prior run and no manual teardown is needed.
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 9.2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectSchedulePerWorkerAutoCreateIntegrationTest {

    private static final String BASE_PATH = "/api/project-schedules";
    private static final String AUTO_CREATE_PATH = BASE_PATH + "/auto-create";

    private static final String ADMIN = "ADMIN";
    private static final String WORKER = "WORKER";

    /** Localized (PL base bundle) prefix of the horizon-overrun 409 asserted below. */
    private static final String PL_TOO_LONG_PREFIX = "Harmonogram przekro";
    /** Localized (PL base bundle) prefix of the rows-empty 409 asserted below. */
    private static final String PL_ROWS_EMPTY_PREFIX = "Nie można utworzyć harmonogramu: kosztorys";

    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        // rate 2000: category value 8000 => duration = ceil(8000/2000) = 4 days = 8 half-days.
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
    private AuditLogDao auditLogDao;
    @Autowired
    private PermissionCache permissionCache;
    @Autowired
    private ProjectAccessCache projectAccessCache;

    private long adminUserId;
    private String adminToken;

    /** Reusable per-fixture currency/unit/estimate holders. */
    private CurrencyEntity currency;
    private MeasurementUnitEntity unit;

    @BeforeEach
    void seed() {
        adminUserId = persistUser(ADMIN);
        adminToken = bearer(ADMIN, adminUserId);
        permissionCache.invalidate(ADMIN);
        projectAccessCache.invalidate(adminUserId);
    }

    // =======================================================================
    // Scenario 1-3: multi-worker split, uncovered alert, version + audit
    // =======================================================================

    @Test
    @DisplayName("per-worker auto-create: multi-worker equal split, uncovered alert, one version bump + one audit row")
    void autoCreate_split_uncoveredAlert_versionAndAudit() throws Exception {
        // Fixture: category A (value 8000) covered by two workers; category B (value 8000) uncovered.
        ProjectEntity project = newProject("AutoCreate Split");
        EstimateEntity estimate = newEstimate(project);
        WorkCategoryEntity catA = persistCategoryWithLine(estimate, "A", 1, new BigDecimal("8000.00"));
        WorkCategoryEntity catB = persistCategoryWithLine(estimate, "B", 2, new BigDecimal("8000.00"));
        Long w1 = persistActiveWorker(project, "w1", catA);
        Long w2 = persistActiveWorker(project, "w2", catA);

        long auditBefore = scheduleAuditCount();

        // --- Auto-create (first write): returns AutoCreateResult { schedule, alert }. ---
        JsonNode result = asJson(autoCreate(project, 0L).andExpect(status().isOk()));
        JsonNode view = result.get("schedule");
        JsonNode alert = result.get("alert");

        // Multi-worker equal split (R4.1): category A has one bar per covering worker, each 2.0 days
        // (8 half-days / 2 workers = 4 half-days = 2.0 days), sharing the same start (R4.3).
        JsonNode rowA = rowByCategory(view, catA.getId());
        assertThat(rowA.get("covered").asBoolean()).isTrue();
        assertThat(rowA.get("bars")).hasSize(2);
        JsonNode bar1 = barByMember(rowA, w1);
        JsonNode bar2 = barByMember(rowA, w2);
        assertThat(bar1.get("durationDays").asDouble()).isEqualTo(2.0);
        assertThat(bar2.get("durationDays").asDouble()).isEqualTo(2.0);
        assertThat(bar1.get("startDay").asDouble())
                .as("both covering workers share the category's provisional start (R4.1)")
                .isEqualTo(bar2.get("startDay").asDouble());
        assertThat(bar1.get("startDay").asDouble()).isEqualTo(0.5); // half-day 1 => day 0.5

        // Uncovered category B (R4.2): one virtual null-member bar, covered = false.
        JsonNode rowB = rowByCategory(view, catB.getId());
        assertThat(rowB.get("covered").asBoolean()).isFalse();
        assertThat(rowB.get("bars")).hasSize(1);
        assertThat(rowB.get("bars").get(0).hasNonNull("projectMemberId"))
                .as("the uncovered category's bar has a null member (D-uncovered)").isFalse();

        // Alert (R4.5): crew not empty, exactly category B listed with its localized name.
        assertThat(alert.get("crewEmpty").asBoolean()).isFalse();
        assertThat(alert.get("uncoveredCategories")).hasSize(1);
        JsonNode uncovered = alert.get("uncoveredCategories").get(0);
        assertThat(uncovered.get("workCategoryId").asLong()).isEqualTo(catB.getId());
        assertThat(uncovered.get("name").asText())
                .as("uncovered ref carries the localized category name (ru->pl->code, R4.5)")
                .isEqualTo(catB.getNamePL());

        // One audit row + committed version 0 after the FIRST write (an insert, no extra increment).
        assertThat(scheduleAuditCount()).isEqualTo(auditBefore + 1);
        assertThat(readView(project).get("version").asLong()).isEqualTo(0L);

        // --- Second auto-create: one version bump + one more audit row (R4.7). ---
        long versionBefore = readView(project).get("version").asLong();
        long auditBeforeSecond = scheduleAuditCount();
        autoCreate(project, versionBefore).andExpect(status().isOk());
        assertThat(readView(project).get("version").asLong())
                .as("a second auto-create bumps the committed version exactly once (R4.7)")
                .isEqualTo(versionBefore + 1);
        assertThat(scheduleAuditCount())
                .as("exactly one more audit row per committed auto-create (R4.7)")
                .isEqualTo(auditBeforeSecond + 1);
    }

    // =======================================================================
    // Scenario 4: empty crew does NOT fail (R4.6)
    // =======================================================================

    @Test
    @DisplayName("per-worker auto-create: empty crew returns 200 with crewEmpty=true and all categories uncovered")
    void autoCreate_emptyCrew_noFail() throws Exception {
        ProjectEntity project = newProject("AutoCreate EmptyCrew");
        EstimateEntity estimate = newEstimate(project);
        WorkCategoryEntity catA = persistCategoryWithLine(estimate, "A", 1, new BigDecimal("8000.00"));
        WorkCategoryEntity catB = persistCategoryWithLine(estimate, "B", 2, new BigDecimal("8000.00"));
        // No ACTIVE WORKER members at all.

        JsonNode result = asJson(autoCreate(project, 0L)
                .andExpect(status().isOk())); // R4.6: NO 409 on an empty crew
        JsonNode view = result.get("schedule");
        JsonNode alert = result.get("alert");

        assertThat(view.get("crewSize").asInt()).isZero();
        assertThat(alert.get("crewEmpty").asBoolean())
                .as("an empty crew is reported via the alert, not a 409 (R4.6)").isTrue();
        assertThat(alert.get("uncoveredCategories")).hasSize(2);

        // Every category is laid out as a single virtual (null-member) bar, uncovered.
        for (Long categoryId : List.of(catA.getId(), catB.getId())) {
            JsonNode row = rowByCategory(view, categoryId);
            assertThat(row.get("covered").asBoolean()).isFalse();
            assertThat(row.get("bars")).hasSize(1);
            assertThat(row.get("bars").get(0).hasNonNull("projectMemberId")).isFalse();
        }
    }

    // =======================================================================
    // Scenario 5: horizon reject (R4.4)
    // =======================================================================

    @Test
    @DisplayName("per-worker auto-create: a category that overruns the 3650-day horizon is rejected 409")
    void autoCreate_horizonReject() throws Exception {
        ProjectEntity project = newProject("AutoCreate Horizon");
        EstimateEntity estimate = newEstimate(project);
        // value huge => duration = ceil(value / 2000) grossly exceeds 3650 days => half-day overrun.
        WorkCategoryEntity cat = persistCategoryWithLine(
                estimate, "H", 1, new BigDecimal("100000000.00"));
        persistActiveWorker(project, "w1", cat);

        long auditBefore = scheduleAuditCount();
        autoCreate(project, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith(PL_TOO_LONG_PREFIX)));

        // A rejected auto-create changes nothing: no schedule row, no audit row.
        assertThat(readView(project).get("version").asLong())
                .as("a horizon-rejected auto-create inserts no schedule").isEqualTo(0L);
        assertThat(scheduleAuditCount())
                .as("a horizon-rejected auto-create writes no audit row").isEqualTo(auditBefore);
    }

    // =======================================================================
    // Scenario 6: rows empty 409 (R4.7)
    // =======================================================================

    @Test
    @DisplayName("per-worker auto-create: a project with no estimate lines is rejected 409 rows.empty")
    void autoCreate_rowsEmpty() throws Exception {
        ProjectEntity project = newProject("AutoCreate RowsEmpty");
        newEstimate(project); // an estimate but no lines => no Schedule_Rows

        autoCreate(project, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith(PL_ROWS_EMPTY_PREFIX)));
    }

    // =======================================================================
    // Endpoint helpers
    // =======================================================================

    private JsonNode readView(ProjectEntity project) throws Exception {
        String body = mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", adminToken)
                        .param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private ResultActions autoCreate(ProjectEntity project, long version) throws Exception {
        return mockMvc.perform(post(AUTO_CREATE_PATH)
                .header("Authorization", adminToken)
                .param("projectId", String.valueOf(project.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"version": %d}
                        """.formatted(version)));
    }

    private JsonNode asJson(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode rowByCategory(JsonNode view, Long categoryId) {
        for (JsonNode row : view.get("rows")) {
            if (row.get("workCategoryId").asLong() == categoryId) {
                return row;
            }
        }
        throw new AssertionError("no row for work category " + categoryId);
    }

    private static JsonNode barByMember(JsonNode row, Long memberId) {
        for (JsonNode bar : row.get("bars")) {
            if (bar.hasNonNull("projectMemberId") && bar.get("projectMemberId").asLong() == memberId) {
                return bar;
            }
        }
        throw new AssertionError("no bar for member " + memberId + " in row " + row);
    }

    private long scheduleAuditCount() {
        long count = 0;
        for (AuditLogEntity log : auditLogDao.findAll()) {
            if ("ProjectScheduleEntity".equals(log.getEntityClass())) {
                count++;
            }
        }
        return count;
    }

    // =======================================================================
    // Seed helpers
    // =======================================================================

    private static long nextId() {
        return RUN_ID.incrementAndGet();
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

    private ProjectEntity newProject(String name) {
        long suffix = nextId();
        ProjectEntity p = new ProjectEntity();
        p.setName(name + " " + suffix);
        p.setStatus(ProjectStatus.DRAFT);
        return projectDao.save(p);
    }

    private EstimateEntity newEstimate(ProjectEntity project) {
        long suffix = nextId();
        currency = new CurrencyEntity();
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

        EstimateEntity estimate = new EstimateEntity();
        estimate.setProject(project);
        estimate.setCurrency(currency);
        return estimateDao.save(estimate);
    }

    private WorkCategoryEntity persistCategoryWithLine(
            EstimateEntity estimate, String tag, int orderNo, BigDecimal valueNet) {
        long suffix = nextId();
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode("C" + tag + suffix);
        category.setOrderNo(orderNo);
        category.setNameRU("Категория " + tag + suffix);
        category.setNamePL("Kategoria " + tag + suffix);
        category.setActive(true);
        category = workCategoryDao.save(category);

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setWorkCategory(category);
        workItem.setUnit(unit);
        workItem.setNameRU("работа " + tag + suffix);
        workItem.setNamePL("praca " + tag + suffix);
        workItem.setCode("WI" + tag + suffix);
        workItem.setActive(true);
        workItem = workItemDao.save(workItem);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(workItem);
        line.setUnit(unit);
        line.setLineNo(orderNo);
        line.setUnitPrice(new BigDecimal("800.00"));
        line.setQuantity(new BigDecimal("10"));
        line.setValueNet(valueNet);
        estimateLineDao.save(line);

        return category;
    }

    /** Persists an ACTIVE WORKER member covering the given category, returning its member id. */
    private Long persistActiveWorker(ProjectEntity project, String tag, WorkCategoryEntity covered) {
        long suffix = nextId();
        UserEntity crew = new UserEntity();
        crew.setName("Crew " + tag + suffix);
        crew.setEmail("crew+" + tag + suffix + "@example.com");
        crew.setRole(ensureRole(WORKER));
        crew.setActive(true);
        crew.setStatus(UserStatus.ACTIVE);
        crew.setLocale("pl");
        crew = userDao.save(crew);

        ProjectMemberEntity crewMember = new ProjectMemberEntity();
        crewMember.setUser(crew);
        crewMember.setProjectId(project.getId());
        crewMember.setProjectRole(ensureRole(WORKER));
        crewMember.setAssignmentStatus(AssignmentStatus.ACTIVE);
        crewMember.setWorkCategories(new java.util.ArrayList<>(List.of(covered)));
        crewMember = projectMemberDao.save(crewMember);
        return crewMember.getId();
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
