package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.ProjectDao;
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
 * End-to-end integration test for the FOR-04-13 custom project-creation endpoint
 * {@code POST /api/projects} (task 9.1).
 *
 * <p>Boots the full application context with the real Spring Security filter chain and the
 * {@code PermissionInterceptor} against a Testcontainers PostgreSQL instance, mirroring
 * {@link ProjectMemberControllerIntegrationTest} (ABAC seeding + JWT minting) and
 * {@link ClientRegistrationControllerIntegrationTest} (mail-sender mock + per-test clean-up +
 * non-transactional class so the service's own transaction commits or rolls back on its own for the
 * atomicity assertions). Requests hit {@code /api/projects} through {@link MockMvc}, so the
 * {@code @RequiresPermission(PROJECTS, CREATE)} enforcement runs exactly as it would for a real HTTP
 * request.
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the ABAC matrix is seeded directly via the JPA layer per test:
 * the {@code PROJECTS} resource, the CRUD operations, a MANAGER-like role granted
 * {@code (PROJECTS, CREATE)} (allowed), and the seeded {@code CLIENT} role the service resolves via
 * {@code RoleDao.findByCode("CLIENT")}. {@code ProjectMemberService.assign(...)} and the FOR-03-05
 * client-registration flow are invoked in-process from within the custom-create transaction (not via
 * a nested HTTP call), so only {@code PROJECTS}/{@code CREATE} is enforced at the HTTP boundary.
 *
 * <p>The {@link InvitationMailSender} is the only mocked collaborator so the {@code newClient} flow
 * neither reaches SMTP nor requires a mail server, while the client-portal invitation dispatch stays
 * verifiable. The Google Places feature stays disabled (default), and no request carries a
 * {@code googlePlaceId}, so no external Google call is made.
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id combined with a per-test UUID for emails), and {@link #cleanUp()} removes all rows the flow
 * can create (project members, users, projects, roles, ABAC join tables) after each test, honoring
 * FK order, so the suite re-runs without manual DB clean-up.
 *
 * <p>Covered acceptance criteria:
 * <ul>
 *   <li>7.1 existing-client create: {@code POST /api/projects} with members + an existing CLIENT
 *       user attaches the client under the CLIENT role; the project and all memberships persist;</li>
 *   <li>7.2 newClient: creates an INVITED CLIENT user, dispatches the client-portal invitation, and
 *       assigns the CLIENT membership;</li>
 *   <li>7.3 failure rolls back: a member referencing an unknown user makes the whole transaction roll
 *       back so no project, no {@code project_members} row, and no client user remains;</li>
 *   <li>7.4 generic create rejected: the bulk/generic create path is refused with 400
 *       {@code error.project.create.unsupported} and nothing persists.</li>
 * </ul>
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectCustomCreateIT {

    private static final String PROJECTS_PATH = "/api/projects";
    private static final String BULK_PATH = "/api/projects/bulk";

    private static final String ADMIN = "ADMIN";      // bypass, reaches everything
    private static final String MANAGER = "MANAGER";  // granted (PROJECTS, CREATE) -> allowed

    // Exact string from messages.properties (PL base) for error.project.create.unsupported.
    private static final String PL_CREATE_UNSUPPORTED =
            "Ogólne tworzenie projektu nie jest obsługiwane. Użyj dedykowanego punktu końcowego tworzenia projektu.";

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
    private ProjectDao projectDao;

    @Autowired
    private ProjectMemberDao projectMemberDao;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Only mocked collaborator: keeps the newClient flow off SMTP and lets us count dispatches. */
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    /** Unique run id so emails never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    private TransactionTemplate tx;

    /** The seeded CLIENT project role the service resolves; captured for id/assertions. */
    private RoleEntity clientRole;
    /** A generic role used as a team member's project role (FK satisfied). */
    private RoleEntity memberRole;

    @BeforeEach
    void seedRolesAndPermissionMatrix() {
        // The class is intentionally NOT @Transactional so the custom-create service commits/rolls
        // back on its own (required for the 7.3 atomicity assertion). Seeding via
        // EntityManager.persist therefore needs its own transaction, supplied by this template.
        tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            ResourceEntity projects = createResource("PROJECTS");
            OperationEntity create = createOperation("CREATE");
            createOperation("READ");
            createOperation("UPDATE");
            createOperation("DELETE");

            // MANAGER: granted (PROJECTS, CREATE) -> reaches the custom-create endpoint.
            grantRole(MANAGER, projects, create);
            // ADMIN: no grant needed (bypass), but the role must exist to mint a ROLE_ADMIN token.
            ensureRole(ADMIN);
            // CLIENT: the seeded role the service resolves via findByCode("CLIENT").
            clientRole = ensureClientRole();
            // A member role for team assignments (any existing role satisfies the FK).
            memberRole = ensureRole("FOREMAN");

            entityManager.flush();
        });
    }

    @AfterEach
    void cleanUp() {
        // The class is not @Transactional, so committed rows survive between tests. Remove every
        // row the seed + flow can create, honoring FK order.
        tx.executeWithoutResult(status -> {
            projectMemberDao.deleteAll();
            projectDao.deleteAll();
            // Child tables that reference users must be cleared before deleting users, otherwise the
            // FK from these token tables (e.g. invite_tokens created by the newClient flow) blocks
            // userDao.deleteAll(). All four token tables exist under the create-drop JPA schema.
            entityManager.createNativeQuery("DELETE FROM invite_tokens").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM otp_tokens").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM refresh_tokens").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM password_reset_tokens").executeUpdate();
            userDao.deleteAll();
            entityManager.createNativeQuery("DELETE FROM role_resource_operations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM role_resources").executeUpdate();
            roleDao.deleteAll();
            entityManager.createNativeQuery("DELETE FROM resources").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM operations").executeUpdate();
        });
    }

    // === 7.1 : existing-client create -> project + members + existing CLIENT attached under CLIENT ===

    @Test
    @DisplayName("POST /api/projects with members + existingClientUserId -> 201; project persisted, team + existing client attached")
    void create_withExistingClient_persistsProjectMembersAndClient() throws Exception {
        UserEntity teamMember = createUser("Team Member", "member");
        UserEntity existingClient = createClientUser("Existing Client", "existing-client");
        String projectName = "Existing Client Project " + nextId();

        String body = """
                {
                  "name": "%s",
                  "members": [ {"userId": %d, "projectRoleId": %d} ],
                  "client": {"existingClientUserId": %d}
                }
                """.formatted(projectName, teamMember.getId(), memberRole.getId(), existingClient.getId());

        MvcResult result = mockMvc.perform(post(PROJECTS_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("PROJECTS/CREATE caller must receive 201 Created")
                .isEqualTo(201);

        JsonNode responseBody = objectMapper.readTree(result.getResponse().getContentAsString());
        long projectId = responseBody.path("id").asLong();
        assertThat(projectId).as("response carries the generated project id").isPositive();
        assertThat(responseBody.path("name").asText()).isEqualTo(projectName);
        assertThat(responseBody.path("status").asText())
                .as("status defaults to DRAFT when omitted").isEqualTo("DRAFT");
        assertThat(responseBody.path("client").path("roleCode").asText())
                .as("derived client is assigned under the CLIENT role").isEqualTo("CLIENT");

        // The project and both memberships (team member + existing client) are persisted.
        tx.executeWithoutResult(status -> {
            assertThat(projectDao.findById(projectId)).as("project persisted").isPresent();

            List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
            assertThat(members).as("two memberships: team member + client").hasSize(2);

            ProjectMemberEntity clientMembership = members.stream()
                    .filter(m -> "CLIENT".equals(m.getProjectRole().getCode()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a CLIENT membership"));
            assertThat(clientMembership.getUser().getId())
                    .as("existing client attached under the CLIENT role")
                    .isEqualTo(existingClient.getId());

            assertThat(projectMemberDao.existsByUserIdAndProjectId(teamMember.getId(), projectId))
                    .as("team member attached").isTrue();
        });

        // Attaching an EXISTING client does not dispatch a client-portal invitation.
        verifyNoInteractions(invitationMailSender);
    }

    // === 7.2 : newClient -> INVITED CLIENT user + invite dispatched + CLIENT assignment ===

    @Test
    @DisplayName("POST /api/projects with newClient -> 201; INVITED CLIENT user created, invite dispatched, membership assigned")
    void create_withNewClient_createsInvitedClientAndDispatchesInvite() throws Exception {
        String projectName = "New Client Project " + nextId();
        String clientEmail = uniqueEmail();

        String body = """
                {
                  "name": "%s",
                  "members": [],
                  "client": {"newClient": {"name": "New Client", "email": "%s"}}
                }
                """.formatted(projectName, clientEmail);

        MvcResult result = mockMvc.perform(post(PROJECTS_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("newClient custom create must return 201 Created").isEqualTo(201);

        JsonNode responseBody = objectMapper.readTree(result.getResponse().getContentAsString());
        long projectId = responseBody.path("id").asLong();
        assertThat(projectId).isPositive();

        tx.executeWithoutResult(status -> {
            assertThat(projectDao.findById(projectId)).as("project persisted").isPresent();

            // The created client is a CLIENT with status INVITED and no password.
            UserEntity created = userDao.findByEmail(clientEmail).orElseThrow(
                    () -> new AssertionError("expected the new CLIENT user to be created"));
            assertThat(created.getRole().getCode()).as("new client is fixed to the CLIENT role").isEqualTo("CLIENT");
            assertThat(created.getStatus()).as("new client is INVITED").isEqualTo(UserStatus.INVITED);
            assertThat(created.getPasswordHash()).as("new client has no password").isNull();

            // Exactly one project_members row: the CLIENT membership on the new project.
            List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
            assertThat(members).as("one membership: the new client under CLIENT").hasSize(1);
            assertThat(members.get(0).getUser().getId()).isEqualTo(created.getId());
            assertThat(members.get(0).getProjectRole().getCode()).isEqualTo("CLIENT");
        });

        // The client-portal invitation was dispatched exactly once (reused FOR-03-05 flow).
        verify(invitationMailSender, times(1)).sendClientPortalInvitation(any(UserEntity.class));
    }

    // === 7.3 : failure rolls back -> no project, no membership, no client persisted ===

    @Test
    @DisplayName("POST /api/projects with a member referencing an unknown user -> failure rolls back the whole transaction")
    void create_failingMember_rollsBackEverything() throws Exception {
        long projectsBefore = projectDao.count();
        long usersBefore = userDao.count();
        long membersBefore = projectMemberDao.count();

        long unknownUserId = 9_000_000_000L + nextId();
        String clientEmail = uniqueEmail();
        String projectName = "Rollback Project " + nextId();

        // A newClient block precedes/accompanies a failing member so we can prove no client user is
        // left behind either. The unknown member userId makes ProjectMemberService.assign throw 404,
        // rolling back the project, every membership, AND the just-created client.
        String body = """
                {
                  "name": "%s",
                  "members": [ {"userId": %d, "projectRoleId": %d} ],
                  "client": {"newClient": {"name": "Rollback Client", "email": "%s"}}
                }
                """.formatted(projectName, unknownUserId, memberRole.getId(), clientEmail);

        MvcResult result = mockMvc.perform(post(PROJECTS_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unknown member surfaces a client-error (404)").isEqualTo(404);

        // Atomicity: nothing from the failed attempt survives.
        assertThat(projectDao.count()).as("no project persisted on rollback").isEqualTo(projectsBefore);
        assertThat(projectMemberDao.count()).as("no membership persisted on rollback").isEqualTo(membersBefore);
        assertThat(userDao.count()).as("no client user persisted on rollback").isEqualTo(usersBefore);
        assertThat(userDao.findByEmail(clientEmail))
                .as("the would-be client user is rolled back with the transaction").isEmpty();
        assertThat(projectDao.findAll())
                .as("no project with the attempted name exists")
                .noneMatch(p -> projectName.equals(p.getName()));
    }

    // === 7.4 : generic (bulk) create rejected -> 400 error.project.create.unsupported, nothing persisted ===

    @Test
    @DisplayName("POST /api/projects/bulk (generic create path) -> 400 error.project.create.unsupported and nothing persists")
    void bulkCreate_isRejectedAndPersistsNothing() throws Exception {
        long projectsBefore = projectDao.count();
        String projectName = "Bulk Rejected " + nextId();

        String body = """
                [ {"name": "%s"} ]
                """.formatted(projectName);

        MvcResult result = mockMvc.perform(post(BULK_PATH)
                        .header("Authorization", bearer(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("generic bulk create is disabled at the service layer -> 400").isEqualTo(400);

        JsonNode responseBody = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(responseBody.path("status").asInt()).isEqualTo(400);
        assertThat(responseBody.path("message").asText())
                .as("the 400 body carries the resolved error.project.create.unsupported message")
                .isEqualTo(PL_CREATE_UNSUPPORTED);

        assertThat(projectDao.count())
                .as("generic create must persist no project").isEqualTo(projectsBefore);
        assertThat(projectDao.findAll())
                .as("no project from the rejected bulk create exists")
                .noneMatch(p -> projectName.equals(p.getName()));
    }

    // === helpers ===

    private long nextId() {
        return RUN_ID.incrementAndGet();
    }

    private String uniqueEmail() {
        return "project-create+" + runId + "-" + nextId() + "@example.com";
    }

    /** Mints a JWT whose principal name is a userId (1L) and whose authority is ROLE_<code>. */
    private String bearer(String roleCode) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                1L, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }

    /** Persists an ACTIVE user under the generic member role with a unique email (repeatable). */
    private UserEntity createUser(String name, String prefix) {
        return tx.execute(status -> {
            UserEntity user = new UserEntity();
            user.setName(name + " " + nextId());
            user.setEmail(prefix + "+" + runId + "-" + nextId() + "@example.com");
            user.setRole(memberRole);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale("ru");
            return userDao.save(user);
        });
    }

    /** Persists an existing CLIENT user (company role CLIENT) for the existing-client scenario. */
    private UserEntity createClientUser(String name, String prefix) {
        return tx.execute(status -> {
            UserEntity user = new UserEntity();
            user.setName(name + " " + nextId());
            user.setEmail(prefix + "+" + runId + "-" + nextId() + "@example.com");
            user.setRole(clientRole);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale("ru");
            return userDao.save(user);
        });
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
