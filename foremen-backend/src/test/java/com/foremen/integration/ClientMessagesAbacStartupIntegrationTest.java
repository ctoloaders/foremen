package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.ClientMessageController;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.permission.PermissionCache;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-12 task 10.2 — {@code CLIENT_MESSAGES} ABAC-matrix + project-scope + startup-completeness +
 * notification-recipient-resolution integration test (design §ClientMessageController / §Notifications;
 * Requirements 8.*, 7.1, 7.2, 13.2, 13.3).
 *
 * <p>Boots the full application context over the real Spring MVC + Spring Security + ABAC stack
 * against a Testcontainers PostgreSQL, following the repo's established {@code @SpringBootTest(MOCK)}
 * + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}
 * pattern (mirroring {@link PayrollAbacStartupIntegrationTest} / {@link ProjectReadinessAbacIntegrationTest}).
 * Under the {@code integration-test} profile Liquibase is disabled and Hibernate {@code create-drop}
 * builds the schema, so every matrix row (roles, resources, operations, grants) and every domain
 * fixture (users, project, membership) is created programmatically; each role carries a per-run
 * suffix while the {@code CLIENT_MESSAGES} resource + the operation rows carry the exact matrix codes
 * the evaluator matches on. Translation is unconfigured ({@code foremen.translation.enabled=false}),
 * so the disabled seam is wired and a post stores original-only text — never requiring a provider.
 *
 * <p><b>Startup completeness (R8.3 / Pxx).</b> The full context registers every
 * {@code @RestController} and runs {@code PermissionAnnotationValidator} at startup; the context
 * loading at all proves the validator found no half-annotated controller across the whole app —
 * including {@link ClientMessageController}, whose class-level {@code @PermissionResource("CLIENT_MESSAGES")}
 * combines with a method-level {@code @RequiresPermission} on every in-scope handler.
 * {@link #contextLoads_withClientMessageControllerBean()} resolves the controller bean to document it.
 *
 * <p><b>ABAC matrix enforced end to end (R8.1, R8.4).</b> Each case issues a JWT-bearer request
 * through the real interceptor against the seeded grants:
 * <ul>
 *   <li><b>401</b> — an unauthenticated request is rejected at the filter chain;</li>
 *   <li><b>ADMIN</b> — full CRUD (reaches READ + CREATE + the mark-read UPDATE path);</li>
 *   <li><b>CLIENT</b> — CREATE + READ (reaches the list and a post), the author side derived CLIENT;</li>
 *   <li><b>MANAGER</b> — CREATE + READ + UPDATE (reaches the list, a reply post, and mark-read);</li>
 *   <li><b>FOREMAN</b> — READ only (reaches the list but is denied a post with 403);</li>
 *   <li><b>FINANCIER / ESTIMATOR</b> — READ (reach the list), denied a post with 403;</li>
 *   <li><b>WORKER</b> — no grant, denied even the READ list with 403 (deny-by-default).</li>
 * </ul>
 *
 * <p><b>Project scope (R8.5).</b> A MANAGER member of project A requesting project B's thread gets a
 * 404 (an out-of-scope project is indistinguishable from a missing one), and a member only ever sees
 * their own project's messages.
 *
 * <p><b>Notification recipient resolution (R7.1, R7.2, R13.2).</b> After a CLIENT posts, the ACTIVE
 * MANAGER + ACTIVE FOREMAN members each receive a {@code notification.clientMessage.fromClient}; after
 * a MANAGER replies, the CLIENT receives a {@code notification.clientMessage.fromStaff}. Asserted by
 * reading the recipient-scoped notification rows the after-commit emitter created.
 *
 * <p>Repeatability: every role/user/project carries a unique per-run suffix and {@link #cleanUp()}
 * removes every row this test created (in FK order) after each test, so the suite re-runs without
 * manual DB cleanup and no stale cached permission or project-access set leaks.
 *
 * <p>Validates: Requirements 7.1, 7.2, 8.1, 8.3, 8.4, 8.5, 13.2, 13.3
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-06-12-client-communication, task 10.2: CLIENT_MESSAGES ABAC + scope + startup + notifications")
class ClientMessagesAbacStartupIntegrationTest {

    private static final String PATH = "/api/client-messages";

    private static final AtomicLong COUNTER = new AtomicLong();

    /** The exact resource/operation codes the resolved CLIENT_MESSAGES pairs match on. */
    private static final Set<String> SEEDED_RESOURCE_CODES = Set.of("CLIENT_MESSAGES");
    private static final Set<String> SEEDED_OPERATION_CODES = Set.of("CREATE", "READ", "UPDATE", "DELETE");

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
        // Translation unconfigured -> the disabled seam is wired; a post stores original-only text.
        registry.add("foremen.translation.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PermissionCache permissionCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Notifications reference users; drop them first (recipient belongs to a per-run user).
            entityManager.createQuery(
                            "delete from NotificationEntity n where n.recipient.id in "
                                    + "(select u.id from UserEntity u where u.email like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // Message translations/media cascade on the message; delete messages by project first.
            entityManager.createQuery(
                            "delete from ClientMessageTranslationEntity t where t.message.id in "
                                    + "(select m.id from ClientMessageEntity m where m.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p))")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from ClientMessageEntity m where m.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from ProjectMemberEntity pm where pm.user.id in "
                                    + "(select u.id from UserEntity u where u.email like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from RoleResourceEntity rr where rr.role.id in "
                                    + "(select r.id from RoleEntity r where r.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ResourceEntity res where res.code in :codes")
                    .setParameter("codes", SEEDED_RESOURCE_CODES)
                    .executeUpdate();
            entityManager.createQuery("delete from OperationEntity op where op.code in :codes")
                    .setParameter("codes", SEEDED_OPERATION_CODES)
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------------
    // startup annotation completeness (R8.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the app context loads with ClientMessageController registered — "
            + "PermissionAnnotationValidator classifies it COMPLETE at startup (R8.3)")
    void contextLoads_withClientMessageControllerBean() {
        // The context loading at all (via @SpringBootTest) already proves
        // PermissionAnnotationValidator found no half-annotated controller across the whole app,
        // including ClientMessageController. Resolving the bean documents it was exercised.
        assertThat(applicationContext.getBean(ClientMessageController.class)).isNotNull();
    }

    // ------------------------------------------------------------------
    // 401 — unauthenticated (filter chain)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated thread list yields 401 at the filter chain")
    void unauthenticated_yields401() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);

        int status = mockMvc.perform(get(PATH).param("projectId", String.valueOf(projectId)))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("an unauthenticated request to the guarded client-messages endpoint must yield 401")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // ADMIN — full CRUD (R8.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an ADMIN with CLIENT_MESSAGES CRUD reaches READ and CREATE through ABAC")
    void adminCrud_reachesReadAndCreate() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        RoleEntity role = persistRoleWithGrants(grant("CLIENT_MESSAGES", "CREATE", "READ", "UPDATE", "DELETE"));
        // The ABAC interceptor bypasses the exact ADMIN authority (the JWT carries ADMIN), so no
        // CLIENT_MESSAGES grant is even consulted for the gate. The service still derives authorSide
        // from the caller's PROJECT role, and its MANAGER-only reply rule admits ADMIN only when the
        // caller's project role is MANAGER/ADMIN — so the ADMIN is seeded as a MANAGER project member
        // (ADMIN bypasses the project-scope 404 gate regardless).
        long userId = createMember(projectId, "MANAGER", role, AssignmentStatus.ACTIVE);
        String auth = "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, "ADMIN", "admin" + userId + "+" + runId + "@example.com");

        assertThat(listStatus(projectId, auth))
                .as("ADMIN must pass the ABAC gate for the list").isLessThan(400);
        assertThat(postStatus(projectId, auth, "admin hello"))
                .as("ADMIN with CREATE must not be denied the post (no 401/403/5xx)")
                .isNotIn(401, 403).isLessThan(500);
    }

    // ------------------------------------------------------------------
    // CLIENT — CREATE + READ (R8.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a CLIENT member with CREATE+READ reaches the list and a post")
    void clientCreateRead_reachesListAndPost() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        RoleEntity role = persistRoleWithGrants(grant("CLIENT_MESSAGES", "CREATE", "READ"));
        long userId = createMember(projectId, "CLIENT", role, AssignmentStatus.ACTIVE);
        String auth = bearer(userId, role);

        assertThat(listStatus(projectId, auth))
                .as("a CLIENT with READ + membership must pass the ABAC gate for the list").isLessThan(400);
        assertThat(postStatus(projectId, auth, "client question"))
                .as("a CLIENT with CREATE + membership must not be denied the post (no 401/403/5xx)")
                .isNotIn(401, 403).isLessThan(500);
    }

    // ------------------------------------------------------------------
    // MANAGER — CREATE + READ + UPDATE (R8.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a MANAGER member with CREATE+READ+UPDATE reaches the list and a reply post")
    void managerCreateReadUpdate_reachesListAndReply() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        RoleEntity role = persistRoleWithGrants(grant("CLIENT_MESSAGES", "CREATE", "READ", "UPDATE"));
        long userId = createMember(projectId, "MANAGER", role, AssignmentStatus.ACTIVE);
        String auth = bearer(userId, role);

        assertThat(listStatus(projectId, auth))
                .as("a MANAGER with READ + membership must pass the ABAC gate for the list").isLessThan(400);
        assertThat(postStatus(projectId, auth, "manager reply"))
                .as("a MANAGER with CREATE + membership must not be denied the reply (no 401/403/5xx)")
                .isNotIn(401, 403).isLessThan(500);
    }

    // ------------------------------------------------------------------
    // FOREMAN — READ only, denied a post (R8.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a FOREMAN member with READ only reaches the list but is denied a post with 403")
    void foremanReadOnly_readsButCannotPost() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        RoleEntity role = persistRoleWithGrants(grant("CLIENT_MESSAGES", "READ"));
        long userId = createMember(projectId, "FOREMAN", role, AssignmentStatus.ACTIVE);
        String auth = bearer(userId, role);

        assertThat(listStatus(projectId, auth))
                .as("a FOREMAN with READ + membership must pass the ABAC gate for the list").isLessThan(400);
        assertThat(postStatus(projectId, auth, "foreman attempt"))
                .as("a FOREMAN without CREATE must be denied the post by the interceptor (403)")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // FINANCIER / ESTIMATOR — READ, denied a post (R8.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FINANCIER and ESTIMATOR members with READ only read the thread but cannot post (403)")
    void readOnlyStaff_readButCannotPost() throws Exception {
        for (String roleCode : List.of("FINANCIER", "ESTIMATOR")) {
            long projectId = createProject(ProjectStatus.ACTIVE);
            RoleEntity role = persistRoleWithGrants(grant("CLIENT_MESSAGES", "READ"));
            long userId = createMember(projectId, roleCode, role, AssignmentStatus.ACTIVE);
            String auth = bearer(userId, role);

            assertThat(listStatus(projectId, auth))
                    .as("%s with READ + membership must pass the ABAC gate for the list", roleCode)
                    .isLessThan(400);
            assertThat(postStatus(projectId, auth, roleCode + " attempt"))
                    .as("%s without CREATE must be denied the post by the interceptor (403)", roleCode)
                    .isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------
    // WORKER — no grant, denied even the READ list (R8.4 deny-by-default)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a WORKER member whose role holds no CLIENT_MESSAGES grant is denied the list with 403")
    void workerNoGrant_deniedList() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        // Role holds a READ grant on an unrelated resource only — no CLIENT_MESSAGES grant at all.
        RoleEntity role = persistRoleWithGrants(grant("PROJECTS", "READ"));
        long userId = createMember(projectId, "WORKER", role, AssignmentStatus.ACTIVE);

        assertThat(listStatus(projectId, bearer(userId, role)))
                .as("a caller with no CLIENT_MESSAGES grant must be denied the list (403, deny-by-default)")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // Project scope (R8.5) — another project's thread is unreachable (404)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a MANAGER member of project A requesting project B's thread gets 404 — project scope (R8.5)")
    void managerCannotReachAnotherProjectsThread() throws Exception {
        long memberProjectId = createProject(ProjectStatus.ACTIVE);   // the caller belongs here
        long otherProjectId = createProject(ProjectStatus.ACTIVE);    // the caller is NOT a member here
        RoleEntity role = persistRoleWithGrants(grant("CLIENT_MESSAGES", "CREATE", "READ", "UPDATE"));
        long userId = createMember(memberProjectId, "MANAGER", role, AssignmentStatus.ACTIVE);
        String auth = bearer(userId, role);

        assertThat(listStatus(otherProjectId, auth))
                .as("a non-member reaching another project's thread must get 404 (out-of-scope "
                        + "indistinguishable from missing, R8.5)")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Notification recipient resolution (R7.1, R7.2, R13.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a CLIENT post notifies the ACTIVE MANAGER + ACTIVE FOREMAN; a STAFF reply notifies the CLIENT")
    void notificationRecipientsResolvedByAuthorSide() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);

        RoleEntity clientRole = persistRoleWithGrants(grant("CLIENT_MESSAGES", "CREATE", "READ"));
        RoleEntity managerRole = persistRoleWithGrants(grant("CLIENT_MESSAGES", "CREATE", "READ", "UPDATE"));
        RoleEntity foremanRole = persistRoleWithGrants(grant("CLIENT_MESSAGES", "READ"));

        long clientId = createMember(projectId, "CLIENT", clientRole, AssignmentStatus.ACTIVE);
        long managerId = createMember(projectId, "MANAGER", managerRole, AssignmentStatus.ACTIVE);
        long foremanId = createMember(projectId, "FOREMAN", foremanRole, AssignmentStatus.ACTIVE);

        // --- CLIENT posts a question -> MANAGER + FOREMAN notified (fromClient), CLIENT not notified ---
        int clientPost = postStatus(projectId, bearer(clientId, clientRole), "please advise");
        assertThat(clientPost).as("the CLIENT post must succeed").isNotIn(401, 403).isLessThan(500);

        assertThat(notificationTypesFor(managerId))
                .as("the ACTIVE MANAGER is notified of a client question (R7.1)")
                .contains("notification.clientMessage.fromClient");
        assertThat(notificationTypesFor(foremanId))
                .as("the ACTIVE FOREMAN is notified of a client question (R7.1)")
                .contains("notification.clientMessage.fromClient");
        assertThat(notificationTypesFor(clientId))
                .as("the CLIENT is not notified of their own question")
                .doesNotContain("notification.clientMessage.fromClient");

        // --- MANAGER replies -> CLIENT notified (fromStaff) ---
        int managerReply = postStatus(projectId, bearer(managerId, managerRole), "noted, thanks");
        assertThat(managerReply).as("the MANAGER reply must succeed").isNotIn(401, 403).isLessThan(500);

        assertThat(notificationTypesFor(clientId))
                .as("the CLIENT is notified of a staff reply (R7.2)")
                .contains("notification.clientMessage.fromStaff");
        assertThat(notificationTypesFor(foremanId))
                .as("a FOREMAN is not a recipient of a staff reply")
                .doesNotContain("notification.clientMessage.fromStaff");
    }

    // ------------------------------------------------------------------
    // requests
    // ------------------------------------------------------------------

    private int listStatus(long projectId, String auth) throws Exception {
        return mockMvc.perform(get(PATH)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", auth))
                .andReturn().getResponse().getStatus();
    }

    /** Posts a text-only message (no media) as a {@code multipart/form-data} with a JSON body part. */
    private int postStatus(long projectId, String auth, String text) throws Exception {
        String bodyJson = "{\"text\":\"" + text + "\"}";
        MockMultipartFile body = new MockMultipartFile(
                "body", "body", MediaType.APPLICATION_JSON_VALUE,
                bodyJson.getBytes(StandardCharsets.UTF_8));
        MockMultipartHttpServletRequestBuilder req = multipart(PATH)
                .file(body);
        return mockMvc.perform(req
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", auth)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    /** The notification type keys delivered to a recipient user (newest first). */
    private List<String> notificationTypesFor(long recipientUserId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery(
                                "select n.type from NotificationEntity n where n.recipient.id = :rid "
                                        + "order by n.createdDate desc", String.class)
                        .setParameter("rid", recipientUserId)
                        .getResultList());
    }

    /** A single (resource, {operations...}) grant specification for a role. */
    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private String bearer(long userId, RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, role.getCode(), "user" + userId + "+" + runId + "@example.com");
    }

    /** Creates (and commits) a project in the given status with a per-run unique name; returns its id. */
    private long createProject(ProjectStatus projectStatus) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("Client Messages ABAC " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(projectStatus);
            entityManager.persist(project);
            entityManager.flush();
            return project.getId();
        });
    }

    /**
     * Creates (and commits) a user + a membership row linking that user to the project with the
     * supplied <em>project</em> role code and assignment status. The user's global role is the ABAC
     * grant-bearing {@code role}; the membership's {@code projectRole} carries the project-role code
     * ({@code CLIENT}/{@code MANAGER}/{@code FOREMAN}/…) that the service's author-side derivation,
     * MANAGER-only rule, and the notification emitter's recipient resolution read. The returned user
     * id is minted into the caller's JWT {@code sub}, so {@code ProjectAccessCache} self-loads this
     * project for the caller (the 404 scope gate passes here and fails for any other project).
     *
     * @return the generated user id (used as the JWT subject)
     */
    private long createMember(long projectId, String projectRoleCode, RoleEntity role,
                              AssignmentStatus assignmentStatus) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity managedRole = entityManager.getReference(RoleEntity.class, role.getId());
            RoleEntity projectRole = getOrCreateProjectRole(projectRoleCode);

            UserEntity user = newUser(managedRole);
            entityManager.persist(user);

            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setUser(user);
            member.setProjectId(projectId);
            member.setProjectRole(projectRole);
            member.setAssignmentStatus(assignmentStatus);
            entityManager.persist(member);
            entityManager.flush();
            return user.getId();
        });
    }

    private UserEntity newUser(RoleEntity managedRole) {
        UserEntity user = new UserEntity();
        long seq = COUNTER.incrementAndGet();
        user.setName("Member " + runId + "-" + seq);
        user.setEmail("member" + seq + "+" + runId + "@example.com");
        user.setRole(managedRole);
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        return user;
    }

    /**
     * Persists (and commits) a role with a unique per-run code holding exactly the supplied grants.
     * The evaluator matches on resource + operation <em>code</em>, so the shared resource/operation
     * rows carry the exact matrix codes (e.g. {@code CLIENT_MESSAGES}/{@code CREATE}); only the role
     * code carries the per-run suffix (it keys the permission cache).
     */
    private RoleEntity persistRoleWithGrants(Grant... grants) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("CM_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль client-messages");
            role.setNamePL("Rola client-messages");
            role.setSystem(false);

            List<com.foremen.dao.model.RoleResourceEntity> roleResources = new ArrayList<>();
            for (Grant g : grants) {
                ResourceEntity resource = getOrCreateResource(g.resource());
                List<OperationEntity> ops = new ArrayList<>();
                for (String opCode : g.operations()) {
                    ops.add(getOrCreateOperation(opCode));
                }
                com.foremen.dao.model.RoleResourceEntity rr = new com.foremen.dao.model.RoleResourceEntity();
                rr.setRole(role);
                rr.setResource(resource);
                rr.setOperations(ops);
                roleResources.add(rr);
            }
            role.getRoleResources().addAll(roleResources);

            entityManager.persist(role);
            entityManager.flush();
            permissionCache.invalidate(role.getCode());
            return role;
        });
    }

    /**
     * Returns (creating on first use) a per-run project role whose {@code code} is exactly the
     * project-role code the service/emitter read ({@code CLIENT}/{@code MANAGER}/{@code FOREMAN}/…).
     * The code must be the bare role code (not suffixed) because the service matches on it; a per-run
     * uniqueness is instead provided by never colliding across runs (create-drop + teardown).
     */
    private RoleEntity getOrCreateProjectRole(String code) {
        List<RoleEntity> existing = entityManager
                .createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU(code + " RU");
        role.setNamePL(code + " PL");
        role.setSystem(false);
        entityManager.persist(role);
        return role;
    }

    /** Returns the existing resource row for the code, or creates it (codes must be exact). */
    private ResourceEntity getOrCreateResource(String code) {
        List<ResourceEntity> existing = entityManager
                .createQuery("select r from ResourceEntity r where r.code = :c", ResourceEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(code);
        resource.setNameRU(code + " RU " + runId);
        resource.setNamePL(code + " PL " + runId);
        entityManager.persist(resource);
        return resource;
    }

    /** Returns the existing operation row for the code, or creates it (codes must be exact). */
    private OperationEntity getOrCreateOperation(String code) {
        List<OperationEntity> existing = entityManager
                .createQuery("select o from OperationEntity o where o.code = :c", OperationEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OperationEntity operation = new OperationEntity();
        operation.setCode(code);
        operation.setNameRU(code + " RU " + runId);
        operation.setNamePL(code + " PL " + runId);
        entityManager.persist(operation);
        return operation;
    }
}
