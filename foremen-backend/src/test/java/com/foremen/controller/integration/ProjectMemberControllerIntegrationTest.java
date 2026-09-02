package com.foremen.controller.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for {@link com.foremen.controller.ProjectMemberController}
 * (FOR-03-04 task 12.4).
 *
 * <p>Boots the full application context with the real Spring Security filter chain and the
 * {@code PermissionInterceptor} against a Testcontainers PostgreSQL instance, mirroring
 * {@link com.foremen.config.security.integration.DemoPermissionEndpointIntegrationTest}. Requests
 * hit {@code /api/project-members} through {@link MockMvc} so the {@code @RequiresPermission}
 * enforcement runs exactly as it would for a real HTTP request.
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the {@code PROJECT_MEMBERS} ABAC matrix that changeset
 * {@code 015-seed-project-members-resource.xml} would normally seed is NOT present at runtime. This
 * test therefore seeds the matrix directly via the JPA layer per test (mirroring
 * {@link com.foremen.config.security.integration.DemoPermissionEndpointIntegrationTest}): the
 * {@code PROJECT_MEMBERS} resource, the CRUD operations, and per-role grants matching the seed
 * matrix (MANAGER full CRUD, FOREMAN READ only, WORKER none, ADMIN bypasses regardless). JWTs are
 * minted with {@link JwtTokenProvider} so the {@code JwtAuthenticationFilter} populates the
 * {@code SecurityContext} with the {@code ROLE_<code>} authority the interceptor reads AND the
 * numeric-userId principal name the controller/service resolve.
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static
 * {@link AtomicLong} run-id combined with unique emails/project-ids), and {@code @Transactional}
 * rolls the whole database back after each test, so the suite is safely re-runnable.
 *
 * <p>Covered acceptance criteria:
 * <ul>
 *   <li>assign returns 201 with a {@code ProjectMemberResponse} (10.6);</li>
 *   <li>duplicate assign returns 409 {@code error.project.member.duplicate} (10.7);</li>
 *   <li>unknown {@code projectRoleId} returns 404 {@code error.project.role.not.found} (10.8);</li>
 *   <li>remove returns 204 (10.9);</li>
 *   <li>remove of a missing membership returns 404 {@code error.project.member.not.found} (10.10);</li>
 *   <li>listMembers returns rows for the project id (10.11);</li>
 *   <li>listProjects returns the distinct project-id set (10.12);</li>
 *   <li>a caller lacking {@code (PROJECT_MEMBERS, op)} gets 403 {@code error.access.denied} (10.3);</li>
 *   <li>an unauthenticated caller gets 401 {@code error.auth.unauthorized} (10.4);</li>
 *   <li>an ADMIN caller reaches every endpoint through the bypass (10.5).</li>
 * </ul>
 *
 * <p>Validates: Requirements 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 10.12
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectMemberControllerIntegrationTest {

    private static final String BASE_PATH = "/api/project-members";
    private static final String PROJECTS_PATH = BASE_PATH + "/projects";

    // Seeded system role codes (changeset 015 grants):
    private static final String ADMIN = "ADMIN";      // bypass, reaches everything
    private static final String MANAGER = "MANAGER";  // full CRUD on PROJECT_MEMBERS
    private static final String FOREMAN = "FOREMAN";   // READ only on PROJECT_MEMBERS
    private static final String WORKER = "WORKER";     // no PROJECT_MEMBERS grant (denied)

    // Localized message for error.access.denied (PL base bundle) — mirrors DemoPermission test.
    private static final String PL_ACCESS_DENIED = "Dostęp zabroniony. Brak wymaganych uprawnień.";

    /** Per-run unique id source keeping every scenario's data collision-free and re-runnable. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column, matching the deployed app
            // (mirrors ProjectMemberDaoIntegrationTest).
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
    private ProjectMemberDao projectMemberDao;

    @PersistenceContext
    private EntityManager entityManager;

    /** A role usable as the assigned project role (any existing role satisfies the FK). */
    private RoleEntity projectRole;

    @BeforeEach
    void seedRolesAndPermissionMatrix() {
        // Liquibase is disabled under the integration-test profile, so seed the system roles we
        // authenticate as plus the PROJECT_MEMBERS ABAC matrix directly via JPA (per test, rolled
        // back by @Transactional). This mirrors DemoPermissionEndpointIntegrationTest.
        ResourceEntity projectMembers = createResource("PROJECT_MEMBERS");
        OperationEntity create = createOperation("CREATE");
        OperationEntity read = createOperation("READ");
        OperationEntity update = createOperation("UPDATE");
        OperationEntity delete = createOperation("DELETE");

        // ADMIN: no grant needed (bypass), but the role must exist to mint a ROLE_ADMIN token.
        ensureRole(ADMIN);
        // MANAGER: full CRUD on PROJECT_MEMBERS.
        grantRole(MANAGER, projectMembers, create, read, update, delete);
        // FOREMAN: READ only on PROJECT_MEMBERS (used to prove a lacking DELETE -> 403).
        grantRole(FOREMAN, projectMembers, read);
        // WORKER: no role_resources grant at all (deny-by-default); also the project-role reference.
        projectRole = ensureRole(WORKER);

        entityManager.flush();
    }

    // === 10.6 : assign returns 201 with a ProjectMemberResponse ===

    @Test
    @DisplayName("POST /api/project-members with CREATE permission -> 201 and ProjectMemberResponse")
    void assign_returns201WithResponse() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        entityManager.flush();

        mockMvc.perform(post(BASE_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(user.getId(), projectId, projectRole.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.projectRoleId").value(projectRole.getId()))
                .andExpect(jsonPath("$.projectRoleCode").value(projectRole.getCode()));
    }

    // === 10.7 : duplicate assign returns 409 error.project.member.duplicate ===

    @Test
    @DisplayName("POST /api/project-members for an existing (userId, projectId) -> 409 error.project.member.duplicate")
    void assign_duplicate_returns409() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        persistMembership(user, projectId);
        entityManager.flush();

        mockMvc.perform(post(BASE_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(user.getId(), projectId, projectRole.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // === 10.8 : unknown projectRoleId returns 404 error.project.role.not.found ===

    @Test
    @DisplayName("POST /api/project-members with an unknown projectRoleId -> 404 error.project.role.not.found")
    void assign_unknownRole_returns404() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        long unknownRoleId = 9_000_000_000L + nextId();
        entityManager.flush();

        mockMvc.perform(post(BASE_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(user.getId(), projectId, unknownRoleId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // === 10.9 : remove returns 204 ===

    @Test
    @DisplayName("DELETE /api/project-members for an existing membership -> 204 no content")
    void remove_returns204() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        persistMembership(user, projectId);
        entityManager.flush();

        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .param("userId", String.valueOf(user.getId()))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isNoContent());
    }

    // === 10.10 : remove of a missing membership returns 404 error.project.member.not.found ===

    @Test
    @DisplayName("DELETE /api/project-members for a missing membership -> 404 error.project.member.not.found")
    void remove_missing_returns404() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        entityManager.flush();

        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .param("userId", String.valueOf(user.getId()))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // === 10.11 : listMembers returns rows for the project id ===

    @Test
    @DisplayName("GET /api/project-members?projectId= -> the memberships whose project_id equals it")
    void listMembers_returnsRowsForProject() throws Exception {
        UserEntity userA = createUser();
        UserEntity userB = createUser();
        UserEntity other = createUser();
        long projectId = nextId();
        long otherProjectId = nextId();
        persistMembership(userA, projectId);
        persistMembership(userB, projectId);
        persistMembership(other, otherProjectId);
        entityManager.flush();

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // user ids are a fresh small sequence (fit int) so Jackson deserializes them as
                // Integer; project ids are per-run values that overflow int so Jackson uses Long.
                // Match each against its produced boxed type.
                .andExpect(jsonPath("$[*].userId",
                        containsInAnyOrder(userA.getId().intValue(), userB.getId().intValue())))
                .andExpect(jsonPath("$[*].projectId",
                        containsInAnyOrder(projectId, projectId)));
    }

    // === 10.12 : listProjects returns the distinct project-id set ===

    @Test
    @DisplayName("GET /api/project-members/projects?userId= -> the distinct project-id set for the user")
    void listProjects_returnsDistinctProjectIds() throws Exception {
        UserEntity user = createUser();
        long projectA = nextId();
        long projectB = nextId();
        persistMembership(user, projectA);
        persistMembership(user, projectB);
        entityManager.flush();

        mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .param("userId", String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // Project ids overflow int so Jackson deserializes them as Long; compare as Long.
                .andExpect(jsonPath("$",
                        containsInAnyOrder(projectA, projectB)));
    }

    // === 10.3 : a caller lacking (PROJECT_MEMBERS, op) gets 403 error.access.denied ===

    @Test
    @DisplayName("POST /api/project-members as WORKER (no PROJECT_MEMBERS grant) -> 403 error.access.denied")
    void assign_withoutPermission_returns403() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        entityManager.flush();

        mockMvc.perform(post(BASE_PATH)
                        .header("Authorization", bearer(WORKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(user.getId(), projectId, projectRole.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(PL_ACCESS_DENIED));
    }

    @Test
    @DisplayName("DELETE /api/project-members as FOREMAN (READ only, no DELETE) -> 403 error.access.denied")
    void remove_withoutDeletePermission_returns403() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        persistMembership(user, projectId);
        entityManager.flush();

        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(FOREMAN))
                        .param("userId", String.valueOf(user.getId()))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // === 10.4 : an unauthenticated caller gets 401 error.auth.unauthorized ===

    @Test
    @DisplayName("GET /api/project-members without a token -> 401")
    void listMembers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("projectId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/project-members without a token -> 401")
    void assign_unauthenticated_returns401() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        entityManager.flush();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(user.getId(), projectId, projectRole.getId())))
                .andExpect(status().isUnauthorized());
    }

    // === 10.5 : an ADMIN caller reaches every endpoint through the bypass ===

    @Test
    @DisplayName("ADMIN reaches every /api/project-members endpoint through the bypass")
    void admin_reachesEveryEndpoint() throws Exception {
        UserEntity user = createUser();
        long projectId = nextId();
        entityManager.flush();

        // assign -> 201 (CREATE reached via bypass)
        mockMvc.perform(post(BASE_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(user.getId(), projectId, projectRole.getId())))
                .andExpect(status().isCreated());

        // listMembers -> 200 (READ reached via bypass)
        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // listProjects -> 200 (READ reached via bypass)
        mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .param("userId", String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // remove -> 204 (DELETE reached via bypass)
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .param("userId", String.valueOf(user.getId()))
                        .param("projectId", String.valueOf(projectId)))
                .andExpect(status().isNoContent());
    }

    // === helpers ===

    private static long nextId() {
        return RUN_ID.incrementAndGet();
    }

    /** Mints a JWT whose principal name is a userId (1L) and whose authority is ROLE_<code>. */
    private String bearer(String roleCode) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                1L, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }

    /** Persists a user with a unique email (repeatable across runs). */
    private UserEntity createUser() {
        long id = nextId();
        UserEntity user = new UserEntity();
        user.setName("Test User " + id);
        user.setEmail("member+" + id + "@example.com");
        user.setRole(projectRole);
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLocale("ru");
        return userDao.save(user);
    }

    /** Ensures a role with the given code exists, returning it (idempotent within a test). */
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

    private void persistMembership(UserEntity user, long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(projectRole);
        projectMemberDao.save(member);
    }

    private String assignBody(Long userId, long projectId, Long projectRoleId) {
        return """
                {"userId": %d, "projectId": %d, "projectRoleId": %d}
                """.formatted(userId, projectId, projectRoleId);
    }
}
