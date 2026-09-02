package com.foremen.config.security.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.service.RoleService;
import com.foremen.testsupport.DemoPermissionController;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for the {@code @RequiresPermission} enforcement path
 * (FOR-03-03 task 8.2).
 *
 * <p>Boots the full application context with the real Spring Security filter chain and the
 * {@code PermissionInterceptor} against a Testcontainers PostgreSQL instance, following the
 * project's established {@code @SpringBootTest} + {@code @Testcontainers} +
 * {@code @DynamicPropertySource} pattern (mirrors {@link SecurityConfigIntegrationTest} and
 * {@link InviteSecurityWiringIntegrationTest}). Requests hit the test-only
 * {@link DemoPermissionController} ({@code GET /demo}, requires {@code (PROJECTS, READ)}), created
 * in task 8.1, through {@link MockMvc} so the interceptor runs exactly as it would for a real HTTP
 * request.
 *
 * <p>The role &times; resource &times; operations matrix is seeded directly via the JPA layer
 * (following {@link com.foremen.controller.integration.PermissionManagementIntegrationTest}); JWTs
 * are minted with {@link JwtTokenProvider} so the {@code JwtAuthenticationFilter} populates the
 * {@code SecurityContext} with the {@code ROLE_<code>} authority the interceptor reads.
 *
 * <p>Covered acceptance criteria:
 * <ul>
 *   <li>Authorized role holding the permission &rarr; 200 and the controller body executes
 *       (5.1, 11.2).</li>
 *   <li>Role lacking the permission &rarr; 403 with body code {@code error.access.denied} and the
 *       controller body does not execute (5.2, 6.1, 6.2, 11.3).</li>
 *   <li>Unauthenticated request to the annotated endpoint &rarr; 401 (7.1, 7.2).</li>
 *   <li>ADMIN role &rarr; 200 regardless of the matrix (3.1).</li>
 *   <li>{@code Accept-Language} pl vs ru yields different 403 message text (10.3).</li>
 *   <li>Role deletion invalidates the cache so a subsequently-denied role gets 403 (9.2).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class DemoPermissionEndpointIntegrationTest {

    private static final String DEMO_PATH = "/demo";
    private static final String ADMIN_ROLE = "ADMIN";

    // Exact strings from messages.properties (PL base) / messages_ru.properties for
    // error.access.denied.
    private static final String PL_ACCESS_DENIED = "Dostęp zabroniony. Brak wymaganych uprawnień.";
    private static final String RU_ACCESS_DENIED = "Доступ запрещён. Недостаточно прав.";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

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
    private RoleService roleService;

    @PersistenceContext
    private EntityManager entityManager;

    private ResourceEntity projectsResource;
    private OperationEntity readOperation;

    @BeforeEach
    void seedResourceAndOperation() {
        // The demonstrative endpoint requires (PROJECTS, READ). Seed those matrix building blocks
        // once per test; each test then creates its own uniquely-coded role so scenarios stay
        // isolated and repeatable (@Transactional rolls everything back after each test).
        projectsResource = createResource("PROJECTS");
        readOperation = createOperation("READ");
        entityManager.flush();
    }

    // --- 5.1 / 11.2 : authorized role holding the permission -> 200 and body executes ---

    @Test
    @DisplayName("GET /demo with a role holding (PROJECTS, READ) -> 200 and the controller body executes")
    void authorizedRoleReturns200AndBody() throws Exception {
        RoleEntity role = createRoleWithProjectsRead("DEMO_ALLOWED");
        entityManager.flush();
        String token = tokenFor(role.getCode());

        mockMvc.perform(get(DEMO_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(DemoPermissionController.OK_BODY));
    }

    // --- 5.2 / 6.1 / 6.2 / 11.3 : role lacking the permission -> 403 error.access.denied, body NOT executed ---

    @Test
    @DisplayName("GET /demo with a role lacking (PROJECTS, READ) -> 403 error.access.denied and the body does not execute")
    void unauthorizedRoleReturns403AndDoesNotExecuteBody() throws Exception {
        // A role with no matrix entry for (PROJECTS, READ).
        RoleEntity role = createRole("DEMO_DENIED");
        entityManager.flush();
        String token = tokenFor(role.getCode());

        mockMvc.perform(get(DEMO_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                // The interceptor prevented the controller from running, so the OK body is absent.
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        DemoPermissionController.OK_BODY))))
                // The localized message for error.access.denied is present in the body (default
                // locale is Polish). This proves the code resolved through ForemenControllerAdvice.
                .andExpect(jsonPath("$.message").value(PL_ACCESS_DENIED));
    }

    // --- 7.1 / 7.2 : unauthenticated request to the annotated endpoint -> 401 ---

    @Test
    @DisplayName("GET /demo without a token -> 401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get(DEMO_PATH))
                .andExpect(status().isUnauthorized());
    }

    // --- 3.1 : ADMIN role -> 200 regardless of the matrix ---

    @Test
    @DisplayName("GET /demo as ADMIN -> 200 regardless of the matrix (ADMIN bypass)")
    void adminRoleReturns200RegardlessOfMatrix() throws Exception {
        // Ensure an ADMIN role exists but grant it NO permissions on PROJECTS/READ, so a 200 can
        // only come from the ADMIN bypass, not from a matrix grant.
        roleDao.findByCode(ADMIN_ROLE).orElseGet(() -> {
            RoleEntity admin = new RoleEntity();
            admin.setCode(ADMIN_ROLE);
            admin.setNameRU("Администратор");
            admin.setNamePL("Administrator");
            admin.setSystem(true);
            return roleDao.save(admin);
        });
        entityManager.flush();
        String token = tokenFor(ADMIN_ROLE);

        mockMvc.perform(get(DEMO_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(DemoPermissionController.OK_BODY));
    }

    // --- 10.3 : Accept-Language pl vs ru yields different 403 message text ---

    @Test
    @DisplayName("GET /demo denied with Accept-Language pl vs ru -> different localized 403 message text")
    void deniedMessageIsLocalizedPerRequestLocale() throws Exception {
        RoleEntity role = createRole("DEMO_L10N");
        entityManager.flush();
        String token = tokenFor(role.getCode());

        mockMvc.perform(get(DEMO_PATH)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept-Language", "pl"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(PL_ACCESS_DENIED));

        mockMvc.perform(get(DEMO_PATH)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept-Language", "ru"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(RU_ACCESS_DENIED));
    }

    // --- 9.2 : role deletion invalidates the cache so a subsequently-denied role gets 403 ---

    @Test
    @DisplayName("Role deletion invalidates the permission cache: an allowed role becomes 403 after its role is deleted and re-created without the grant")
    void roleDeletionInvalidatesCacheSoNextEvaluationDenies() throws Exception {
        // 1) Create a role holding (PROJECTS, READ); first call is 200 and caches its permission set.
        String code = "DEMO_INVALIDATE_" + UUID.randomUUID().toString().substring(0, 8);
        RoleEntity role = createRoleWithProjectsRead(code);
        entityManager.flush();
        String token = tokenFor(code);

        mockMvc.perform(get(DEMO_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(DemoPermissionController.OK_BODY));

        // 2) Delete the role via RoleService.deleteById, which evicts the cache entry keyed by the
        //    role code captured before deletion (Requirement 9.2).
        Long roleId = role.getId();
        roleService.deleteById(roleId);
        entityManager.flush();
        entityManager.clear();

        // 3) Re-create a role with the SAME code but WITHOUT the (PROJECTS, READ) grant. Because the
        //    cache was invalidated on delete, the next evaluation reloads from the DB and finds the
        //    empty matrix -> deny -> 403. (afterCreate also defensively evicts on re-create.)
        createRole(code);
        entityManager.flush();

        mockMvc.perform(get(DEMO_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // --- helpers ---

    private String tokenFor(String roleCode) {
        return jwtTokenProvider.generateAccessToken(1L, roleCode, roleCode.toLowerCase() + "@example.com");
    }

    private RoleEntity createRole(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU("Роль " + code);
        role.setNamePL("Rola " + code);
        role.setSystem(false);
        return roleDao.save(role);
    }

    private RoleEntity createRoleWithProjectsRead(String code) {
        RoleEntity role = createRole(code);
        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setRole(role);
        rr.setResource(projectsResource);
        rr.getOperations().add(readOperation);
        role.getRoleResources().add(rr);
        return roleDao.save(role);
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
