package com.foremen.controller.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.ProjectAccessCache;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-09 task 5.3 — the per-role &times; per-endpoint ABAC matrix for
 * {@link com.foremen.controller.ProjectMemberController}, exercised end-to-end through the real
 * Spring Security filter chain and {@code PermissionInterceptor} against a Testcontainers
 * PostgreSQL database, mirroring the container / profile / JPA-seeding shape of
 * {@link ProjectMemberControllerIntegrationTest} and
 * {@link com.foremen.config.security.integration.DemoPermissionEndpointIntegrationTest}.
 *
 * <p>Unlike the FOR-03-04 integration test, every scenario here seeds a <b>real
 * {@link ProjectEntity}</b> in an Editable_Status and makes the authenticated caller a genuine
 * {@code project_members} member of it, so the FOR-03-04 project-scope gate (task 5.1,
 * {@code getProjectIdPath() == "projectId"}) passes for non-ADMIN callers and the test isolates the
 * ABAC {@code (resource, operation)} decision from the scope decision. The caller principal is the
 * caller user's real numeric id (how the JWT layer populates the {@code SecurityContext}), so
 * {@link ProjectAccessCache} self-loads the caller's genuine memberships.
 *
 * <p><b>What this asserts (Requirements 2.3, 2.4, 2.7, 2.8, 3.3, 3.4):</b>
 * <ul>
 *   <li><b>No grant (WORKER, CLIENT)</b> &rarr; 403 {@code error.access.denied} on <em>every</em>
 *       endpoint, read or write (Requirement 2.3 / 2.4 / 2.7).</li>
 *   <li><b>READ-only grant (FOREMAN, ESTIMATOR, FINANCIER)</b> &rarr; 403 {@code error.access.denied}
 *       on the write endpoints (CREATE assign, DELETE remove) but 200 on the READ endpoints
 *       (list, projects) (Requirement 2.4 / 2.8).</li>
 *   <li><b>401 before any permission / scope check</b> — an unauthenticated request is rejected by
 *       the security filter with 401, ahead of the interceptor's 403 and the service's 404, so a
 *       caller lacking a token never learns whether the grant or the project would have allowed the
 *       call (Requirement 2.7, canonical order step 1).</li>
 *   <li><b>ADMIN success</b> — the ADMIN bypass reaches every endpoint (Requirement 2.8).</li>
 *   <li><b>404 indistinguishability</b> — for a caller holding READ, an <em>inaccessible but
 *       existing</em> project and a <em>non-existent</em> project both yield 404
 *       {@code error.entity.not.found} with a byte-identical body, so project existence never leaks
 *       across the scope boundary (Requirements 3.3, 3.4).</li>
 * </ul>
 *
 * <p>Repeatability: a per-run {@link AtomicLong} suffix keeps emails / names collision-free and
 * {@code @Transactional} rolls the database back after each test; the self-loading
 * {@link ProjectAccessCache} is explicitly invalidated for every caller id used, so a rolled-back
 * id reused by a later test never serves a stale allowed-project set.
 *
 * <p>Validates: Requirements 2.3, 2.4, 2.7, 2.8, 3.3, 3.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectMemberControllerAbacIntegrationTest {

    private static final String BASE_PATH = "/api/project-members";
    private static final String PROJECTS_PATH = BASE_PATH + "/projects";

    // Seeded system role codes and their PROJECT_MEMBERS grants (changeset 015 / 136 matrix, D1):
    private static final String ADMIN = "ADMIN";          // bypass, reaches everything
    private static final String MANAGER = "MANAGER";      // full CRUD
    private static final String FOREMAN = "FOREMAN";       // READ only
    private static final String ESTIMATOR = "ESTIMATOR";   // READ only
    private static final String FINANCIER = "FINANCIER";   // READ only
    private static final String WORKER = "WORKER";         // no grant
    private static final String CLIENT = "CLIENT";         // no grant

    /** error.access.denied, resolved in PL (base) and RU bundles (exact messages.properties text). */
    private static final String PL_ACCESS_DENIED = "Dostęp zabroniony. Brak wymaganych uprawnień.";
    private static final String RU_ACCESS_DENIED = "Доступ запрещён. Недостаточно прав.";

    /** Per-run unique id source keeping every scenario's data collision-free and re-runnable. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

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
    private ProjectAccessCache projectAccessCache;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seedPermissionMatrix() {
        // Liquibase is disabled under integration-test (DDL from JPA), so seed the system roles we
        // authenticate as plus the PROJECT_MEMBERS matrix directly via JPA (per test, rolled back by
        // @Transactional). This reproduces the live changeset 015 / 136 grants (D1):
        //   ADMIN  — bypass (no grant row needed, but the role must exist to mint ROLE_ADMIN)
        //   MANAGER — CREATE, READ, UPDATE, DELETE
        //   FOREMAN / ESTIMATOR / FINANCIER — READ only
        //   WORKER / CLIENT — no grant at all
        ResourceEntity projectMembers = createResource("PROJECT_MEMBERS");
        OperationEntity create = createOperation("CREATE");
        OperationEntity read = createOperation("READ");
        OperationEntity update = createOperation("UPDATE");
        OperationEntity delete = createOperation("DELETE");

        ensureRole(ADMIN);
        grantRole(MANAGER, projectMembers, create, read, update, delete);
        grantRole(FOREMAN, projectMembers, read);
        grantRole(ESTIMATOR, projectMembers, read);
        grantRole(FINANCIER, projectMembers, read);
        ensureRole(WORKER);   // no grant
        ensureRole(CLIENT);   // no grant

        entityManager.flush();
    }

    // ======================================================================
    // No-grant roles (WORKER, CLIENT) — 403 error.access.denied on EVERY endpoint (2.3, 2.4, 2.7)
    // ======================================================================

    @Nested
    @DisplayName("No PROJECT_MEMBERS grant (WORKER / CLIENT) -> 403 on every endpoint")
    class NoGrantRoles {

        @Test
        @DisplayName("WORKER: assign / remove / list / projects all -> 403 error.access.denied")
        void worker_everyEndpointDenied() throws Exception {
            assertEveryEndpointDenied(WORKER);
        }

        @Test
        @DisplayName("CLIENT: assign / remove / list / projects all -> 403 error.access.denied")
        void client_everyEndpointDenied() throws Exception {
            assertEveryEndpointDenied(CLIENT);
        }

        private void assertEveryEndpointDenied(String roleCode) throws Exception {
            Fixture f = newAccessibleFixture(roleCode);

            // CREATE (assign) -> 403
            assertAccessDenied(mockMvc.perform(post(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(f.targetUserId, f.projectId, f.roleId))));

            // DELETE (remove) -> 403
            assertAccessDenied(mockMvc.perform(delete(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .param("userId", String.valueOf(f.targetUserId))
                    .param("projectId", String.valueOf(f.projectId))));

            // READ (list members) -> 403
            assertAccessDenied(mockMvc.perform(get(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .param("projectId", String.valueOf(f.projectId))));

            // READ (list projects) -> 403
            assertAccessDenied(mockMvc.perform(get(PROJECTS_PATH)
                    .header("Authorization", f.bearer)
                    .param("userId", String.valueOf(f.callerId))));
        }
    }

    // ======================================================================
    // READ-only roles (FOREMAN, ESTIMATOR, FINANCIER) — write denied, read allowed (2.4, 2.8)
    // ======================================================================

    @Nested
    @DisplayName("READ-only PROJECT_MEMBERS grant (FOREMAN / ESTIMATOR / FINANCIER)")
    class ReadOnlyRoles {

        @Test
        @DisplayName("FOREMAN: writes -> 403, reads -> 200")
        void foreman_writeDeniedReadAllowed() throws Exception {
            assertWriteDeniedReadAllowed(FOREMAN);
        }

        @Test
        @DisplayName("ESTIMATOR: writes -> 403, reads -> 200")
        void estimator_writeDeniedReadAllowed() throws Exception {
            assertWriteDeniedReadAllowed(ESTIMATOR);
        }

        @Test
        @DisplayName("FINANCIER: writes -> 403, reads -> 200")
        void financier_writeDeniedReadAllowed() throws Exception {
            assertWriteDeniedReadAllowed(FINANCIER);
        }

        private void assertWriteDeniedReadAllowed(String roleCode) throws Exception {
            Fixture f = newAccessibleFixture(roleCode);

            // CREATE (assign) -> 403 (no CREATE grant)
            assertAccessDenied(mockMvc.perform(post(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(f.targetUserId, f.projectId, f.roleId))));

            // DELETE (remove) -> 403 (no DELETE grant)
            assertAccessDenied(mockMvc.perform(delete(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .param("userId", String.valueOf(f.targetUserId))
                    .param("projectId", String.valueOf(f.projectId))));

            // READ (list members) -> 200 (grant present, caller is a member so scope passes)
            mockMvc.perform(get(BASE_PATH)
                            .header("Authorization", f.bearer)
                            .param("projectId", String.valueOf(f.projectId)))
                    .andExpect(status().isOk());

            // READ (list projects) -> 200 (grant present)
            mockMvc.perform(get(PROJECTS_PATH)
                            .header("Authorization", f.bearer)
                            .param("userId", String.valueOf(f.callerId)))
                    .andExpect(status().isOk());
        }
    }

    // ======================================================================
    // 401 before any permission / scope check (2.7, canonical order step 1)
    // ======================================================================

    @Nested
    @DisplayName("Unauthenticated -> 401 before any permission / scope check")
    class Unauthenticated {

        @Test
        @DisplayName("GET list without a token -> 401 (ahead of the READ grant check)")
        void listUnauthenticated_returns401() throws Exception {
            mockMvc.perform(get(BASE_PATH).param("projectId", "1"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST assign without a token -> 401 (ahead of the CREATE grant and project-scope checks)")
        void assignUnauthenticated_returns401() throws Exception {
            // Even with a well-formed body naming a real project, the missing token is rejected
            // first: no 403 (grant) and no 404 (scope) is reached, so neither the grant nor the
            // project's existence leaks to an anonymous caller.
            Fixture f = newAccessibleFixture(MANAGER);
            mockMvc.perform(post(BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(assignBody(f.targetUserId, f.projectId, f.roleId)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE remove without a token -> 401 (ahead of the DELETE grant and project-scope checks)")
        void removeUnauthenticated_returns401() throws Exception {
            mockMvc.perform(delete(BASE_PATH)
                            .param("userId", "1")
                            .param("projectId", "1"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ======================================================================
    // ADMIN success — the bypass reaches every endpoint (2.8)
    // ======================================================================

    @Test
    @DisplayName("ADMIN reaches every /api/project-members endpoint through the bypass -> 2xx")
    void admin_reachesEveryEndpoint() throws Exception {
        long projectId = seedProject(ProjectStatus.DRAFT);
        UserEntity target = createUser();
        long adminCallerId = createUser().getId();
        entityManager.flush();
        projectAccessCache.invalidate(adminCallerId);
        String bearer = bearer(adminCallerId, ADMIN);

        // assign -> 201 (CREATE via bypass; ADMIN skips the project-scope gate too)
        mockMvc.perform(post(BASE_PATH)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(target.getId(), projectId, defaultRoleId())))
                .andExpect(status().isCreated());

        // list members -> 200
        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", bearer)
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk());

        // list projects -> 200
        mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", bearer)
                        .param("userId", String.valueOf(target.getId())))
                .andExpect(status().isOk());

        // remove -> 204
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer)
                        .param("userId", String.valueOf(target.getId()))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isNoContent());
    }

    // ======================================================================
    // 404 indistinguishability: inaccessible-but-existing vs non-existent (3.3, 3.4)
    // ======================================================================

    @Nested
    @DisplayName("Non-ADMIN scope: inaccessible vs non-existent project are both 404 error.entity.not.found")
    class ScopeIndistinguishability {

        // The by-id / by-pair scope gate (ProjectMemberService.projectAccessCheck, task 5.1) rejects
        // an inaccessible OR non-existent project with the Access_Denied_Outcome before the duplicate,
        // user-existence, member-existence, and lifecycle checks run — so the two cases are reported
        // with the SAME status (404) and the SAME message code (error.entity.not.found), matching the
        // established FOR-03-04 convention asserted by ProjectScopingIT (status + message code; the
        // ForemenApiException rendering embeds only the opaque id, never the project's existence or the
        // caller's membership). This is the assertion level the repo uses for the Access_Denied_Outcome
        // (Requirements 3.3, 3.4). assign (POST, CREATE) and remove (DELETE) both funnel through the
        // gate; listMembers is NOT by-pair-scoped at this task (its enriched, scoped read arrives with
        // task 7.5), so it is intentionally not asserted here.

        @Test
        @DisplayName("POST assign: inaccessible-existing and non-existent projects both -> 404 error.entity.not.found")
        void assign_inaccessibleAndMissing_areIndistinguishable() throws Exception {
            // A MANAGER caller (holds CREATE) who is a member of f.projectId but NOT of the two below,
            // so the 404 is the pure project-scope outcome and not an access-denied.
            Fixture f = newAccessibleFixture(MANAGER);
            long projectInaccessible = seedProject(ProjectStatus.DRAFT); // exists, caller not a member
            long projectMissing = 9_000_000_000L + nextId();             // no projects row at all
            entityManager.flush();
            projectAccessCache.invalidate(f.callerId); // reload: caller is a member of f.projectId only

            String inaccessible = assertEntityNotFound(mockMvc.perform(post(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(f.targetUserId, projectInaccessible, f.roleId))));

            String missing = assertEntityNotFound(mockMvc.perform(post(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(f.targetUserId, projectMissing, f.roleId))));

            // Indistinguishable up to the opaque id: the two bodies are identical once the embedded
            // numeric id is stripped, so neither response reveals whether the project exists or whether
            // the caller is a member (Requirements 3.3, 3.4).
            assertThat(stripIds(inaccessible))
                    .as("an inaccessible project and a non-existent project must be reported identically "
                            + "(same 404 + error.entity.not.found, differing only in the opaque id)")
                    .isEqualTo(stripIds(missing));
        }

        @Test
        @DisplayName("DELETE remove: inaccessible-existing and non-existent projects both -> 404 error.entity.not.found")
        void remove_inaccessibleAndMissing_areIndistinguishable() throws Exception {
            // A MANAGER caller (holds DELETE) so the 404 is the project-scope outcome, not an
            // access-denied. The pair's membership never matters: the scope gate (step 4) rejects
            // before the member-existence check (step 6).
            Fixture f = newAccessibleFixture(MANAGER);
            long projectInaccessible = seedProject(ProjectStatus.DRAFT);
            long projectMissing = 9_000_000_000L + nextId();
            long someUserId = f.targetUserId;
            entityManager.flush();
            projectAccessCache.invalidate(f.callerId);

            String inaccessible = assertEntityNotFound(mockMvc.perform(delete(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .param("userId", String.valueOf(someUserId))
                    .param("projectId", String.valueOf(projectInaccessible))));

            String missing = assertEntityNotFound(mockMvc.perform(delete(BASE_PATH)
                    .header("Authorization", f.bearer)
                    .param("userId", String.valueOf(someUserId))
                    .param("projectId", String.valueOf(projectMissing))));

            assertThat(stripIds(inaccessible))
                    .as("an inaccessible project and a non-existent project must be reported identically "
                            + "(same 404 + error.entity.not.found, differing only in the opaque id)")
                    .isEqualTo(stripIds(missing));
        }
    }

    // ======================================================================
    // Fixtures and helpers
    // ======================================================================

    /** One ready-to-call scenario: an authenticated caller who IS a member of a real project. */
    private record Fixture(long callerId, String bearer, long projectId, long targetUserId, long roleId) {
    }

    /**
     * Builds a scenario where the caller (authenticated as {@code roleCode}) is a genuine member of a
     * freshly-seeded Editable_Status project, so the FOR-03-04 project-scope gate passes for a
     * non-ADMIN caller and the test isolates the ABAC decision. Also seeds a distinct target user to
     * assign / remove. The caller's {@link ProjectAccessCache} entry is invalidated so the self-loader
     * reflects the just-seeded membership.
     */
    private Fixture newAccessibleFixture(String roleCode) {
        long projectId = seedProject(ProjectStatus.DRAFT);
        UserEntity caller = createUser();
        UserEntity target = createUser();
        persistMembership(caller, projectId); // caller is a member -> project is Accessible for them
        entityManager.flush();
        projectAccessCache.invalidate(caller.getId());
        return new Fixture(
                caller.getId(),
                bearer(caller.getId(), roleCode),
                projectId,
                target.getId(),
                defaultRoleId());
    }

    /** Asserts a 403 whose body carries the resolved error.access.denied message (PL or RU). */
    private void assertAccessDenied(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        MvcResult result = actions
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("403 body must carry the resolved error.access.denied message (PL or RU locale)")
                .satisfiesAnyOf(
                        b -> assertThat(b).contains(PL_ACCESS_DENIED),
                        b -> assertThat(b).contains(RU_ACCESS_DENIED));
    }

    /**
     * Asserts a 404 whose body is the Access_Denied_Outcome ({@code error.entity.not.found}) — not an
     * access-denied (403) and not a lifecycle lock / duplicate (409) — and returns the raw body so the
     * caller can compare the inaccessible and missing cases for indistinguishability. The message is
     * the resolved {@code error.entity.not.found} template ("Nie znaleziono encji o ID {0}."), the same
     * template the FOR-03-04 scope convention surfaces for both a missing and an inaccessible anchor.
     */
    private String assertEntityNotFound(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        MvcResult result = actions
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("404 body must be the resolved error.entity.not.found message (not access-denied, not a lock)")
                .contains("Nie znaleziono encji o ID");
        return body;
    }

    /**
     * Normalizes a response body for the indistinguishability comparison by replacing every run of
     * digits with a placeholder and dropping the volatile ISO {@code timestamp} field. Two bodies that
     * are equal after this transform differ only in the opaque entity id and the response timestamp, so
     * an inaccessible project cannot be told apart from a non-existent one (Requirements 3.3, 3.4).
     */
    private static String stripIds(String body) {
        return body
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<ts>\"")
                .replaceAll("[0-9\\u00a0 ]+", "#");
    }

    private static long nextId() {
        return RUN_ID.incrementAndGet();
    }

    /** Mints a JWT whose principal name is the given userId and whose authority is ROLE_<code>. */
    private String bearer(long userId, String roleCode) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }

    /** A real role usable as the assigned project role (any seeded role satisfies the FK). */
    private long defaultRoleId() {
        return ensureRole(WORKER).getId();
    }

    private UserEntity createUser() {
        long id = nextId();
        UserEntity user = new UserEntity();
        user.setName("Test User " + id);
        user.setEmail("member+" + id + "@example.com");
        user.setRole(ensureRole(WORKER));
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLocale("ru");
        return userDao.save(user);
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

    /** Seeds a real projects row (so project_members.project_id FK holds) in the given status. */
    private long seedProject(ProjectStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setName("ABAC Project " + nextId());
        project.setStatus(status);
        return projectDao.save(project).getId();
    }

    private void persistMembership(UserEntity user, long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(ensureRole(WORKER));
        projectMemberDao.save(member);
    }

    private String assignBody(long userId, long projectId, long projectRoleId) {
        return """
                {"userId": %d, "projectId": %d, "projectRoleId": %d}
                """.formatted(userId, projectId, projectRoleId);
    }
}
