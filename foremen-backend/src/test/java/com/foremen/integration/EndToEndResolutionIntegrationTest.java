package com.foremen.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end permission-resolution integration test for FOR-03-08 (task 12.1).
 *
 * <p>Boots the full application context over the real Spring MVC + Spring Security stack against a
 * Testcontainers PostgreSQL instance, following the project's established
 * {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} +
 * {@code @ActiveProfiles("integration-test")} pattern (mirroring
 * {@link FilterChainComposedAuthIntegrationTest}). Under the {@code integration-test} profile
 * Liquibase is disabled and the schema is created by Hibernate {@code create-drop}, so every matrix
 * row (roles, resources, operations, grants) is created programmatically via the
 * {@link EntityManager}.
 *
 * <p>Where {@code FilterChainComposedAuthIntegrationTest} covers the composed 401/403/200 outcomes
 * for a single endpoint, this test verifies that the {@code PermissionResolver} derives the correct
 * {@code (resource, operation)} pair for a spread of endpoints as it runs inside the real
 * interceptor chain. The interceptor's {@code preHandle} denies with HTTP 403
 * {@code error.access.denied} exactly when the derived pair is one the caller's role does not hold,
 * and lets the request through otherwise. So each resolved pair is proven with a matched pair of
 * cases:
 * <ul>
 *   <li>a role granted <em>only</em> the pair-under-test reaches the endpoint (the interceptor does
 *       not return 403), and</li>
 *   <li>a sibling endpoint that resolves to a <em>different</em> pair is denied with 403 for the
 *       same role,</li>
 * </ul>
 * so a wrong resolution would flip one of the two assertions. Concretely:
 * <ul>
 *   <li>{@code GET /api/users} resolves {@code USERS}/{@code READ}
 *       ({@code @PermissionResource("USERS")} + inherited {@code @PermissionOperation("READ")}).</li>
 *   <li>{@code POST /api/users} resolves {@code USERS}/{@code CREATE}
 *       ({@code @PermissionOperation("CREATE")} on the inherited create method).</li>
 *   <li>{@code POST /api/users/client} resolves {@code PROJECTS}/{@code EDIT} via the
 *       {@code @RequiresPermission} precedence rule (the class {@code @PermissionResource("USERS")}
 *       is ignored).</li>
 *   <li>{@code GET /api/project-members} resolves {@code PROJECT_MEMBERS}/{@code READ} from its
 *       {@code @RequiresPermission}.</li>
 *   <li>{@code GET /api/auth/me} and {@code GET /api/users/&#123;id&#125;/display-preferences}
 *       are Unguarded — the interceptor performs no matrix check, so a role holding no grants is
 *       never denied 403 by the interceptor.</li>
 *   <li>An ADMIN token reaches every guarded endpoint via the evaluator's ADMIN bypass.</li>
 *   <li>A missing token still yields 401 at the filter chain (existing outcome preserved).</li>
 * </ul>
 *
 * <p>For endpoints the interceptor <em>allows</em>, the downstream handler may still fail on the
 * minimal/empty request body (e.g. 400/404/500). That is irrelevant to resolution: the assertion is
 * only that the status is <b>not 403</b>, i.e. the {@code PermissionInterceptor} did not block the
 * derived pair. Denied cases assert an exact 403 whose body carries the resolved
 * {@code error.access.denied} message, so a 403 from an unrelated cause cannot masquerade as a
 * resolution result.
 *
 * <p>Repeatability: every role code carries a unique per-run suffix (a per-test UUID plus a
 * counter); the shared matrix resource/operation code rows (whose codes must match the resolved
 * pairs exactly for the evaluator's code match to succeed) are created idempotently and removed,
 * along with every role/grant this test created, in {@link #cleanUp()}. The suite therefore re-runs
 * without manual DB cleanup, and per-run role codes never collide so no stale cached permission set
 * can leak.
 *
 * <p>Validates: Requirements 3.2, 7.1, 7.2, 7.3, 7.4, 10.1, 10.2, 10.3, 10.4, 13.1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class EndToEndResolutionIntegrationTest {

    private static final String USERS_PATH = "/api/users";
    private static final String USERS_CLIENT_PATH = "/api/users/client";
    private static final String PROJECT_MEMBERS_PATH = "/api/project-members";
    private static final String AUTH_ME_PATH = "/api/auth/me";

    private static final AtomicLong COUNTER = new AtomicLong();

    /**
     * Every resource/operation code this test seeds so the codes match the resolved pairs exactly.
     * Removed wholesale in {@link #cleanUp()} to keep the suite repeatable.
     */
    private static final Set<String> SEEDED_RESOURCE_CODES =
            Set.of("USERS", "PROJECTS", "PROJECT_MEMBERS");
    private static final Set<String> SEEDED_OPERATION_CODES =
            Set.of("READ", "CREATE", "EDIT");

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
    private PermissionCache permissionCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so role codes never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // Bulk deletes require an active transaction; @AfterEach is not test-transaction-managed,
        // so wrap the teardown in an explicit programmatic transaction. Delete the grants
        // (role_resources + their operations) owned by this run's roles first (FK order), selecting
        // via a role-id subquery to avoid an implicit-join delete PostgreSQL rejects.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
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
    // GET /api/users -> USERS/READ
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/users resolves USERS/READ: a role granted USERS/READ reaches the endpoint")
    void getUsers_resolvesUsersRead_allowedForGrantedRole() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("USERS", "READ"));
        MvcResult result = mockMvc.perform(get(USERS_PATH).header("Authorization", bearer(role)))
                .andReturn();

        assertNotForbidden(result, "GET /api/users must resolve USERS/READ and be allowed for a "
                + "role holding that grant (interceptor must not return 403)");
    }

    @Test
    @DisplayName("GET /api/users resolves USERS/READ: a role lacking that grant is denied 403")
    void getUsers_resolvesUsersRead_deniedForRoleWithoutGrant() throws Exception {
        // Holds USERS/CREATE only -> the USERS/READ pair the endpoint resolves to is not granted.
        RoleEntity role = persistRoleWithGrants(grant("USERS", "CREATE"));
        MvcResult result = mockMvc.perform(get(USERS_PATH).header("Authorization", bearer(role)))
                .andReturn();

        assertAccessDenied(result, "GET /api/users resolves USERS/READ; a role holding only "
                + "USERS/CREATE must be denied 403");
    }

    // ------------------------------------------------------------------
    // POST /api/users -> USERS/CREATE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/users resolves USERS/CREATE: a role granted USERS/CREATE reaches the endpoint")
    void postUsers_resolvesUsersCreate_allowedForGrantedRole() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("USERS", "CREATE"));
        MvcResult result = mockMvc.perform(post(USERS_PATH)
                        .header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertNotForbidden(result, "POST /api/users must resolve USERS/CREATE and be allowed for a "
                + "role holding that grant (interceptor must not return 403)");
    }

    @Test
    @DisplayName("POST /api/users resolves USERS/CREATE: a role holding only USERS/READ is denied 403")
    void postUsers_resolvesUsersCreate_deniedForReadOnlyRole() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("USERS", "READ"));
        MvcResult result = mockMvc.perform(post(USERS_PATH)
                        .header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertAccessDenied(result, "POST /api/users resolves USERS/CREATE; a role holding only "
                + "USERS/READ must be denied 403");
    }

    // ------------------------------------------------------------------
    // POST /api/users/client -> PROJECTS/EDIT (@RequiresPermission precedence, Req 3.2 / 10.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/users/client resolves PROJECTS/EDIT via @RequiresPermission precedence")
    void postUsersClient_resolvesProjectsEdit_allowedForGrantedRole() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("PROJECTS", "EDIT"));
        MvcResult result = mockMvc.perform(post(USERS_CLIENT_PATH)
                        .header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertNotForbidden(result, "POST /api/users/client must resolve PROJECTS/EDIT (its "
                + "@RequiresPermission wins over the class @PermissionResource(\"USERS\")) and be "
                + "allowed for a role holding PROJECTS/EDIT");
    }

    @Test
    @DisplayName("POST /api/users/client resolves PROJECTS/EDIT, not USERS: a USERS-only role is denied 403")
    void postUsersClient_resolvesProjectsEdit_deniedForUsersOnlyRole() throws Exception {
        // Full USERS CRUD but no PROJECTS grant: if the endpoint resolved to USERS it would pass;
        // it must resolve to PROJECTS/EDIT (precedence) and therefore be denied.
        RoleEntity role = persistRoleWithGrants(
                grant("USERS", "READ", "CREATE", "EDIT"));
        MvcResult result = mockMvc.perform(post(USERS_CLIENT_PATH)
                        .header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertAccessDenied(result, "POST /api/users/client resolves PROJECTS/EDIT via "
                + "@RequiresPermission precedence; a role holding only USERS grants must be denied 403");
    }

    // ------------------------------------------------------------------
    // GET /api/project-members -> PROJECT_MEMBERS/READ
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/project-members resolves PROJECT_MEMBERS/READ: granted role reaches the endpoint")
    void getProjectMembers_resolvesProjectMembersRead_allowedForGrantedRole() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("PROJECT_MEMBERS", "READ"));
        MvcResult result = mockMvc.perform(get(PROJECT_MEMBERS_PATH)
                        .param("projectId", "1")
                        .header("Authorization", bearer(role)))
                .andReturn();

        assertNotForbidden(result, "GET /api/project-members must resolve PROJECT_MEMBERS/READ and "
                + "be allowed for a role holding that grant");
    }

    @Test
    @DisplayName("GET /api/project-members resolves PROJECT_MEMBERS/READ: a role lacking it is denied 403")
    void getProjectMembers_resolvesProjectMembersRead_deniedForRoleWithoutGrant() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("USERS", "READ"));
        MvcResult result = mockMvc.perform(get(PROJECT_MEMBERS_PATH)
                        .param("projectId", "1")
                        .header("Authorization", bearer(role)))
                .andReturn();

        assertAccessDenied(result, "GET /api/project-members resolves PROJECT_MEMBERS/READ; a role "
                + "holding only USERS/READ must be denied 403");
    }

    // ------------------------------------------------------------------
    // Unguarded controllers proceed without a matrix check (Req 10.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/auth/me is Unguarded: a role holding no grants is not denied 403 by the interceptor")
    void authMe_isUnguarded_notMatrixChecked() throws Exception {
        // A role with zero grants: were /api/auth/me matrix-checked, it would be denied 403.
        RoleEntity role = persistRoleWithGrants();
        MvcResult result = mockMvc.perform(get(AUTH_ME_PATH).header("Authorization", bearer(role)))
                .andReturn();

        assertNotForbidden(result, "GET /api/auth/me is Unguarded; the PermissionInterceptor must "
                + "perform no matrix check, so a grant-less role is never denied 403 here");
    }

    @Test
    @DisplayName("Display-preferences endpoint is Unguarded: no interceptor matrix check for the owner")
    void displayPreferences_isUnguarded_notMatrixChecked() throws Exception {
        // The DisplayPreferencesController performs its own self-vs-requested-id check keyed on the
        // principal (the user id in the token 'sub'). Minting a token whose subject equals the path
        // id keeps that self-check satisfied, isolating the assertion to the interceptor: a
        // grant-less role must not be denied 403 by the matrix (Unguarded).
        long userId = nextId();
        RoleEntity role = persistRoleWithGrants();
        String token = jwtTokenProvider.generateAccessToken(
                userId, role.getCode(), "prefs+" + runId + "@example.com");

        MvcResult result = mockMvc.perform(get(USERS_PATH + "/" + userId + "/display-preferences")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertNotForbidden(result, "the display-preferences endpoint is Unguarded; the "
                + "PermissionInterceptor must perform no matrix check for its owner");
    }

    // ------------------------------------------------------------------
    // ADMIN bypass + preserved 401 (Req 7.4, 13.1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADMIN reaches guarded endpoints via the evaluator bypass (no grants needed)")
    void admin_reachesGuardedEndpoints() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");

        MvcResult usersResult = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        MvcResult membersResult = mockMvc.perform(get(PROJECT_MEMBERS_PATH)
                        .param("projectId", "1")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(usersResult.getResponse().getStatus())
                .as("ADMIN must reach the guarded GET /api/users (evaluator ADMIN bypass -> 200)")
                .isEqualTo(200);
        assertNotForbidden(membersResult, "ADMIN must reach the guarded GET /api/project-members "
                + "via the evaluator bypass (interceptor must not return 403)");
    }

    @Test
    @DisplayName("Missing token on a guarded endpoint still yields 401 (existing outcome preserved)")
    void missingToken_yields401() throws Exception {
        MvcResult result = mockMvc.perform(get(USERS_PATH)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unauthenticated request to a guarded endpoint must still yield 401")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // assertions
    // ------------------------------------------------------------------

    /** Asserts the interceptor did NOT deny the request (any status other than 403). */
    private void assertNotForbidden(MvcResult result, String because) {
        assertThat(result.getResponse().getStatus())
                .as(because)
                .isNotEqualTo(403);
    }

    /** Asserts an exact 403 whose body carries the resolved error.access.denied message. */
    private void assertAccessDenied(MvcResult result, String because) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(because)
                .isEqualTo(403);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("403 body must carry the resolved error.access.denied message (PL or RU locale), "
                        + "confirming the interceptor's access-denied path produced it")
                .satisfiesAnyOf(
                        b -> assertThat(b).contains("Brak wymaganych uprawnień"),
                        b -> assertThat(b).contains("Недостаточно прав"));
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    /** A single (resource, {operations...}) grant specification for a role. */
    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private String bearer(RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                nextId(), role.getCode(), "user+" + runId + "@example.com");
    }

    /**
     * Persists (and commits) a role with a unique per-run code holding exactly the supplied grants.
     * The evaluator matches on resource and operation <em>code</em>, so the shared resource/operation
     * rows carry the exact matrix codes (e.g. {@code USERS}/{@code READ}); only the role code carries
     * the per-run suffix (it keys the permission cache). Resource/operation rows are created once and
     * reused across grants within a run. The committed rows are visible to the request-thread
     * transaction the {@code ForemenPermissionEvaluator} opens on a cache miss.
     */
    private RoleEntity persistRoleWithGrants(Grant... grants) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("E2E_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль E2E");
            role.setNamePL("Rola E2E");
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

    private long nextId() {
        return 910_000L + COUNTER.incrementAndGet();
    }
}
