package com.foremen.controller.integration;

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
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.transaction.annotation.Transactional;
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
 * FOR-05-10 task 7.3 — ABAC per-role × per-endpoint integration tests for
 * {@link com.foremen.controller.ProjectScheduleController} ({@code /api/project-schedules}).
 *
 * <p>Boots the full application context with the real Spring Security filter chain, the
 * {@code PermissionInterceptor}, and the real {@code ForemenPermissionEvaluator} against a
 * Testcontainers PostgreSQL instance, mirroring {@link ProjectMemberControllerIntegrationTest}.
 * Requests hit the four Schedule_API endpoints through {@link MockMvc} so the class-level
 * {@code @PermissionResource("WORK_SCHEDULE")} + per-handler {@code @PermissionOperation}
 * enforcement runs exactly as it would for a real HTTP request, and the project-scope gate
 * ({@code ProjectScopedService} → {@link ProjectAccessCache}) runs against real
 * {@code project_members} rows.
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the {@code WORK_SCHEDULE} ABAC matrix that changeset
 * {@code 153-seed-work-schedule-resource.xml} would normally seed is NOT present at runtime. This
 * test therefore seeds the matrix directly via the JPA layer per test (mirroring
 * {@link ProjectMemberControllerIntegrationTest}): the {@code WORK_SCHEDULE} resource, the CRUD
 * operations, and the per-role grants of changeset 153 (FOR-05-10 Requirement 1 / design D13):
 * <ul>
 *   <li>ADMIN, MANAGER — CREATE, READ, UPDATE, DELETE;</li>
 *   <li>FOREMAN, ESTIMATOR — READ, UPDATE;</li>
 *   <li>WORKER, FINANCIER, CLIENT — READ only.</li>
 * </ul>
 * ADMIN bypasses the matrix at the {@code ForemenPermissionEvaluator} level regardless.
 *
 * <p><b>The controller resolves (Requirement 2.2):</b> GET {@code /} and GET {@code /readiness} →
 * READ; PUT {@code /bars} and POST {@code /auto-create} → UPDATE. The test exercises every role
 * against every endpoint and asserts the HTTP status the matrix mandates.
 *
 * <p><b>Startup validator (Requirement 2.1).</b> The {@code @SpringBootTest} context only starts if
 * {@code PermissionAnnotationValidator} accepts {@code ProjectScheduleController} — a controller
 * carrying {@code @PermissionResource} with any in-scope handler lacking a matching
 * {@code @PermissionOperation} fails startup. Every test in this class therefore implicitly proves
 * the validator accepts the controller; {@link StartupValidator#startupAcceptsTheController()} makes
 * that assertion explicit by driving one request through the booted context.
 *
 * <p><b>Project scoping and 404 (Requirements 3.2, 3.3).</b> A non-ADMIN caller must be a member of
 * the project to pass the access gate; a non-member and a non-existent project both yield HTTP 404
 * {@code error.entity.not.found} with an identical body (asserted with identical request bodies in
 * {@link NotFoundScoping}).
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id combined with unique emails and codes), the two permission caches are invalidated for the
 * seeded role codes / per-run user ids before each test, and {@code @Transactional} rolls the whole
 * database back after each test, so the suite is safely re-runnable.
 *
 * <p>Validates: Requirements 2.1, 2.3, 2.4, 2.5, 3.2, 3.3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectScheduleControllerAbacIntegrationTest {

    private static final String BASE_PATH = "/api/project-schedules";
    private static final String BARS_PATH = BASE_PATH + "/bars";
    private static final String AUTO_CREATE_PATH = BASE_PATH + "/auto-create";
    private static final String READINESS_PATH = BASE_PATH + "/readiness";

    private static final String WORK_SCHEDULE = "WORK_SCHEDULE";
    private static final String ESTIMATE = "ESTIMATE";

    // The eight system role codes exercised. ADMIN bypasses the matrix; the rest hold the grants of
    // changeset 153. WORKER is also the project role minted onto the ACTIVE crew member.
    private static final String ADMIN = "ADMIN";
    private static final String MANAGER = "MANAGER";
    private static final String FOREMAN = "FOREMAN";
    private static final String ESTIMATOR = "ESTIMATOR";
    private static final String WORKER = "WORKER";
    private static final String FINANCIER = "FINANCIER";
    private static final String CLIENT = "CLIENT";

    // Localized error.access.denied (PL base bundle), mirrors ProjectMemberControllerIntegrationTest.
    private static final String PL_ACCESS_DENIED = "Dostęp zabroniony. Brak wymaganych uprawnień.";

    /** Per-run unique id source keeping every scenario's data collision-free and re-runnable. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

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
        // The schedule rate is a @Validated @NotNull @DecimalMin(exclusive 0) property; the base
        // application.yml default (2000) binds under the integration-test profile, but pin it here so
        // the ScheduleProperties bean is unambiguously valid regardless of environment overrides.
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

    @PersistenceContext
    private EntityManager entityManager;

    /** The design-stage project every scenario reads / writes (DRAFT ⇒ Schedule_Editable_Status). */
    private ProjectEntity project;

    @BeforeEach
    void seedMatrixProjectAndCrew() {
        // Liquibase is disabled under the integration-test profile, so seed the WORK_SCHEDULE (and
        // ESTIMATE, for the Money_Viewer path) ABAC matrix directly via JPA, per test, rolled back by
        // @Transactional. This mirrors ProjectMemberControllerIntegrationTest.
        ResourceEntity workSchedule = createResource(WORK_SCHEDULE);
        ResourceEntity estimate = createResource(ESTIMATE);
        OperationEntity create = createOperation("CREATE");
        OperationEntity read = createOperation("READ");
        OperationEntity update = createOperation("UPDATE");
        OperationEntity delete = createOperation("DELETE");

        // ADMIN: no grant needed (bypass), but the role must exist to mint a ROLE_ADMIN token.
        ensureRole(ADMIN);
        // Changeset 153 (FOR-05-10 R1 / D13): the WORK_SCHEDULE grants per role.
        grantRole(MANAGER, workSchedule, create, read, update, delete);
        grantRole(FOREMAN, workSchedule, read, update);
        grantRole(ESTIMATOR, workSchedule, read, update);
        grantRole(WORKER, workSchedule, read);
        grantRole(FINANCIER, workSchedule, read);
        grantRole(CLIENT, workSchedule, read);
        // ESTIMATE READ for the Money_Viewer roles (changeset 135/136): MANAGER + ESTIMATOR. Not
        // asserted by the ABAC status matrix but seeded so the resource graph is consistent.
        grantRole(MANAGER, estimate, read);
        grantRole(ESTIMATOR, estimate, read);

        // Invalidate the long-lived permission cache for every seeded role so this test's freshly
        // seeded (and later rolled-back) matrix is read, never a sibling test's cached PermissionSet.
        for (String code : new String[] {ADMIN, MANAGER, FOREMAN, ESTIMATOR, WORKER, FINANCIER, CLIENT}) {
            permissionCache.invalidate(code);
        }

        // A DRAFT project with an estimate of exactly one line (one Schedule_Row), so writes resolve a
        // real category and auto-create has a row to lay out; and one ACTIVE WORKER so auto-create has
        // a non-empty crew. The project is reused across the role scenarios of a single test method.
        project = persistProjectWithEstimateLineAndCrew();

        entityManager.flush();
    }

    // =======================================================================
    // 401 — unauthenticated (Requirement 2.3)
    // =======================================================================

    @Nested
    @DisplayName("unauthenticated requests get 401 on every endpoint (R2.3)")
    class Unauthenticated {

        @Test
        @DisplayName("GET /api/project-schedules without a token -> 401")
        void getView_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get(BASE_PATH).param("projectId", projectId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api/project-schedules/readiness without a token -> 401")
        void readiness_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get(READINESS_PATH).param("projectId", projectId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PUT /api/project-schedules/bars without a token -> 401")
        void saveBars_unauthenticated_returns401() throws Exception {
            mockMvc.perform(put(BARS_PATH)
                            .param("projectId", projectId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBarsBody()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/project-schedules/auto-create without a token -> 401")
        void autoCreate_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post(AUTO_CREATE_PATH)
                            .param("projectId", projectId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(autoCreateBody()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =======================================================================
    // Full-access roles: ADMIN + MANAGER reach all four endpoints (R2.2)
    // =======================================================================

    @Nested
    @DisplayName("ADMIN and MANAGER reach every endpoint (R2.2)")
    class FullAccessRoles {

        @Test
        @DisplayName("ADMIN: read, readiness, save, auto-create all succeed")
        void admin_reachesEveryEndpoint() throws Exception {
            assertReachesEveryEndpoint(ADMIN);
        }

        @Test
        @DisplayName("MANAGER (CRUD on WORK_SCHEDULE): read, readiness, save, auto-create all succeed")
        void manager_reachesEveryEndpoint() throws Exception {
            assertReachesEveryEndpoint(MANAGER);
        }
    }

    // =======================================================================
    // Read+write roles: FOREMAN + ESTIMATOR read AND write (R2.2)
    // =======================================================================

    @Nested
    @DisplayName("FOREMAN and ESTIMATOR read and write (R2.2)")
    class ReadWriteRoles {

        @Test
        @DisplayName("FOREMAN (READ + UPDATE): read, readiness, save, auto-create all succeed")
        void foreman_readsAndWrites() throws Exception {
            assertReachesEveryEndpoint(FOREMAN);
        }

        @Test
        @DisplayName("ESTIMATOR (READ + UPDATE): read, readiness, save, auto-create all succeed")
        void estimator_readsAndWrites() throws Exception {
            assertReachesEveryEndpoint(ESTIMATOR);
        }
    }

    // =======================================================================
    // Read-only roles: WORKER / FINANCIER / CLIENT read; writes 403 (R2.4, R2.5)
    // =======================================================================

    @Nested
    @DisplayName("WORKER / FINANCIER / CLIENT read but cannot write (R2.4, R2.5)")
    class ReadOnlyRoles {

        @Test
        @DisplayName("WORKER (READ only): reads succeed, writes 403 error.access.denied")
        void worker_readsButWritesRejected() throws Exception {
            assertReadsSucceedWritesForbidden(WORKER);
        }

        @Test
        @DisplayName("FINANCIER (READ only): reads succeed, writes 403 error.access.denied")
        void financier_readsButWritesRejected() throws Exception {
            assertReadsSucceedWritesForbidden(FINANCIER);
        }

        @Test
        @DisplayName("CLIENT (READ only): reads succeed, writes 403 error.access.denied")
        void client_readsButWritesRejected() throws Exception {
            assertReadsSucceedWritesForbidden(CLIENT);
        }
    }

    // =======================================================================
    // Project scoping: 404 for a non-member and a non-existent project (R3.2, R3.3)
    // =======================================================================

    @Nested
    @DisplayName("project scoping: 404 for a non-member and a non-existent project with identical bodies (R3.2, R3.3)")
    class NotFoundScoping {

        @Test
        @DisplayName("a MANAGER who is NOT a member of the project gets 404 error.entity.not.found")
        void nonMember_returns404() throws Exception {
            // A MANAGER user with the WORK_SCHEDULE grant but NO membership of the project: the
            // project-scope gate rejects before any business logic (R3.2).
            long nonMemberUserId = persistUserWithRole(MANAGER);
            projectAccessCache.invalidate(nonMemberUserId);

            mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", bearer(MANAGER, nonMemberUserId))
                            .param("projectId", projectId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("a non-member (404) and a non-existent project (404) return identical bodies for the same request")
        void nonMemberAndNonExistentHaveIdenticalBodies() throws Exception {
            long nonMemberUserId = persistUserWithRole(MANAGER);
            projectAccessCache.invalidate(nonMemberUserId);
            String token = bearer(MANAGER, nonMemberUserId);

            // Same caller, same shape of request — only the projectId differs (an in-scope-but-not-a-
            // member project vs a project id that does not exist). Both must be 404 with the SAME body.
            String nonMemberBody = mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", token)
                            .param("projectId", projectId()))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            long missingProjectId = 9_000_000_000L + nextId();
            String nonExistentBody = mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", token)
                            .param("projectId", String.valueOf(missingProjectId)))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            // The 404 bodies must be identical beyond two intrinsically request-specific fields: the
            // ErrorResponse `timestamp` (a per-request Instant) and the project id echoed into the
            // localized `message`. Compare the semantic fields (status, error, message, path) with the
            // id normalized out and the timestamp ignored: an out-of-scope project must be
            // indistinguishable from a missing one (R3.3 — "a response body identical to the one of
            // criterion 2").
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode nonMemberNode =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(nonMemberBody);
            com.fasterxml.jackson.databind.node.ObjectNode nonExistentNode =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(nonExistentBody);

            // Drop the per-request timestamp (an Instant that is always different) and the `message`
            // (which embeds the echoed project id, locale-grouped by MessageFormat, so it is
            // intrinsically id-specific). The REMAINING fields — status, error, path — must be
            // byte-identical between the two 404s (R3.3).
            nonMemberNode.remove("timestamp");
            nonExistentNode.remove("timestamp");
            String nonMemberMessage = textOrNull(nonMemberNode.remove("message"));
            String nonExistentMessage = textOrNull(nonExistentNode.remove("message"));

            org.assertj.core.api.Assertions.assertThat(nonExistentNode)
                    .as("a non-existent project's 404 body must match a non-member's 404 body beyond "
                            + "the timestamp and the id echoed into the message (R3.3)")
                    .isEqualTo(nonMemberNode);

            // The two messages differ only in the embedded id: strip every digit and grouping space
            // (MessageFormat renders large ids as e.g. "1 800 652 168 947") and the localized template
            // that remains must be identical — proving the SAME error.entity.not.found code (R3.3).
            org.assertj.core.api.Assertions.assertThat(stripNumber(nonExistentMessage))
                    .as("both 404s carry the same localized error.entity.not.found template (R3.3)")
                    .isEqualTo(stripNumber(nonMemberMessage));

            // And the shared code/status are exactly the entity-not-found 404.
            org.assertj.core.api.Assertions.assertThat(nonMemberNode.get("status").asInt()).isEqualTo(404);
        }

        @Test
        @DisplayName("even an ADMIN gets 404 for a non-existent project (R3.3)")
        void admin_nonExistentProject_returns404() throws Exception {
            long adminUserId = persistUserWithRole(ADMIN);
            long missingProjectId = 9_000_000_000L + nextId();

            mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", bearer(ADMIN, adminUserId))
                            .param("projectId", String.valueOf(missingProjectId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // =======================================================================
    // Startup validator accepts the controller (Requirement 2.1)
    // =======================================================================

    @Nested
    @DisplayName("PermissionAnnotationValidator accepts the controller at startup (R2.1)")
    class StartupValidator {

        @Test
        @DisplayName("the context booted (validator accepted the controller) and a guarded endpoint is reachable")
        void startupAcceptsTheController() throws Exception {
            // Reaching this test means the @SpringBootTest context started, which only happens if
            // PermissionAnnotationValidator accepted ProjectScheduleController (its @PermissionResource
            // combined with every handler's @PermissionOperation). Drive one guarded request through
            // the booted context to make the "controller is wired and enforceable" assertion explicit.
            long adminUserId = persistUserWithRole(ADMIN);

            mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", bearer(ADMIN, adminUserId))
                            .param("projectId", projectId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projectId").value(project.getId()));
        }
    }

    // =======================================================================
    // Shared assertions
    // =======================================================================

    /** A member caller with full access reaches all four endpoints with 200. */
    private void assertReachesEveryEndpoint(String roleCode) throws Exception {
        long userId = persistMemberWithRole(roleCode);
        String token = bearer(roleCode, userId);

        read(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId()));
        readiness(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("schedule"));
        // Save with the current version 0 (no schedule row yet) succeeds and bumps the version.
        saveBars(token).andExpect(status().isOk());
        // Auto-create needs the fresh version after the save: re-read it, then auto-create.
        long version = currentVersion(token);
        autoCreate(token, version).andExpect(status().isOk());
    }

    /** A member caller with READ only reaches the reads with 200 and is rejected 403 on writes. */
    private void assertReadsSucceedWritesForbidden(String roleCode) throws Exception {
        long userId = persistMemberWithRole(roleCode);
        String token = bearer(roleCode, userId);

        read(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId()));
        readiness(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("schedule"));

        // Writes resolve to WORK_SCHEDULE UPDATE, which a READ-only role lacks: 403 before any
        // business logic, carrying error.access.denied, and changing no data (R2.4, R2.5).
        saveBars(token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(PL_ACCESS_DENIED));
        autoCreate(token, 0L).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(PL_ACCESS_DENIED));
    }

    // =======================================================================
    // Endpoint helpers
    // =======================================================================

    private ResultActions read(String token) throws Exception {
        return mockMvc.perform(get(BASE_PATH)
                .header("Authorization", token)
                .param("projectId", projectId()));
    }

    private ResultActions readiness(String token) throws Exception {
        return mockMvc.perform(get(READINESS_PATH)
                .header("Authorization", token)
                .param("projectId", projectId()));
    }

    private ResultActions saveBars(String token) throws Exception {
        return mockMvc.perform(put(BARS_PATH)
                .header("Authorization", token)
                .param("projectId", projectId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBarsBody()));
    }

    private ResultActions autoCreate(String token, long version) throws Exception {
        return mockMvc.perform(post(AUTO_CREATE_PATH)
                .header("Authorization", token)
                .param("projectId", projectId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(autoCreateBody(version)));
    }

    /** Reads the current Schedule_Version off the view, so a follow-up write carries a fresh version. */
    private long currentVersion(String token) throws Exception {
        String body = read(token).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        return node.get("version").asLong();
    }

    // =======================================================================
    // Request-body builders
    // =======================================================================

    /** A save setting the single row's bar to (startDay=1, durationDays=1) with version 0. */
    private String saveBarsBody() {
        Long categoryId = project == null ? 0L : rowCategoryId;
        return """
                {"version": 0, "bars": [{"workCategoryId": %d, "startDay": 1, "durationDays": 1}]}
                """.formatted(categoryId);
    }

    private String autoCreateBody() {
        return autoCreateBody(0L);
    }

    private String autoCreateBody(long version) {
        return """
                {"version": %d}
                """.formatted(version);
    }

    // =======================================================================
    // Seed helpers
    // =======================================================================

    private static long nextId() {
        return RUN_ID.incrementAndGet();
    }

    /** The textual value of a (possibly null) JSON node, or {@code null}. */
    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * Strips the embedded project id out of a localized {@code error.entity.not.found} message so two
     * messages that differ only in their id compare equal: removes every digit and every Unicode
     * whitespace character (MessageFormat renders a large id with locale grouping separators such as
     * the narrow no-break space, e.g. {@code "1 800 652 168 947"}), leaving the fixed template text.
     */
    private static String stripNumber(String message) {
        return message == null ? null : message.replaceAll("[\\d\\s\\u00A0\\u202F]", "");
    }

    private String projectId() {
        return String.valueOf(project.getId());
    }

    /** The work category id of the project's single Schedule_Row, used in the save body. */
    private Long rowCategoryId;

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
        user.setLocale("pl");
        user = userDao.save(user);
        entityManager.flush();
        return user.getId();
    }

    /**
     * Persists a user with the given system role AND makes that user a member of the test project, so
     * the project-scope gate (R3.2) admits a non-ADMIN caller. Returns the user id. The membership's
     * project-role is WORKER (any role satisfies the FK; it is unrelated to the system role that
     * drives the ABAC matrix). The access cache for this fresh user id is invalidated so the gate
     * reloads the just-created membership from the current transaction.
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
     * Builds a DRAFT project with a one-line estimate (one Schedule_Row) and one ACTIVE WORKER crew
     * member, so reads return a row, saves resolve a real category, and auto-create has both a crew
     * and a row. Records the row's work category id in {@link #rowCategoryId}.
     */
    private ProjectEntity persistProjectWithEstimateLineAndCrew() {
        long suffix = nextId();

        ProjectEntity p = new ProjectEntity();
        p.setName("Schedule ABAC Project " + suffix);
        p.setStatus(ProjectStatus.DRAFT);
        p = projectDao.save(p);

        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("CUR" + suffix);
        currency.setSymbol("z");
        currency.setNameRU("Валюта");
        currency.setNamePL("Waluta");
        currency.setActive(true);
        currency = currencyDao.save(currency);

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("u" + suffix);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        unit = measurementUnitDao.save(unit);

        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode("C" + suffix);
        category.setOrderNo(1);
        category.setNameRU("Демонтаж");
        category.setNamePL("Rozbiórka");
        category.setActive(true);
        category = workCategoryDao.save(category);
        this.rowCategoryId = category.getId();

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setWorkCategory(category);
        workItem.setUnit(unit);
        workItem.setNameRU("работа");
        workItem.setNamePL("praca");
        workItem.setCode("WI" + suffix);
        workItem.setActive(true);
        workItem = workItemDao.save(workItem);

        EstimateEntity estimate = new EstimateEntity();
        estimate.setProject(p);
        estimate.setCurrency(currency);
        estimate = estimateDao.save(estimate);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(workItem);
        line.setUnit(unit);
        line.setLineNo(1);
        line.setUnitPrice(new BigDecimal("100.00"));
        line.setQuantity(new BigDecimal("10"));
        line.setValueNet(new BigDecimal("1000.00"));
        estimateLineDao.save(line);

        // One ACTIVE WORKER so auto-create has a non-empty Crew_Size.
        UserEntity crew = new UserEntity();
        crew.setName("Crew " + suffix);
        crew.setEmail("crew+" + suffix + "@example.com");
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
