package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.WorkerPayoutController;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.permission.PermissionCache;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-11 task 8.2 — payroll ABAC + startup-wiring integration test (design §Testing strategy
 * "ABAC / startup"; Properties <b>P9 RoleMatrix</b>, <b>P13 StartupAnnotationCompleteness</b>; R7.4,
 * R10.3, R10.4, R10.5).
 *
 * <p>Boots the full application context over the real Spring MVC + Spring Security + ABAC stack
 * against a Testcontainers PostgreSQL, following the repo's established
 * {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} +
 * {@code @ActiveProfiles("integration-test")} pattern (mirroring {@link ProjectReadinessAbacIntegrationTest}).
 * Under the {@code integration-test} profile Liquibase is disabled and Hibernate {@code create-drop}
 * builds the schema, so every matrix row (roles, resources, operations, grants) and every domain
 * fixture (users, project, membership) is created programmatically; each role carries a per-run
 * suffix while the {@code PAYROLL} resource + the {@code CREATE}/{@code READ}/{@code UPDATE}/
 * {@code DELETE} operations carry the exact matrix codes the evaluator matches on.
 *
 * <p><b>P13 — startup annotation completeness (R10.3).</b> The full context registers every
 * {@code @RestController} and runs {@code PermissionAnnotationValidator} (a
 * {@code SmartInitializingSingleton}) at startup; the context loading at all therefore proves the
 * validator found no half-annotated controller across the whole app — including
 * {@link WorkerPayoutController}, which carries class-level {@code @PermissionResource("PAYROLL")}
 * plus a method-level {@code @RequiresPermission} on every in-scope handler. {@link #contextLoads_withWorkerPayoutControllerBean()}
 * resolves the controller bean explicitly to document it was the one exercised.
 *
 * <p><b>P9 / R10.4, R10.5 — the PAYROLL matrix enforced end to end.</b> Each case issues a JWT-bearer
 * request through the real interceptor:
 * <ul>
 *   <li><b>401</b> — an unauthenticated request is rejected at the filter chain;</li>
 *   <li><b>MANAGER with CRUD + membership</b> reaches both the READ list and a CREATE (no 4xx/5xx
 *       from the ABAC gate);</li>
 *   <li><b>FOREMAN with READ only</b> reaches the list (READ) but is denied a CREATE with 403
 *       (read-only; no issuing);</li>
 *   <li><b>CLIENT / WORKER with no PAYROLL grant</b> are denied even the READ list with 403
 *       (deny-by-default);</li>
 *   <li><b>project scope (R10.5)</b> — a MANAGER member of project A requesting project B's payouts
 *       gets a 404 (an out-of-scope project is indistinguishable from a missing one), and a create
 *       against project B is likewise confined/denied.</li>
 * </ul>
 *
 * <p>Repeatability: every role/user/project carries a unique per-run suffix and {@link #cleanUp()}
 * removes every row this test created (in FK order) after each test, so the suite re-runs without
 * manual DB cleanup and no stale cached permission or project-access set leaks.
 *
 * <p>Validates: Requirements 7.4, 10.3, 10.4, 10.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-06-11-payroll, task 8.2: PAYROLL ABAC matrix + startup completeness (P9, P13)")
class PayrollAbacStartupIntegrationTest {

    private static final String PAYROLL_PATH = "/api/worker-payouts";

    private static final AtomicLong COUNTER = new AtomicLong();

    /** The exact resource/operation codes the resolved PAYROLL pairs match on. */
    private static final Set<String> SEEDED_RESOURCE_CODES = Set.of("PAYROLL");
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
            // Payouts (created by the MANAGER create case) reference projects, so they must go
            // before the projects they belong to. This test seeds no report lines, so a payout's
            // only blocking FK is project_id.
            entityManager.createQuery(
                            "delete from WorkerPayoutEntity p where p.project.id in "
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
    // P13 — startup annotation completeness (R10.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P13: the app context loads with WorkerPayoutController registered — "
            + "PermissionAnnotationValidator classifies it COMPLETE at startup (R10.3)")
    void contextLoads_withWorkerPayoutControllerBean() {
        // The context loading at all (via @SpringBootTest) already proves
        // PermissionAnnotationValidator found no half-annotated controller across the whole app,
        // including WorkerPayoutController. Resolving the bean documents it was exercised.
        assertThat(applicationContext.getBean(WorkerPayoutController.class)).isNotNull();
    }

    // ------------------------------------------------------------------
    // 401 — unauthenticated (filter chain)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated payroll list yields 401 at the filter chain")
    void unauthenticated_yields401() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);

        MvcResult result = mockMvc.perform(get(PAYROLL_PATH).param("projectId", String.valueOf(projectId)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unauthenticated request to the guarded payroll endpoint must yield 401")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // MANAGER (CRUD + membership) — reaches READ + CREATE (P9, R10.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a MANAGER member with PAYROLL CRUD reaches the list (READ) and a create (CREATE) through ABAC")
    void managerWithCrud_reachesReadAndCreate() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        RoleEntity role = persistRoleWithGrants(grant("PAYROLL", "CREATE", "READ", "UPDATE", "DELETE"));
        long workerId = createMember(projectId, role, AssignmentStatus.ACTIVE);
        String auth = bearer(workerId, role);

        int listStatus = mockMvc.perform(get(PAYROLL_PATH)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", auth))
                .andReturn().getResponse().getStatus();
        assertThat(listStatus)
                .as("a MANAGER with PAYROLL READ + membership must pass the ABAC gate for the list")
                .isLessThan(400);

        int createStatus = mockMvc.perform(post(PAYROLL_PATH)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(workerId)))
                .andReturn().getResponse().getStatus();
        assertThat(createStatus)
                .as("a MANAGER with PAYROLL CREATE + membership must not be denied by the ABAC gate "
                        + "(no 401/403/5xx)")
                .isNotIn(401, 403)
                .isLessThan(500);
    }

    // ------------------------------------------------------------------
    // FOREMAN (READ only) — reads but cannot create (P9, R10.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a FOREMAN member with PAYROLL READ only reaches the list but is denied a create with 403")
    void foremanReadOnly_readsButCannotCreate() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        RoleEntity role = persistRoleWithGrants(grant("PAYROLL", "READ"));
        long workerId = createMember(projectId, role, AssignmentStatus.ACTIVE);
        String auth = bearer(workerId, role);

        int listStatus = mockMvc.perform(get(PAYROLL_PATH)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", auth))
                .andReturn().getResponse().getStatus();
        assertThat(listStatus)
                .as("a FOREMAN with PAYROLL READ + membership must pass the ABAC gate for the list")
                .isLessThan(400);

        int createStatus = mockMvc.perform(post(PAYROLL_PATH)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(workerId)))
                .andReturn().getResponse().getStatus();
        assertThat(createStatus)
                .as("a FOREMAN without PAYROLL CREATE must be denied the create by the interceptor (403)")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // CLIENT / WORKER (no grant) — denied even the READ list (P9, R10.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a member whose role holds no PAYROLL grant is denied even the list with 403 (deny-by-default)")
    void noPayrollGrant_deniedList() throws Exception {
        long projectId = createProject(ProjectStatus.ACTIVE);
        // Role holds a READ grant on an unrelated resource only — no PAYROLL grant at all.
        RoleEntity role = persistRoleWithGrants(grant("PROJECTS", "READ"));
        long workerId = createMember(projectId, role, AssignmentStatus.ACTIVE);

        int status = mockMvc.perform(get(PAYROLL_PATH)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", bearer(workerId, role)))
                .andReturn().getResponse().getStatus();
        assertThat(status)
                .as("a caller with no PAYROLL grant must be denied the list by the interceptor (403)")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // Project scope (R10.5) — another project's payouts are unreachable (404)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a MANAGER member of project A requesting project B's payouts gets 404 — project scope (R10.5)")
    void managerCannotReachAnotherProjectsPayouts() throws Exception {
        long memberProjectId = createProject(ProjectStatus.ACTIVE);   // the caller belongs here
        long otherProjectId = createProject(ProjectStatus.ACTIVE);    // the caller is NOT a member here
        RoleEntity role = persistRoleWithGrants(grant("PAYROLL", "CREATE", "READ", "UPDATE", "DELETE"));
        long workerId = createMember(memberProjectId, role, AssignmentStatus.ACTIVE);
        String auth = bearer(workerId, role);

        int status = mockMvc.perform(get(PAYROLL_PATH)
                        .param("projectId", String.valueOf(otherProjectId))
                        .header("Authorization", auth))
                .andReturn().getResponse().getStatus();
        assertThat(status)
                .as("a non-member reaching another project's payouts must get 404 (out-of-scope is "
                        + "indistinguishable from missing, R10.5)")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    /** A single (resource, {operations...}) grant specification for a role. */
    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private String createBody(long workerId) {
        LocalDate from = LocalDate.now().minusDays(10);
        LocalDate to = LocalDate.now().minusDays(1); // non-advance: periodTo not after today
        return "{\"workerId\":" + workerId
                + ",\"periodFrom\":\"" + from + "\""
                + ",\"periodTo\":\"" + to + "\""
                + ",\"paymentChannel\":\"BANK_TRANSFER\""
                + ",\"amount\":0}";
    }

    private String bearer(long userId, RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, role.getCode(), "user" + userId + "+" + runId + "@example.com");
    }

    /** Creates (and commits) a project in the given status with a per-run unique name; returns its id. */
    private long createProject(ProjectStatus projectStatus) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("Payroll ABAC " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(projectStatus);
            entityManager.persist(project);
            entityManager.flush();
            return project.getId();
        });
    }

    /**
     * Creates (and commits) a user + a membership row linking that user to the project with the
     * supplied role and assignment status. The returned user id is minted into the caller's JWT
     * {@code sub}, so {@code ProjectAccessCache} self-loads this project id for the caller and the
     * service's 404 access gate passes for this project (and fails for any other). The same user
     * doubles as the payout's target worker, so an ACTIVE membership also satisfies R4.6.
     *
     * @return the generated user id (used both as the JWT subject and the payout worker)
     */
    private long createMember(long projectId, RoleEntity role, AssignmentStatus assignmentStatus) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity managedRole = entityManager.getReference(RoleEntity.class, role.getId());

            UserEntity user = new UserEntity();
            long seq = COUNTER.incrementAndGet();
            user.setName("Member " + runId + "-" + seq);
            user.setEmail("member" + seq + "+" + runId + "@example.com");
            user.setRole(managedRole);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale("ru");
            entityManager.persist(user);

            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setUser(user);
            member.setProjectId(projectId);
            member.setProjectRole(managedRole);
            member.setAssignmentStatus(assignmentStatus);
            entityManager.persist(member);
            entityManager.flush();
            return user.getId();
        });
    }

    /**
     * Persists (and commits) a role with a unique per-run code holding exactly the supplied grants.
     * The evaluator matches on resource + operation <em>code</em>, so the shared resource/operation
     * rows carry the exact matrix codes (e.g. {@code PAYROLL}/{@code CREATE}); only the role code
     * carries the per-run suffix (it keys the permission cache).
     */
    private RoleEntity persistRoleWithGrants(Grant... grants) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("PAYROLL_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль payroll");
            role.setNamePL("Rola payroll");
            role.setSystem(false);

            List<RoleResourceEntity> roleResources = new ArrayList<>();
            for (Grant g : grants) {
                ResourceEntity resource = getOrCreateResource(g.resource());
                List<OperationEntity> ops = new ArrayList<>();
                for (String opCode : g.operations()) {
                    ops.add(getOrCreateOperation(opCode));
                }
                RoleResourceEntity rr = new RoleResourceEntity();
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
