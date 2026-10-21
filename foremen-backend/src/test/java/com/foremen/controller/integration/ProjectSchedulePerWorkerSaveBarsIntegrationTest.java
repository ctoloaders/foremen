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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-10b task 4.2 — end-to-end integration test for the <em>per-worker</em>
 * {@code PUT /api/project-schedules/bars} save path against a real Testcontainers PostgreSQL
 * instance through {@link MockMvc}. It proves the rewritten {@code saveBars} business workflow of
 * {@link com.foremen.controller.ProjectScheduleController} +
 * {@link com.foremen.service.ProjectScheduleService}: bars are now keyed by
 * {@code (projectMember, workCategory)} on the half-day grid, a single category may carry one bar
 * per covering worker, and a {@code null} member is the virtual {@code Uncovered_Category} bar.
 *
 * <p>Scenarios (one committed MockMvc request each, so a later read observes the committed version;
 * see {@link ProjectScheduleWorkflowIntegrationTest} for why this test is not {@code @Transactional}):
 * <ol>
 *   <li><b>Set per-worker bars</b> — two covering workers of one category each get their own bar on
 *       the 0.5 grid; the read returns one row with two bars + the virtual uncovered row (R3.1,
 *       R3.3).</li>
 *   <li><b>One version bump + one audit row</b> — the save bumps {@code @Version} exactly once and
 *       writes exactly one audit row (R9.2).</li>
 *   <li><b>Clear</b> — a both-null entry removes one worker's bar (R3.5).</li>
 *   <li><b>Invalid pairing</b> — a {@code (member, category)} where the member does not cover the
 *       category is rejected 400 {@code error.schedule.assignment.invalid}, changing nothing
 *       (R3.5).</li>
 *   <li><b>Virtual bar allowed</b> — a {@code projectMemberId: null} entry on the uncovered category
 *       is accepted (R3.2).</li>
 *   <li><b>Horizon reject</b> — a finish beyond the 3650-day horizon is rejected 400
 *       {@code error.schedule.bar.invalid}, changing nothing (R3.4).</li>
 *   <li><b>No-op</b> — re-saving the stored bars neither bumps the version nor writes an audit row
 *       (R7.5 carried over).</li>
 * </ol>
 *
 * <p>The {@code integration-test} profile builds the schema from JPA DDL ({@code create-drop}); the
 * ADMIN caller bypasses the ABAC matrix and project-scope gate, so no WORK_SCHEDULE grants need to
 * be seeded. Repeatability: every row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id) so no row collides with a prior run and no manual teardown is needed.
 *
 * <p>Validates: Requirements 3.4, 3.5, 6.3, 9.2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectSchedulePerWorkerSaveBarsIntegrationTest {

    private static final String BASE_PATH = "/api/project-schedules";
    private static final String BARS_PATH = BASE_PATH + "/bars";

    private static final String ADMIN = "ADMIN";
    private static final String WORKER = "WORKER";

    /** Localized (PL base bundle) text of the invalid-pairing 400 asserted below. */
    private static final String PL_ASSIGNMENT_INVALID_PREFIX = "Pracownik nie może wykonywać";
    /** Localized (PL base bundle) text of the invalid-bar 400 asserted below. */
    private static final String PL_BAR_INVALID_PREFIX = "Nieprawidłowy okres dla kategorii";

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

    private ProjectEntity project;
    private long adminUserId;
    private String adminToken;
    private EstimateEntity estimate;
    private MeasurementUnitEntity unit;

    /** Category A, covered by both workers; Category B, covered by nobody (uncovered). */
    private Long categoryA;
    private Long categoryB;
    /** The two ACTIVE WORKER member ids, both covering category A. */
    private Long workerMember1;
    private Long workerMember2;

    @BeforeEach
    void seed() {
        adminUserId = persistUser(ADMIN);
        adminToken = bearer(ADMIN, adminUserId);
        permissionCache.invalidate(ADMIN);
        projectAccessCache.invalidate(adminUserId);
        project = persistFixture();
    }

    @Test
    @DisplayName("per-worker saveBars: set, clear, invalid pairing, virtual bar, horizon, version+audit, no-op")
    void perWorkerSaveBars_endToEnd() throws Exception {
        // --- 1. Read (unscheduled): 2 rows, category A covered, category B uncovered; version 0. ---
        JsonNode view = readView();
        assertThat(view.get("version").asLong()).isEqualTo(0L);
        assertThat(view.get("rows")).hasSize(2);
        assertThat(rowByCategory(view, categoryA).get("covered").asBoolean()).isTrue();
        assertThat(rowByCategory(view, categoryB).get("covered").asBoolean()).isFalse();

        long auditBefore = scheduleAuditCount();

        // --- 2. Set per-worker bars: both covering workers of category A get a bar on the 0.5 grid.
        // Worker 1: start 1.0 / dur 2.5; worker 2: start 1.0 / dur 1.5 (half-day resolution). ---
        String setBody = """
                {"version": 0, "bars": [
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": 1.0, "durationDays": 2.5},
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": 1.0, "durationDays": 1.5}
                ]}
                """.formatted(workerMember1, categoryA, workerMember2, categoryA);
        saveBars(adminToken, setBody).andExpect(status().isOk());

        JsonNode afterSet = readView();
        // First write INSERTS the schedule at committed version 0 (no extra UPDATE on a new row).
        long versionAfterSet = afterSet.get("version").asLong();
        assertThat(versionAfterSet).isEqualTo(0L);

        JsonNode rowA = rowByCategory(afterSet, categoryA);
        assertThat(rowA.get("bars")).hasSize(2);
        JsonNode bar1 = barByMember(rowA, workerMember1);
        assertThat(bar1.get("startDay").asDouble()).isEqualTo(1.0);
        assertThat(bar1.get("durationDays").asDouble()).isEqualTo(2.5);
        assertThat(bar1.get("finishDay").asDouble()).isEqualTo(3.0); // (2 + 5 - 1)/2 half-days
        JsonNode bar2 = barByMember(rowA, workerMember2);
        assertThat(bar2.get("startDay").asDouble()).isEqualTo(1.0);
        assertThat(bar2.get("durationDays").asDouble()).isEqualTo(1.5);

        // Exactly one audit row written for the committed save (R9.2).
        assertThat(scheduleAuditCount()).isEqualTo(auditBefore + 1);

        // --- 3. One version bump: a SECOND (changing) write increments the committed version once
        // (R9.2). Move worker 1's bar on the half-day grid. ---
        long auditBeforeMove = scheduleAuditCount();
        String moveBody = """
                {"version": %d, "bars": [
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": 3.5, "durationDays": 2.0}
                ]}
                """.formatted(versionAfterSet, workerMember1, categoryA);
        saveBars(adminToken, moveBody).andExpect(status().isOk());

        JsonNode afterMove = readView();
        long versionAfterMove = afterMove.get("version").asLong();
        assertThat(versionAfterMove)
                .as("a changing save bumps the committed version exactly once (R9.2)")
                .isEqualTo(versionAfterSet + 1);
        assertThat(scheduleAuditCount())
                .as("exactly one more audit row per committed change (R9.2)")
                .isEqualTo(auditBeforeMove + 1);
        JsonNode movedBar = barByMember(rowByCategory(afterMove, categoryA), workerMember1);
        assertThat(movedBar.get("startDay").asDouble()).isEqualTo(3.5);
        assertThat(movedBar.get("durationDays").asDouble()).isEqualTo(2.0);
        // Worker 2's bar is untouched.
        assertThat(barByMember(rowByCategory(afterMove, categoryA), workerMember2)
                .get("durationDays").asDouble()).isEqualTo(1.5);

        // --- 4. Clear: a both-null entry removes worker 2's bar; worker 1's stays. ---
        String clearBody = """
                {"version": %d, "bars": [
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": null, "durationDays": null}
                ]}
                """.formatted(versionAfterMove, workerMember2, categoryA);
        saveBars(adminToken, clearBody).andExpect(status().isOk());

        JsonNode afterClear = readView();
        JsonNode rowAfterClear = rowByCategory(afterClear, categoryA);
        assertThat(rowAfterClear.get("bars")).hasSize(1);
        assertThat(barByMember(rowAfterClear, workerMember1)).isNotNull();
        long versionAfterClear = afterClear.get("version").asLong();

        // --- 5. Invalid pairing: worker 2 does NOT cover category B — rejected 400, nothing changes. ---
        String invalidPairing = """
                {"version": %d, "bars": [
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": 1.0, "durationDays": 1.0}
                ]}
                """.formatted(versionAfterClear, workerMember1, categoryB);
        saveBars(adminToken, invalidPairing)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith(PL_ASSIGNMENT_INVALID_PREFIX)));
        JsonNode afterInvalid = readView();
        assertThat(afterInvalid.get("version").asLong())
                .as("a rejected pairing changes nothing").isEqualTo(versionAfterClear);
        assertThat(rowByCategory(afterInvalid, categoryB).get("bars")).isEmpty();

        // --- 6. Virtual bar allowed: a null-member entry on the uncovered category B is accepted. ---
        long auditBeforeVirtual = scheduleAuditCount();
        String virtualBody = """
                {"version": %d, "bars": [
                  {"projectMemberId": null, "workCategoryId": %d, "startDay": 2.0, "durationDays": 3.0}
                ]}
                """.formatted(versionAfterClear, categoryB);
        saveBars(adminToken, virtualBody).andExpect(status().isOk());

        JsonNode afterVirtual = readView();
        JsonNode rowB = rowByCategory(afterVirtual, categoryB);
        assertThat(rowB.get("bars")).hasSize(1);
        JsonNode virtualBar = rowB.get("bars").get(0);
        assertThat(virtualBar.hasNonNull("projectMemberId"))
                .as("the virtual uncovered bar has a null member").isFalse();
        assertThat(virtualBar.get("startDay").asDouble()).isEqualTo(2.0);
        assertThat(virtualBar.get("durationDays").asDouble()).isEqualTo(3.0);
        assertThat(scheduleAuditCount()).isEqualTo(auditBeforeVirtual + 1);
        long versionAfterVirtual = afterVirtual.get("version").asLong();

        // --- 7. Horizon reject: a finish beyond 3650 days is rejected 400, nothing changes. ---
        String tooLong = """
                {"version": %d, "bars": [
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": 3649.0, "durationDays": 5.0}
                ]}
                """.formatted(versionAfterVirtual, workerMember1, categoryA);
        saveBars(adminToken, tooLong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith(PL_BAR_INVALID_PREFIX)));
        assertThat(readView().get("version").asLong())
                .as("a horizon-rejected save changes nothing").isEqualTo(versionAfterVirtual);

        // --- 8. No-op: re-saving worker 1's EXISTING bar (start 3.5 / dur 2.0) neither bumps the
        // version nor writes an audit row (R7.5 carried over). ---
        long auditBeforeNoop = scheduleAuditCount();
        String noopBody = """
                {"version": %d, "bars": [
                  {"projectMemberId": %d, "workCategoryId": %d, "startDay": 3.5, "durationDays": 2.0}
                ]}
                """.formatted(versionAfterVirtual, workerMember1, categoryA);
        saveBars(adminToken, noopBody).andExpect(status().isOk());

        JsonNode afterNoop = readView();
        assertThat(afterNoop.get("version").asLong())
                .as("a no-op save does not bump the version (R7.5)").isEqualTo(versionAfterVirtual);
        assertThat(scheduleAuditCount())
                .as("a no-op save writes no audit row (R7.5)").isEqualTo(auditBeforeNoop);
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

    private ResultActions saveBars(String token, String body) throws Exception {
        return mockMvc.perform(put(BARS_PATH)
                .header("Authorization", token)
                .param("projectId", projectId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static JsonNode rowByCategory(JsonNode view, Long categoryId) {
        for (JsonNode row : view.get("rows")) {
            if (row.get("workCategoryId").asLong() == categoryId) {
                return row;
            }
        }
        throw new AssertionError("no row for work category " + categoryId);
    }

    /** Count of audit rows written for the schedule aggregate (the only auditor exercised here). */
    private long scheduleAuditCount() {
        long count = 0;
        for (AuditLogEntity log : auditLogDao.findAll()) {
            if ("ProjectScheduleEntity".equals(log.getEntityClass())) {
                count++;
            }
        }
        return count;
    }

    private static JsonNode barByMember(JsonNode row, Long memberId) {
        for (JsonNode bar : row.get("bars")) {
            if (bar.hasNonNull("projectMemberId") && bar.get("projectMemberId").asLong() == memberId) {
                return bar;
            }
        }
        throw new AssertionError("no bar for member " + memberId + " in row " + row);
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
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        user = userDao.save(user);
        return user.getId();
    }

    /**
     * A DRAFT project with an estimate of two categories (A covered by two workers, B uncovered),
     * one estimate line each, and two ACTIVE WORKER members whose Member_Work_Categories both
     * include category A only.
     */
    private ProjectEntity persistFixture() {
        long suffix = nextId();

        ProjectEntity p = new ProjectEntity();
        p.setName("Per-Worker SaveBars Project " + suffix);
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

        WorkCategoryEntity catA = persistCategoryWithLine(suffix + "-A", 1, new BigDecimal("8000.00"), 1);
        WorkCategoryEntity catB = persistCategoryWithLine(suffix + "-B", 2, new BigDecimal("8000.00"), 2);
        categoryA = catA.getId();
        categoryB = catB.getId();

        // Two ACTIVE WORKER members, both covering category A (not B).
        workerMember1 = persistActiveWorker(p, suffix + "-w1", catA);
        workerMember2 = persistActiveWorker(p, suffix + "-w2", catA);

        return p;
    }

    private WorkCategoryEntity persistCategoryWithLine(String tag, int orderNo, BigDecimal valueNet, int lineNo) {
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

        return category;
    }

    /** Persists an ACTIVE WORKER member covering the given category, returning its member id. */
    private Long persistActiveWorker(ProjectEntity p, String tag, WorkCategoryEntity covered) {
        UserEntity crew = new UserEntity();
        crew.setName("Crew " + tag);
        crew.setEmail("crew+" + tag + "@example.com");
        crew.setRole(ensureRole(WORKER));
        crew.setActive(true);
        crew.setStatus(UserStatus.ACTIVE);
        crew.setLocale(java.util.Locale.forLanguageTag("pl"));
        crew = userDao.save(crew);

        ProjectMemberEntity crewMember = new ProjectMemberEntity();
        crewMember.setUser(crew);
        crewMember.setProjectId(p.getId());
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
