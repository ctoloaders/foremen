package com.foremen.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Composed filter-chain + interceptor enforcement integration test for the FOR-03-08
 * {@code SecurityFilterChain} migration (task 9.2).
 *
 * <p>Boots the full application context over the real Spring MVC + Spring Security stack against a
 * Testcontainers PostgreSQL instance, following the project's established
 * {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} +
 * {@code @ActiveProfiles("integration-test")} pattern (mirroring
 * {@link InviteEndToEndIntegrationTest}). Under the {@code integration-test} profile Liquibase is
 * disabled and the schema is created by Hibernate {@code create-drop}, so all matrix rows (roles,
 * resources, operations, grants) are created programmatically via the {@link EntityManager}.
 *
 * <p>Exercises the composed authentication (filter chain) + authorization (interceptor) layers
 * against a protected, matrix-guarded CRUD endpoint — {@code GET /api/users}, which resolves to the
 * {@code USERS}/{@code READ} pair via {@code @PermissionResource("USERS")} on {@code UserController}
 * and {@code @PermissionOperation("READ")} on the inherited {@code AdminController.find} default
 * method:
 * <ol>
 *   <li><b>No token</b> &rarr; the {@code SecurityFilterChain} short-circuits with HTTP 401 via the
 *       {@code JwtAuthenticationEntryPoint}; the {@code PermissionInterceptor} never runs
 *       (Requirements 14.1, 14.2, 15.1).</li>
 *   <li><b>Valid token whose role lacks the grant</b> &rarr; the request clears authentication but
 *       the {@code PermissionInterceptor} denies with HTTP 403 {@code error.access.denied}
 *       (Requirements 13.5, 15.2).</li>
 *   <li><b>Valid token whose role holds the {@code USERS}/{@code READ} grant</b> &rarr; HTTP 200
 *       (Requirements 13.5, 15.3).</li>
 *   <li><b>Valid ADMIN token</b> &rarr; the evaluator's ADMIN bypass yields HTTP 200
 *       (Requirements 13.5, 15.3).</li>
 * </ol>
 *
 * <p>Repeatability: every role/resource/operation code carries a unique per-run suffix (a per-test
 * UUID plus a counter), and {@link #cleanUp()} removes every row the test created after each test,
 * so the suite re-runs without manual DB cleanup. The permission cache is keyed by role code and the
 * per-run role codes never collide across runs, so no stale cached permission set can leak.
 *
 * <p>Validates: Requirements 13.5, 14.1, 14.2, 15.1, 15.2, 15.3
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class FilterChainComposedAuthIntegrationTest {

    /** A protected, matrix-guarded CRUD endpoint resolving to USERS/READ. */
    private static final String USERS_PATH = "/api/users";

    private static final AtomicLong COUNTER = new AtomicLong();

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
    private PermissionCache permissionCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so role/resource/operation codes never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // Bulk deletes require an active transaction; @AfterEach is not test-transaction-managed,
        // so wrap the teardown in an explicit programmatic transaction.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Delete the grants (role_resource_operations) and role_resources owned by this run's
            // roles first (FK order), selecting role_resources via a role-id subquery to avoid an
            // implicit-join delete that PostgreSQL rejects.
            entityManager.createQuery(
                            "delete from RoleResourceEntity rr where rr.role.id in "
                                    + "(select r.id from RoleEntity r where r.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The granted-role fixture seeds resource/operation rows with the exact matrix codes
            // (USERS/READ) so the evaluator's code match succeeds; remove them explicitly here.
            entityManager.createQuery("delete from ResourceEntity res where res.code = 'USERS'")
                    .executeUpdate();
            entityManager.createQuery("delete from OperationEntity op where op.code = 'READ'")
                    .executeUpdate();
        });
    }

    @Test
    @DisplayName("No token on a protected endpoint -> 401 via the filter chain (interceptor never runs)")
    void noToken_yields401() throws Exception {
        MvcResult result = mockMvc.perform(get(USERS_PATH))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unauthenticated request to a protected /api/** endpoint must be rejected "
                        + "with 401 by the SecurityFilterChain")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("Valid token whose role lacks the USERS/READ grant -> 403 error.access.denied (interceptor)")
    void validTokenWithoutGrant_yields403() throws Exception {
        // A real role that exists in the matrix but holds NO grants at all.
        RoleEntity role = persistRole("NO_GRANT");
        String token = jwtTokenProvider.generateAccessToken(
                nextId(), role.getCode(), "no-grant+" + runId + "@example.com");

        MvcResult result = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an authenticated request whose role lacks the USERS/READ grant must be denied "
                        + "with 403 by the PermissionInterceptor")
                .isEqualTo(403);
        // The error pipeline surfaces the *resolved* message text for error.access.denied; with the
        // default (Polish) request locale that is the messages.properties value. Its presence confirms
        // the 403 originated from the access-denied path rather than an unrelated error.
        String denyBody = result.getResponse().getContentAsString();
        assertThat(denyBody)
                .as("403 body must carry the resolved error.access.denied message (PL or RU locale)")
                .satisfiesAnyOf(
                        body -> assertThat(body).contains("Brak wymaganych uprawnień"),
                        body -> assertThat(body).contains("Недостаточно прав"));
    }

    @Test
    @DisplayName("Valid token whose role holds the USERS/READ grant -> 200")
    void validTokenWithGrant_yields200() throws Exception {
        RoleEntity role = persistRoleWithUsersReadGrant();
        String token = jwtTokenProvider.generateAccessToken(
                nextId(), role.getCode(), "granted+" + runId + "@example.com");

        MvcResult result = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an authenticated request whose role holds the USERS/READ grant must reach the "
                        + "controller and return 200")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Valid ADMIN token -> 200 (evaluator ADMIN bypass)")
    void validAdminToken_yields200() throws Exception {
        // ADMIN bypasses the matrix lookup entirely, so no grant rows are needed.
        String token = jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");

        MvcResult result = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an authenticated ADMIN request must reach the controller and return 200")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Persists (and commits) a role with a unique per-run code and no grants, so the committed row
     * is visible to the request-thread transaction that the {@code ForemenPermissionEvaluator}
     * opens on a cache miss.
     */
    private RoleEntity persistRole(String baseCode) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode(baseCode + "_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль " + baseCode);
            role.setNamePL("Rola " + baseCode);
            role.setSystem(false);
            entityManager.persist(role);
            entityManager.flush();
            permissionCache.invalidate(role.getCode());
            return role;
        });
    }

    /**
     * Persists a role granted exactly the USERS/READ pair: a USERS resource row, a READ operation
     * row, and a role_resource linking the two. Codes carry the per-run suffix so the row set is
     * unique per run and removable in {@link #cleanUp()}. Note the evaluator matches on resource
     * and operation *code*, so the codes must be exactly {@code USERS} and {@code READ} for the
     * matrix membership to satisfy the resolved (USERS, READ) pair — the per-run suffix is applied
     * only to the role code, which is what keys the cache.
     */
    private RoleEntity persistRoleWithUsersReadGrant() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ResourceEntity usersResource = new ResourceEntity();
            usersResource.setCode("USERS");
            usersResource.setNameRU("Пользователи " + runId);
            usersResource.setNamePL("Uzytkownicy " + runId);
            entityManager.persist(usersResource);

            OperationEntity readOperation = new OperationEntity();
            readOperation.setCode("READ");
            readOperation.setNameRU("Чтение " + runId);
            readOperation.setNamePL("Odczyt " + runId);
            entityManager.persist(readOperation);

            RoleEntity role = new RoleEntity();
            role.setCode("USERS_READER_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Читатель пользователей");
            role.setNamePL("Czytelnik uzytkownikow");
            role.setSystem(false);

            RoleResourceEntity roleResource = new RoleResourceEntity();
            roleResource.setRole(role);
            roleResource.setResource(usersResource);
            roleResource.setOperations(List.of(readOperation));
            role.getRoleResources().add(roleResource);

            entityManager.persist(role);
            entityManager.flush();
            // Defensive: ensure the cache reloads the freshly seeded permission set for this role code.
            permissionCache.invalidate(role.getCode());
            return role;
        });
    }

    private long nextId() {
        return 900_000L + COUNTER.incrementAndGet();
    }
}
