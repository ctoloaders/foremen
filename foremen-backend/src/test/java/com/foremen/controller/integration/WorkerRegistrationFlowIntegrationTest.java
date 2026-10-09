package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.ProjectAccessCache;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end integration test for the FOR-05-09 registration / worker flows (task 14.4) against the
 * full application stack: the Spring Security filter chain, the {@code PermissionInterceptor}, the
 * real service / DAO layer, and a Testcontainers PostgreSQL schema.
 *
 * <p>Mirrors {@link ClientRegistrationControllerIntegrationTest} (permission seeding + JWT minting +
 * {@code InvitationMailSender} mock + per-test clean-up): requests hit the real endpoints through
 * {@link MockMvc}, so {@code @RequiresPermission(PROJECTS, EDIT)} and the programmatic
 * {@code PROJECT_MEMBERS CREATE} half of the dual guard run exactly as for a real HTTP request. The
 * class is intentionally NOT {@code @Transactional} so each flow's own transaction commits or rolls
 * back on its own, which the atomicity (rollback) and {@code AFTER_COMMIT} email / cache assertions
 * require.
 *
 * <p>The {@code integration-test} profile disables Liquibase and builds the schema from JPA DDL
 * ({@code ddl-auto=create-drop}), so the ABAC matrix is seeded via the JPA layer per test: the
 * {@code PROJECTS/EDIT} and {@code PROJECT_MEMBERS/CREATE} grants on a MANAGER-like role (allowed), a
 * WORKER-like role with no grant (denied), the {@code ADMIN} and {@code CLIENT} roles the flows
 * resolve by code, plus one Active_Worker_Type for the "with worker type" scenarios.
 *
 * <p><b>Caller choice.</b> The worker-record / worker-invitation flows run their own project-scope
 * check (FOR-03-04), so the happy paths authenticate as {@code ADMIN}: the ABAC + project-scope
 * bypass lets the flow reach the service without pre-seeding a {@code projects} row or a caller
 * membership. The 403 case authenticates as the no-grant WORKER role to prove the guard still fires.
 *
 * <p>The {@link InvitationMailSender} is the only mocked collaborator so the flows neither reach
 * SMTP nor require a mail server, while the client-portal OTP dispatch (CLIENT) and the staff
 * password-set dispatch (WORKER invite) stay independently verifiable.
 *
 * <p>Repeatability: every persisted row uses a per-run unique suffix (a static {@link AtomicLong}
 * run-id combined with a per-test UUID for emails / role codes), and {@link #cleanUp()} removes
 * every row the flows can create after each test, so the suite re-runs without manual DB clean-up.
 *
 * <p>Covered:
 * <ul>
 *   <li>Client invitation (Req 12.1): 201, one CLIENT+INVITED user, one {@code project_members} row,
 *       exactly one client-portal OTP email, no staff password-set email.</li>
 *   <li>Worker_Record_Flow (Req 13.1, 13.7): PERSON and COMPANY (NIP + contact person stored), with
 *       and without a worker type, and the duplicate-email 409 with full rollback.</li>
 *   <li>Worker_Invitation_Flow (Req 13.17, 13.18): invite, re-send (token rotation + second email),
 *       already-active 409, and the 403 for a caller without the grant.</li>
 *   <li>ProjectAccessCache propagation (Req 16.1): the newly created worker's allowed-project set
 *       reflects the membership assigned by the record flow.</li>
 * </ul>
 *
 * <p><b>Discovered bug (not fixed here — a test-task deliverable).</b> The {@code Worker_Record_Flow}
 * is specified to accept an uninvited worker record <em>without</em> an email (Req 13.1 / 13.2 —
 * email is optional for an uninvited worker). But {@code users.email} is {@code NOT NULL} in both the
 * {@code UserEntity} mapping ({@code @Column(nullable = false, unique = true)}) and the Liquibase
 * schema (changeset {@code 008-create-users.xml}), so a no-email {@code POST /api/users/worker}
 * fails the NOT NULL constraint and is mis-reported as 409 {@code error.user.email.already.exists}
 * ("Adres email 'null' jest już używany") with a full rollback. The two scenarios that need a
 * no-email worker record — {@code workerRecord_noEmail_createsWorkerWithEmptyEmail} and
 * {@code workerInvite_noEmail_returns400EmailRequired} — are therefore {@code @Disabled} with a
 * reason pointing at this gap; making {@code users.email} nullable belongs to the worker-record
 * implementation (task 14.2 / its changeset), not to this test task. Every other scenario supplies
 * a stored email so the row satisfies the current schema.
 *
 * <p>Validates: Requirements 12.1, 13.1, 13.7, 13.17, 13.18, 16.1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class WorkerRegistrationFlowIntegrationTest {

    private static final String CLIENT_PATH = "/api/users/client";
    private static final String WORKER_PATH = "/api/users/worker";

    private static final String ADMIN = "ADMIN";      // bypass, reaches everything
    private static final String MANAGER = "MANAGER";  // granted (PROJECTS, EDIT) + (PROJECT_MEMBERS, CREATE)
    private static final String WORKER = "WORKER";    // no grant -> denied

    // Exact string from messages.properties (PL base) for error.access.denied.
    private static final String PL_ACCESS_DENIED = "Dostęp zabroniony. Brak wymaganych uprawnień.";

    // A valid Polish NIP (checksum verified: weights 6,5,7,2,3,4,5,6,7 mod 11 == 6, the 10th digit).
    private static final String VALID_NIP = "5260001246";

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
    private ProjectDao projectDao;

    @Autowired
    private InviteTokenDao inviteTokenDao;

    @Autowired
    private RefreshTokenDao refreshTokenDao;

    @Autowired
    private WorkerTypeDao workerTypeDao;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Only mocked collaborator: keeps the flows off SMTP and lets us count each email variant. */
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    /** Unique run id so emails/role codes never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    /** An Active_Worker_Type id seeded for the "with worker type" scenarios. */
    private Long activeWorkerTypeId;

    private TransactionTemplate tx;

    @BeforeEach
    void seedRolesAndPermissionMatrix() {
        // NOT @Transactional (the flows commit/roll back on their own), so seed via a TransactionTemplate.
        tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            ResourceEntity projects = createResource("PROJECTS");
            ResourceEntity projectMembers = createResource("PROJECT_MEMBERS");
            OperationEntity edit = createOperation("EDIT");
            OperationEntity create = createOperation("CREATE");

            // MANAGER: both halves of the dual guard — PROJECTS/EDIT (handler) + PROJECT_MEMBERS/CREATE (service).
            RoleEntity manager = ensureRole(MANAGER);
            grant(manager, projects, edit);
            grant(manager, projectMembers, create);

            // WORKER: no grant at all (deny-by-default) -> 403.
            ensureRole(WORKER);
            // ADMIN: no grant needed (bypass), but the role must exist to mint a ROLE_ADMIN token.
            ensureRole(ADMIN);
            // CLIENT + WORKER roles the flows resolve via findByCode (WORKER already ensured above).
            ensureRole("CLIENT");

            // One Active_Worker_Type for the "with worker type" worker-record scenario.
            WorkerTypeEntity type = new WorkerTypeEntity();
            type.setCode("TYPE_" + runId);
            type.setNameRU("Тип " + runId);
            type.setNamePL("Typ " + runId);
            type.setTierPct(new BigDecimal("0.1000"));
            type.setBase(false);
            type.setOrderNo(1);
            type.setActive(true);
            activeWorkerTypeId = workerTypeDao.save(type).getId();

            entityManager.flush();
        });
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(status -> {
            projectMemberDao.deleteAll();
            inviteTokenDao.deleteAll();
            refreshTokenDao.deleteAll();
            userDao.deleteAll();
            projectDao.deleteAll();
            workerTypeDao.deleteAll();
            entityManager.createNativeQuery("DELETE FROM role_resource_operations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM role_resources").executeUpdate();
            roleDao.deleteAll();
            entityManager.createNativeQuery("DELETE FROM resources").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM operations").executeUpdate();
        });
    }

    // ================================================================================
    // Client invitation (Requirement 12.1): one CLIENT+INVITED user + one membership +
    // exactly one client-portal OTP email, no staff password-set email.
    // ================================================================================

    @Test
    @DisplayName("POST /api/users/client -> 201; one CLIENT+INVITED user, one membership, one OTP email, no set-password email")
    void clientInvitation_createsClientMembershipAndSendsOneOtpEmail() throws Exception {
        long projectId = createProject();
        String email = uniqueEmail("client");

        // ADMIN bypasses both the ABAC matrix and the FOR-03-04 project-scope check that
        // ClientRegistrationService -> ProjectMemberService.assign now runs (task 5.1), so the flow
        // reaches the service without pre-seeding a projects row or a caller membership.
        MvcResult result = mockMvc.perform(post(CLIENT_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Co Owner", email, projectId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("client invitation must return 201").isEqualTo(201);
        Long clientId = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();

        tx.executeWithoutResult(status -> {
            UserEntity created = userDao.findById(clientId).orElseThrow();
            assertThat(created.getRole().getCode()).as("created user is CLIENT").isEqualTo("CLIENT");
            assertThat(created.getStatus()).as("created user is INVITED").isEqualTo(UserStatus.INVITED);
            assertThat(created.getPasswordHash()).as("client has no password").isNull();

            List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
            assertThat(members).as("exactly one membership on the project").hasSize(1);
            assertThat(members.get(0).getUser().getId()).isEqualTo(clientId);
        });

        // Exactly one client-portal OTP email; the staff password-set email is never used (Req 12.1).
        verify(invitationMailSender, times(1)).sendClientPortalInvitation(any(UserEntity.class));
        verify(invitationMailSender, times(0)).sendSetPasswordInvitation(any(UserEntity.class), anyString());
    }

    // ================================================================================
    // Worker_Record_Flow (Requirement 13.1): PERSON / COMPANY, with/without worker type,
    // with/without email, NIP; and the duplicate-email 409 with rollback (Requirement 13.7).
    // ================================================================================

    @Test
    @DisplayName("POST /api/users/worker PERSON with email + worker type -> 201; uninvited WORKER user + ACTIVE membership, no email")
    void workerRecord_personWithEmailAndWorkerType_createsUninvitedWorkerAndMembership() throws Exception {
        long projectId = createProject();
        String email = uniqueEmail("worker-person");

        String body = """
                {"workerKind":"PERSON","name":"Jan Kowalski","email":"%s","workerTypeId":%d,"projectId":%d}
                """.formatted(email, activeWorkerTypeId, projectId);

        MvcResult result = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("worker-record must return 201; body=%s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long workerId = json.path("userId").asLong();
        assertThat(json.path("email").asText()).isEqualTo(email);
        assertThat(json.path("projectId").asLong()).isEqualTo(projectId);
        assertThat(json.path("membershipId").asLong()).isPositive();

        tx.executeWithoutResult(status -> {
            UserEntity worker = userDao.findById(workerId).orElseThrow();
            assertThat(worker.getRole().getCode()).as("role fixed to WORKER server-side").isEqualTo("WORKER");
            assertThat(worker.getWorkerKind()).isEqualTo(WorkerKind.PERSON);
            assertThat(worker.isActive()).as("worker record is active").isTrue();
            assertThat(worker.getPasswordHash()).as("uninvited worker has no password").isNull();

            ProjectMemberEntity membership = projectMemberDao.findByUserIdAndProjectId(workerId, projectId).orElseThrow();
            assertThat(membership.getProjectRole().getCode()).isEqualTo("WORKER");
            assertThat(membership.getWorkerType()).as("submitted worker type attached").isNotNull();
            assertThat(membership.getWorkerType().getId()).isEqualTo(activeWorkerTypeId);
        });

        // The record flow sends NO email (D7, Req 13.1).
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("POST /api/users/worker COMPANY with NIP + email, no worker type -> 201; NIP + contact stored, Uncategorized_Worker")
    void workerRecord_companyWithNipAndEmailNoType_createsUncategorizedWorker() throws Exception {
        long projectId = createProject();
        String email = uniqueEmail("worker-company");

        String body = """
                {"workerKind":"COMPANY","name":"ACME Sp. z o.o.","contactPerson":"Anna Nowak","nip":"%s","email":"%s","projectId":%d}
                """.formatted(VALID_NIP, email, projectId);

        MvcResult result = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("company worker-record must return 201; body=%s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long workerId = json.path("userId").asLong();

        tx.executeWithoutResult(status -> {
            UserEntity worker = userDao.findById(workerId).orElseThrow();
            assertThat(worker.getWorkerKind()).isEqualTo(WorkerKind.COMPANY);
            assertThat(worker.getContactPerson()).isEqualTo("Anna Nowak");
            assertThat(worker.getNip()).as("normalized NIP stored").isEqualTo(VALID_NIP);

            ProjectMemberEntity membership = projectMemberDao.findByUserIdAndProjectId(workerId, projectId).orElseThrow();
            assertThat(membership.getProjectRole().getCode()).isEqualTo("WORKER");
            assertThat(membership.getWorkerType()).as("Uncategorized_Worker has no worker type").isNull();
        });

        // The record flow sends NO email (D7, Req 13.1).
        verifyNoInteractions(invitationMailSender);
    }

    @org.junit.jupiter.api.Disabled("""
            Blocked by a schema bug discovered by this test (NOT task 14.4): the Worker_Record_Flow \
            allows an uninvited worker record WITHOUT an email (Req 13.1/13.2 — email is optional for \
            an uninvited worker), but users.email is NOT NULL in both the UserEntity mapping \
            (@Column(nullable=false,unique=true)) and the Liquibase schema (changeset 008). A no-email \
            POST /api/users/worker therefore fails the NOT NULL constraint and is mis-reported as 409 \
            error.user.email.already.exists ("Adres email 'null' jest już używany") with a full \
            rollback. Making users.email nullable belongs to the worker-record implementation \
            (task 14.2 / changeset 149), not to the test task. Re-enable once email is nullable.""")
    @Test
    @DisplayName("POST /api/users/worker with NO email -> 201; stored email empty (BLOCKED: users.email NOT NULL)")
    void workerRecord_noEmail_createsWorkerWithEmptyEmail() throws Exception {
        long projectId = createProject();

        String body = """
                {"workerKind":"COMPANY","name":"ACME Sp. z o.o.","contactPerson":"Anna Nowak","nip":"%s","projectId":%d}
                """.formatted(VALID_NIP, projectId);

        MvcResult result = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        // No email supplied -> response echoes an empty string (not null) per Req 13.1.
        assertThat(json.path("email").asText()).as("empty stored email when none supplied").isEmpty();

        tx.executeWithoutResult(status -> {
            UserEntity worker = userDao.findById(json.path("userId").asLong()).orElseThrow();
            assertThat(worker.getEmail()).as("no email stored").isNull();
        });
    }

    @Test
    @DisplayName("POST /api/users/worker with a duplicate email -> 409 and full rollback (no user, no membership)")
    void workerRecord_duplicateEmail_returns409AndRollsBack() throws Exception {
        long firstProject = createProject();
        String email = uniqueEmail("worker-dup");

        // First record succeeds.
        MvcResult first = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerKind":"PERSON","name":"First Worker","email":"%s","projectId":%d}
                                """.formatted(email, firstProject)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        long usersAfterFirst = userDao.count();

        // Second record with the SAME email (case-insensitive) on a DIFFERENT project -> 409, rolled back.
        long secondProject = createProject();
        MvcResult second = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerKind":"PERSON","name":"Second Worker","email":"%s","projectId":%d}
                                """.formatted(email.toUpperCase(), secondProject)))
                .andReturn();

        assertThat(second.getResponse().getStatus())
                .as("a duplicate email (case-insensitive) must conflict with 409").isEqualTo(409);

        // Atomicity (Req 13.7, 13.12): no orphaned user and no membership from the failed attempt.
        assertThat(userDao.count())
                .as("a rejected worker-record leaves no orphaned user").isEqualTo(usersAfterFirst);
        assertThat(projectMemberDao.findByProjectId(secondProject))
                .as("no membership on the second project after the rejection").isEmpty();
    }

    @Test
    @DisplayName("POST /api/users/worker as WORKER (no grant) -> 403 error.access.denied, nothing created")
    void workerRecord_withoutGrant_returns403() throws Exception {
        long projectId = createProject();
        String email = uniqueEmail("worker-denied");

        MvcResult result = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(WORKER))
                        .header("Accept-Language", "pl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerKind":"PERSON","name":"Denied Worker","email":"%s","projectId":%d}
                                """.formatted(email, projectId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("message").asText())
                .isEqualTo(PL_ACCESS_DENIED);
        assertThat(userDao.findByEmail(email)).as("no user created on denial").isEmpty();
        verifyNoInteractions(invitationMailSender);
    }

    // ================================================================================
    // ProjectAccessCache propagation (Requirement 16.1): the new worker's allowed-project
    // set reflects the membership assigned by the record flow.
    // ================================================================================

    @Test
    @DisplayName("Worker_Record_Flow assigns a membership whose project is in the worker's ProjectAccessCache set")
    void workerRecord_propagatesToProjectAccessCache() throws Exception {
        long projectId = createProject();

        // The record flow assigns the worker a WORKER membership on the project; the worker is given
        // a stored email so the row satisfies the users.email NOT NULL constraint (see class note).
        String email = uniqueEmail("worker-cache");
        MvcResult result = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerKind":"PERSON","name":"Cache Worker","email":"%s","projectId":%d}
                                """.formatted(email, projectId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long workerId = objectMapper.readTree(result.getResponse().getContentAsString()).path("userId").asLong();

        // The assign invalidated the (empty) entry; the next read self-loads the committed membership
        // so the worker's allowed-project set now contains the project (Req 16.1).
        assertThat(projectAccessCache.get(workerId))
                .as("the newly assigned project is in the worker's allowed-project set")
                .contains(projectId);
    }

    // ================================================================================
    // Worker_Invitation_Flow (Requirements 13.17 / 13.18): invite, re-send (rotation + 2nd
    // email), email-required 400, already-active 409, and 403 for a caller without the grant.
    // ================================================================================

    @Test
    @DisplayName("POST /api/users/worker/{id}/invite for an uninvited WORKER with an email -> 200 + one staff password-set email")
    void workerInvite_withEmail_sendsStaffPasswordSetEmail() throws Exception {
        long projectId = createProject();
        long workerId = createWorkerRecord(uniqueEmail("worker-invite"), projectId);

        MvcResult invite = mockMvc.perform(post(WORKER_PATH + "/" + workerId + "/invite")
                        .header("Authorization", bearer(ADMIN)))
                .andReturn();

        assertThat(invite.getResponse().getStatus()).as("invite must return 200").isEqualTo(200);
        assertThat(objectMapper.readTree(invite.getResponse().getContentAsString()).path("status").asText())
                .isEqualTo(UserStatus.INVITED.name());

        // A fresh password-set link is persisted and exactly one staff email is dispatched (Req 13.17).
        assertThat(inviteTokenDao.findByUserIdAndUsedFalse(workerId))
                .as("one unused password-set token issued").hasSize(1);
        verify(invitationMailSender, times(1)).sendSetPasswordInvitation(any(UserEntity.class), anyString());
        verify(invitationMailSender, times(0)).sendClientPortalInvitation(any(UserEntity.class));
    }

    @Test
    @DisplayName("POST .../invite twice -> re-send rotates the token (old used, new unused) and dispatches a second email")
    void workerInvite_resend_rotatesTokenAndSendsSecondEmail() throws Exception {
        long projectId = createProject();
        long workerId = createWorkerRecord(uniqueEmail("worker-resend"), projectId);

        mockMvc.perform(post(WORKER_PATH + "/" + workerId + "/invite").header("Authorization", bearer(ADMIN)))
                .andReturn();
        String firstToken = inviteTokenDao.findByUserIdAndUsedFalse(workerId).get(0).getToken();

        mockMvc.perform(post(WORKER_PATH + "/" + workerId + "/invite").header("Authorization", bearer(ADMIN)))
                .andReturn();

        // Token rotation (Req 13.17): the first token is now used, exactly one fresh unused token remains.
        assertThat(inviteTokenDao.findByToken(firstToken).orElseThrow().isUsed())
                .as("the earlier unused link is invalidated on re-send").isTrue();
        List<com.foremen.dao.model.InviteTokenEntity> unusedNow = inviteTokenDao.findByUserIdAndUsedFalse(workerId);
        assertThat(unusedNow).as("exactly one fresh unused token after re-send").hasSize(1);
        assertThat(unusedNow.get(0).getToken()).as("the fresh token differs from the rotated-out one").isNotEqualTo(firstToken);

        verify(invitationMailSender, times(2)).sendSetPasswordInvitation(any(UserEntity.class), anyString());
    }

    @org.junit.jupiter.api.Disabled("""
            Blocked by the same users.email NOT NULL schema bug (see \
            workerRecord_noEmail_createsWorkerWithEmptyEmail): the email-required invite path \
            (Req 13.18 -> error.worker.email.required) needs a persisted WORKER record WITHOUT a \
            stored email, but a no-email worker record cannot be created while users.email is NOT NULL. \
            Re-enable once email is nullable.""")
    @Test
    @DisplayName("POST .../invite for a WORKER with no stored email -> 400 error.worker.email.required (BLOCKED)")
    void workerInvite_noEmail_returns400EmailRequired() throws Exception {
        long projectId = createProject();
        long workerId = createWorkerRecord(null, projectId); // no email supplied

        MvcResult result = mockMvc.perform(post(WORKER_PATH + "/" + workerId + "/invite")
                        .header("Authorization", bearer(ADMIN)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("email-required must be 400").isEqualTo(400);
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("POST .../invite for an already-activated WORKER -> 409 error.worker.already.active, no email")
    void workerInvite_alreadyActive_returns409() throws Exception {
        long projectId = createProject();
        long workerId = createWorkerRecord(uniqueEmail("worker-active"), projectId);

        // Simulate activation: the worker has set a password and reached ACTIVE.
        tx.executeWithoutResult(status -> {
            UserEntity worker = userDao.findById(workerId).orElseThrow();
            worker.setPasswordHash("$2a$10$deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbe");
            worker.setStatus(UserStatus.ACTIVE);
            userDao.save(worker);
        });

        MvcResult result = mockMvc.perform(post(WORKER_PATH + "/" + workerId + "/invite")
                        .header("Authorization", bearer(ADMIN)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("already-active must be 409").isEqualTo(409);
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("POST .../invite as WORKER (no grant) -> 403 error.access.denied, no email")
    void workerInvite_withoutGrant_returns403() throws Exception {
        long projectId = createProject();
        long workerId = createWorkerRecord(uniqueEmail("worker-invite-denied"), projectId);

        MvcResult result = mockMvc.perform(post(WORKER_PATH + "/" + workerId + "/invite")
                        .header("Authorization", bearer(WORKER))
                        .header("Accept-Language", "pl"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("message").asText())
                .isEqualTo(PL_ACCESS_DENIED);
        verifyNoInteractions(invitationMailSender);
    }

    // =============================== helpers ===============================

    /**
     * Creates a real {@code projects} row (status DRAFT = an Editable_Status) and returns its
     * generated id. A row is required because the {@code integration-test} profile builds the schema
     * from JPA ({@code create-drop}), where {@code ProjectEntity.members} generates a
     * {@code project_members.project_id -> projects.id} FK; assigning a membership to a non-existent
     * project id would otherwise fail that FK.
     */
    private long createProject() {
        return tx.execute(status -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("Project " + runId + "-" + nextId());
            project.setStatus(ProjectStatus.DRAFT);
            return projectDao.save(project).getId();
        });
    }

    /** Creates an uninvited WORKER record via the real flow and returns the new user id. */
    private long createWorkerRecord(String email, long projectId) throws Exception {
        String emailField = email == null ? "" : "\"email\":\"" + email + "\",";
        String body = """
                {"workerKind":"PERSON","name":"Record Worker",%s"projectId":%d}
                """.formatted(emailField, projectId);

        MvcResult result = mockMvc.perform(post(WORKER_PATH)
                        .header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("worker-record setup must return 201").isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("userId").asLong();
    }

    private long nextId() {
        return RUN_ID.incrementAndGet();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "+" + runId + "-" + nextId() + "@example.com";
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

    private void grant(RoleEntity role, ResourceEntity resource, OperationEntity... operations) {
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
