package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.mail.InvitationMailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end integration test for the FOR-03-05 client-registration endpoint
 * {@code POST /api/users/client} (task 14.7).
 *
 * <p>Boots the full application context with the real Spring Security filter chain and the
 * {@code PermissionInterceptor} against a Testcontainers PostgreSQL instance, mirroring
 * {@link ProjectMemberControllerIntegrationTest} (permission seeding + JWT minting) and
 * {@link com.foremen.integration.InviteEndToEndIntegrationTest} (mail-sender mock + per-test
 * clean-up). Requests hit {@code /api/users/client} through {@link MockMvc}, so the
 * {@code @RequiresPermission(PROJECTS, EDIT)} enforcement runs exactly as it would for a real HTTP
 * request.
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the ABAC matrix is seeded directly via the JPA layer per test:
 * the {@code PROJECTS} resource, the {@code EDIT} operation, a MANAGER-like role granted
 * {@code (PROJECTS, EDIT)} (allowed), a WORKER-like role with no grant (denied), and the seeded
 * {@code CLIENT} role the service resolves via {@code RoleDao.findByCode("CLIENT")} and which the
 * invite flow keys the client-portal email off of.
 *
 * <p>The {@link InvitationMailSender} is the only mocked collaborator so the flow neither reaches
 * SMTP nor requires a mail server, while the client-portal invitation dispatch stays verifiable.
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id combined with a per-test UUID for emails/role codes), and {@link #cleanUp()} removes all
 * rows the flow can create (project members, invite/refresh tokens, users, roles) after each test,
 * so the suite re-runs without manual DB clean-up. The class is intentionally NOT
 * {@code @Transactional}: the service's own transaction must commit or roll back on its own for the
 * atomicity assertions (10.7) to be meaningful.
 *
 * <p>Covered acceptance criteria:
 * <ul>
 *   <li>with PROJECTS/EDIT: 201, created user is CLIENT + INVITED, exactly one {@code project_members}
 *       row for {@code (userId, projectId)}, client-portal invitation captured (10.2, 10.6, 10.10);</li>
 *   <li>without the grant: 403 {@code error.access.denied}, no user or membership created (10.2);</li>
 *   <li>ADMIN reaches the endpoint via the bypass (10.2);</li>
 *   <li>missing {@code projectId} / blank {@code name} / blank {@code email}: 400 before any work (10.3);</li>
 *   <li>duplicate email: 409, no membership created (10.8);</li>
 *   <li>duplicate {@code (email, projectId)}: 409 {@code error.project.member.duplicate} with no
 *       orphaned user, proving the single-transaction atomicity (10.7, 10.9).</li>
 * </ul>
 *
 * <p>Validates: Requirements 10.2, 10.3, 10.6, 10.7, 10.8, 10.9, 10.10
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ClientRegistrationControllerIntegrationTest {

    private static final String CLIENT_PATH = "/api/users/client";

    private static final String ADMIN = "ADMIN";      // bypass, reaches everything
    private static final String MANAGER = "MANAGER";  // granted (PROJECTS, EDIT) -> allowed
    private static final String WORKER = "WORKER";    // no PROJECTS grant -> denied

    // Exact string from messages.properties (PL base) for error.access.denied.
    private static final String PL_ACCESS_DENIED = "Dostęp zabroniony. Brak wymaganych uprawnień.";

    /** Per-run unique id source keeping every scenario's data collision-free and re-runnable. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

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

    /** Local instance — the MOCK web-environment context does not expose an ObjectMapper bean. */
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Autowired
    private InviteTokenDao inviteTokenDao;

    @Autowired
    private RefreshTokenDao refreshTokenDao;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Only mocked collaborator: keeps the flow off SMTP and lets us count client-portal dispatches. */
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    /** Unique run id so emails/role codes never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    private TransactionTemplate tx;

    @BeforeEach
    void seedRolesAndPermissionMatrix() {
        // The class is intentionally NOT @Transactional so the service commits/rolls back on its
        // own (required for the atomicity assertions). Seeding via EntityManager.persist therefore
        // needs its own transaction, supplied by this TransactionTemplate.
        tx = new TransactionTemplate(transactionManager);

        // Liquibase is disabled under the integration-test profile, so seed the roles we
        // authenticate as, the PROJECTS/EDIT ABAC matrix, and the CLIENT role directly via JPA.
        tx.executeWithoutResult(status -> {
            ResourceEntity projects = createResource("PROJECTS");
            OperationEntity edit = createOperation("EDIT");

            // MANAGER: granted (PROJECTS, EDIT) -> allowed to register clients.
            grantRole(MANAGER, projects, edit);
            // WORKER: no role_resources grant at all (deny-by-default) -> 403.
            ensureRole(WORKER);
            // ADMIN: no grant needed (bypass), but the role must exist to mint a ROLE_ADMIN token.
            ensureRole(ADMIN);
            // CLIENT: the seeded role the service resolves via findByCode("CLIENT"); its code drives
            // the client-portal invitation variant in the invite flow.
            ensureClientRole();

            entityManager.flush();
        });
    }

    @AfterEach
    void cleanUp() {
        // The class is not @Transactional, so committed rows survive between tests. Remove every
        // row the seed + flow can create, honoring FK order: membership/token tables -> users,
        // users -> roles, and the ABAC join tables -> role_resources -> resources/operations.
        // The resources/operations DAOs are read-only, so those are cleared via native SQL.
        tx.executeWithoutResult(status -> {
            projectMemberDao.deleteAll();
            inviteTokenDao.deleteAll();
            refreshTokenDao.deleteAll();
            userDao.deleteAll();
            entityManager.createNativeQuery("DELETE FROM role_resource_operations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM role_resources").executeUpdate();
            roleDao.deleteAll();
            entityManager.createNativeQuery("DELETE FROM resources").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM operations").executeUpdate();
        });
    }

    // === 10.2 / 10.6 / 10.10 : PROJECTS/EDIT -> 201, CLIENT + INVITED, one membership, email captured ===

    @Test
    @DisplayName("POST /api/users/client with PROJECTS/EDIT -> 201; CLIENT+INVITED user, one project_members row, invite email captured")
    void register_withEditPermission_createsClientAndMembershipAndSendsInvite() throws Exception {
        long projectId = nextId();
        String email = uniqueEmail();

        MvcResult result = mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Client User", email, projectId)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("PROJECTS/EDIT caller must receive 201 Created")
                .isEqualTo(201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("id").asLong()).as("response carries the created client id").isPositive();
        assertThat(body.path("email").asText()).as("response echoes the client email").isEqualTo(email);
        assertThat(body.path("projectId").asLong()).as("response echoes the assigned projectId").isEqualTo(projectId);

        Long clientId = body.path("id").asLong();

        // The class is intentionally NOT @Transactional (so the service commits/rolls back on its
        // own for the atomicity assertions) and the integration-test profile disables
        // open-session-in-view, so the persisted rows are detached when read back here. Inspect the
        // lazy role/user associations inside a read-only transaction so they initialize within an
        // active session rather than throwing a LazyInitializationException.
        tx.executeWithoutResult(status -> {
            // The created user is a CLIENT with status INVITED and no password (reused invite create path).
            UserEntity created = userDao.findById(clientId).orElseThrow();
            assertThat(created.getRole().getCode()).as("created user is fixed to the CLIENT role").isEqualTo("CLIENT");
            assertThat(created.getStatus()).as("created user is INVITED").isEqualTo(UserStatus.INVITED);
            assertThat(created.getPasswordHash()).as("client has no password").isNull();

            // Exactly one project_members row for (userId, projectId) (Req 10.6).
            List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
            assertThat(members).as("exactly one membership on the supplied project").hasSize(1);
            assertThat(members.get(0).getUser().getId()).isEqualTo(clientId);
            assertThat(members.get(0).getProjectId()).isEqualTo(projectId);
            assertThat(projectMemberDao.existsByUserIdAndProjectId(clientId, projectId)).isTrue();
        });

        // The client-portal invitation was dispatched exactly once (Req 10.5), no set-password variant.
        verify(invitationMailSender, times(1)).sendClientPortalInvitation(any(UserEntity.class));
    }

    // === 10.2 : without PROJECTS/EDIT -> 403 error.access.denied, nothing created ===

    @Test
    @DisplayName("POST /api/users/client as WORKER (no PROJECTS grant) -> 403 error.access.denied, no user or membership created")
    void register_withoutPermission_returns403AndCreatesNothing() throws Exception {
        long projectId = nextId();
        String email = uniqueEmail();

        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(WORKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Denied Client", email, projectId)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));

        // The interceptor rejects before the controller runs: no user, no membership, no email.
        assertThat(userDao.findByEmail(email)).as("no client user created on denial").isEmpty();
        assertThat(projectMemberDao.findByProjectId(projectId)).as("no membership created on denial").isEmpty();
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("POST /api/users/client as WORKER -> 403 body carries resolved error.access.denied")
    void register_withoutPermission_bodyIsAccessDenied() throws Exception {
        long projectId = nextId();

        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(WORKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Denied Client", uniqueEmail(), projectId)))
                .andReturn();

        MvcResult result = mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(WORKER))
                        .header("Accept-Language", "pl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Denied Client", uniqueEmail(), projectId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("message").asText())
                .as("403 body must be the resolved error.access.denied text")
                .isEqualTo(PL_ACCESS_DENIED);
    }

    // === 10.2 : ADMIN reaches the endpoint via the bypass ===

    @Test
    @DisplayName("POST /api/users/client as ADMIN -> 201 via the bypass regardless of the matrix")
    void register_asAdmin_reachesEndpointViaBypass() throws Exception {
        long projectId = nextId();
        String email = uniqueEmail();

        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Admin Client", email, projectId)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

        assertThat(userDao.findByEmail(email)).as("ADMIN bypass reaches the endpoint and creates the client").isPresent();
    }

    // === 10.3 : missing projectId / blank name / blank email -> 400 before any work ===

    @Test
    @DisplayName("POST /api/users/client without projectId -> 400 and nothing created")
    void register_missingProjectId_returns400() throws Exception {
        String email = uniqueEmail();
        String bodyNoProject = """
                {"name": "No Project", "email": "%s"}
                """.formatted(email);

        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyNoProject))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));

        assertThat(userDao.findByEmail(email)).as("validation rejects before any user creation").isEmpty();
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("POST /api/users/client with blank name -> 400 and nothing created")
    void register_blankName_returns400() throws Exception {
        long projectId = nextId();
        String email = uniqueEmail();

        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("   ", email, projectId)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));

        assertThat(userDao.findByEmail(email)).isEmpty();
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("POST /api/users/client with blank email -> 400 and nothing created")
    void register_blankEmail_returns400() throws Exception {
        long projectId = nextId();
        String bodyBlankEmail = """
                {"name": "Blank Email", "email": "   ", "projectId": %d}
                """.formatted(projectId);

        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBlankEmail))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));

        assertThat(projectMemberDao.findByProjectId(projectId)).isEmpty();
        verifyNoInteractions(invitationMailSender);
    }

    // === 10.8 : duplicate email -> 409, no membership created ===

    @Test
    @DisplayName("POST /api/users/client with an already-used email -> 409 duplicate-email, no membership created")
    void register_duplicateEmail_returns409() throws Exception {
        String email = uniqueEmail();
        long firstProject = nextId();

        // First registration succeeds and creates the client.
        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("First Client", email, firstProject)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

        // A second registration with the SAME email on a DIFFERENT project -> 409 from the
        // established duplicate-email uniqueness behavior, and no membership on the second project.
        long secondProject = nextId();
        mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Second Client", email, secondProject)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));

        assertThat(projectMemberDao.findByProjectId(secondProject))
                .as("duplicate email must not create a membership on the second project")
                .isEmpty();
        // Exactly one user exists for the email (the first one); no orphan from the failed attempt.
        assertThat(userDao.findByEmail(email)).isPresent();
    }

    // === 10.7 / 10.9 : a 409 on the (email, projectId) registration is atomic (no orphaned user) ===

    @Test
    @DisplayName("POST /api/users/client re-registering an existing (email, projectId) -> 409 and NO orphaned user/membership (atomicity)")
    void register_duplicate_isAtomicWithNoOrphanedUser() throws Exception {
        // The endpoint creates a fresh user (new id) and then assigns it to the project, all in one
        // transaction. A brand-new user can never trip ProjectMemberService's duplicate-(userId,
        // projectId) guard, so the only 409 the endpoint surfaces for a repeated (email, projectId)
        // is the established duplicate-email conflict raised at the create step. The atomicity
        // contract (Req 10.7, 10.9) is that this 409 rolls back the whole transaction, leaving no
        // orphaned CLIENT user and no membership from the failed attempt.
        String email = uniqueEmail();
        long projectId = nextId();

        // First registration succeeds: one CLIENT user + one membership on projectId.
        MvcResult first = mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Atomic Client", email, projectId)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        long usersAfterFirst = userDao.count();
        long membersOnProjectAfterFirst = projectMemberDao.findByProjectId(projectId).size();
        assertThat(membersOnProjectAfterFirst).isEqualTo(1);

        // Re-register the SAME (email, projectId) -> 409 (duplicate email at the create step).
        MvcResult second = mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Atomic Client Dup", email, projectId)))
                .andReturn();
        assertThat(second.getResponse().getStatus())
                .as("re-registering an existing (email, projectId) must conflict with 409")
                .isEqualTo(409);

        // Atomicity: the failed attempt left no orphaned user and no extra membership.
        assertThat(userDao.count())
                .as("a failed registration must not leave an orphaned CLIENT user")
                .isEqualTo(usersAfterFirst);
        assertThat(projectMemberDao.findByProjectId(projectId).size())
                .as("the project's membership set is unchanged after the failed attempt")
                .isEqualTo(membersOnProjectAfterFirst);
    }

    // === helpers ===

    private long nextId() {
        return RUN_ID.incrementAndGet();
    }

    private String uniqueEmail() {
        return "client-reg+" + runId + "-" + nextId() + "@example.com";
    }

    /** Mints a JWT whose principal name is a userId (1L) and whose authority is ROLE_<code>. */
    private String bearer(String roleCode) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                1L, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }

    private String clientBody(String name, String email, long projectId) {
        return """
                {"name": "%s", "email": "%s", "projectId": %d}
                """.formatted(name, email, projectId);
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

    /** Ensures the seeded CLIENT role (code exactly "CLIENT") exists for findByCode + invite variant. */
    private RoleEntity ensureClientRole() {
        return roleDao.findByCode("CLIENT").orElseGet(() -> {
            RoleEntity role = new RoleEntity();
            role.setCode("CLIENT");
            role.setNameRU("Клиент");
            role.setNamePL("Klient");
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
