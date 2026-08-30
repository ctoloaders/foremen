package com.foremen.config.security.integration;

import com.foremen.config.security.JwtAuthenticationFilter;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Spring Security wiring around the authentication endpoints
 * (Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 9.1, 9.3).
 *
 * <p>Boots the full application context with the real filter chain against a Testcontainers
 * PostgreSQL instance, following the project's established {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @DynamicPropertySource} pattern (Hibernate builds the schema via
 * {@code ddl-auto=create-drop}, as in {@code PermissionManagementIntegrationTest} and
 * {@code RoleControllerIntegrationTest}). The real
 * {@link org.springframework.security.web.SecurityFilterChain} is exercised through
 * {@link MockMvc}:
 *
 * <ul>
 *   <li>{@code /api/auth/login} is reachable unauthenticated — the request reaches
 *       {@code AuthService} (proven by a 401 {@code error.auth.invalid.credentials}, not the
 *       security-layer {@code error.auth.unauthorized}) (6.1, 6.5).</li>
 *   <li>{@code /api/auth/me} returns 401 without a token (via {@code JwtAuthenticationEntryPoint})
 *       and 200 with a valid Bearer token (6.4, 9.1, 9.3).</li>
 *   <li>The session policy is STATELESS — no {@code JSESSIONID} cookie is set and no security
 *       context is persisted into an HTTP session (6.3).</li>
 *   <li>CSRF is disabled — a POST to a permitAll endpoint without a CSRF token is not rejected with
 *       403 (6.5).</li>
 *   <li>{@code JwtAuthenticationFilter} precedes {@code UsernamePasswordAuthenticationFilter} in the
 *       filter chain (6.2).</li>
 * </ul>
 *
 * <p>The ACTIVE user is seeded with a native insert that casts the {@code display_preferences}
 * value to {@code jsonb}, avoiding the type mismatch that arises when the JPA converter binds a
 * {@code null} JSON value against a Hibernate-generated {@code jsonb} column.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class SecurityConfigIntegrationTest {

    private static final String USER_ROLE = "WORKER";

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
    private FilterChainProxy filterChainProxy;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RoleDao roleDao;

    @PersistenceContext
    private EntityManager entityManager;

    private Long activeUserId;
    private String activeUserEmail;

    @BeforeEach
    void seedActiveUser() {
        RoleEntity role = roleDao.findByCode(USER_ROLE).orElseGet(() -> {
            RoleEntity r = new RoleEntity();
            r.setCode(USER_ROLE);
            r.setNameRU("Рабочий");
            r.setNamePL("Pracownik");
            r.setSystem(true);
            return roleDao.save(r);
        });
        entityManager.flush();

        activeUserEmail = "sec-config+" + UUID.randomUUID() + "@example.com";

        // Native insert casting display_preferences to jsonb, avoiding the converter/jsonb bind
        // mismatch on a Hibernate-generated column.
        entityManager.createNativeQuery("""
                        INSERT INTO users (name, email, role_id, active, status, locale,
                                           display_preferences, created_date)
                        VALUES (:name, :email, :roleId, true, 'ACTIVE', 'ru',
                                CAST(NULL AS jsonb), NOW())
                        """)
                .setParameter("name", "Active Tester")
                .setParameter("email", activeUserEmail)
                .setParameter("roleId", role.getId())
                .executeUpdate();
        entityManager.flush();

        activeUserId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", activeUserEmail)
                .getSingleResult()).longValue();
    }

    // --- Requirement 6.1 / 6.5 : /api/auth/login is public and reaches the service ---

    @Test
    @DisplayName("POST /api/auth/login is reachable unauthenticated and reaches AuthService (401 invalid.credentials, not the entrypoint 401)")
    void loginIsPublicAndReachesService() throws Exception {
        // A login for a non-existent user must reach AuthService, which rejects it with
        // error.auth.invalid.credentials. Crucially, this is NOT the security-layer
        // error.auth.unauthorized 401 that would prove the endpoint was blocked as unauthenticated.
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "does.not.exist@example.com", "password": "whatever-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("login should reach AuthService, not be blocked by the security entrypoint")
                .doesNotContain("error.auth.unauthorized");
        assertThat(body).contains("401");
    }

    // --- Requirement 6.4 / 9.3 : /api/auth/me requires authentication ---

    @Test
    @DisplayName("GET /api/auth/me without a token → 401 via JwtAuthenticationEntryPoint")
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/auth/me with an invalid Bearer token → 401")
    void meWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // --- Requirement 9.1 : /api/auth/me with a valid token returns the identity ---

    @Test
    @DisplayName("GET /api/auth/me with a valid Bearer token → 200 with the user's identity")
    void meWithValidTokenReturns200() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(activeUserId, USER_ROLE, activeUserEmail);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeUserId))
                .andExpect(jsonPath("$.email").value(activeUserEmail))
                .andExpect(jsonPath("$.roleCode").value(USER_ROLE));
    }

    // --- Requirement 6.3 : STATELESS session ---

    @Test
    @DisplayName("A public request creates no HTTP session (STATELESS) — no Set-Cookie")
    void publicRequestIsStateless() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "does.not.exist@example.com", "password": "whatever"}
                                """))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("An authenticated request does not persist a security context into an HTTP session (STATELESS)")
    void authenticatedRequestPersistsNoSecurityContext() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(activeUserId, USER_ROLE, activeUserEmail);

        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        // Under STATELESS policy the SecurityContext is never saved into the HTTP session, so even
        // if a session exists it must not carry the Spring Security context attribute.
        var session = result.getRequest().getSession(false);
        if (session != null) {
            assertThat(session.getAttribute(SPRING_SECURITY_CONTEXT_KEY))
                    .as("STATELESS policy must not persist the SecurityContext into the HTTP session")
                    .isNull();
        }
    }

    // --- Requirement 6.5 : CSRF disabled (POST without CSRF token is not rejected) ---

    @Test
    @DisplayName("CSRF is disabled: POST to a permitAll endpoint without a CSRF token is not 403")
    void csrfDisabledAllowsPostWithoutToken() throws Exception {
        // With CSRF enabled, an unsafe method (POST) with no CSRF token would yield 403.
        // Here the request reaches the service and returns a business 401 (invalid.credentials),
        // proving CSRF is off — the POST was not rejected by CSRF protection.
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "does.not.exist@example.com", "password": "whatever"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("CSRF-disabled POST must not be rejected with 403")
                .isNotEqualTo(403);
    }

    // --- Requirement 6.2 : filter ordering ---

    @Test
    @DisplayName("JwtAuthenticationFilter is registered before UsernamePasswordAuthenticationFilter (or the position it would occupy)")
    void jwtFilterPrecedesUsernamePasswordFilter() {
        List<Filter> filters = resolveApiAuthFilters();

        int jwtIndex = indexOfFilter(filters, JwtAuthenticationFilter.class);
        assertThat(jwtIndex)
                .as("JwtAuthenticationFilter must be present in the security filter chain")
                .isGreaterThanOrEqualTo(0);

        int upIndex = indexOfFilter(filters, UsernamePasswordAuthenticationFilter.class);
        if (upIndex >= 0) {
            // Form login is present in the chain: assert the direct ordering (Requirement 6.2).
            assertThat(jwtIndex)
                    .as("JwtAuthenticationFilter must precede UsernamePasswordAuthenticationFilter")
                    .isLessThan(upIndex);
        } else {
            // With this stateless configuration Spring Security does not add
            // UsernamePasswordAuthenticationFilter to the chain (no form login). The
            // addFilterBefore(..., UsernamePasswordAuthenticationFilter.class) registration still
            // guarantees the JWT filter runs before the standard authentication-processing slot,
            // i.e. before the AuthorizationFilter that enforces access rules. Asserting that
            // ordering is the robust equivalent of Requirement 6.2 here.
            int authorizationIndex = indexOfFilterByName(filters, "AuthorizationFilter");
            assertThat(authorizationIndex)
                    .as("AuthorizationFilter must be present to anchor the ordering check")
                    .isGreaterThanOrEqualTo(0);
            assertThat(jwtIndex)
                    .as("JwtAuthenticationFilter must run before the authorization decision "
                            + "(the UsernamePasswordAuthenticationFilter slot)")
                    .isLessThan(authorizationIndex);
        }
    }

    /**
     * Resolves the ordered filter list for the security chain that matches {@code /api/auth/me}.
     */
    private List<Filter> resolveApiAuthFilters() {
        List<Filter> filters = filterChainProxy.getFilters("/api/auth/me");
        if (filters == null || filters.isEmpty()) {
            List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();
            filters = chains.get(0).getFilters();
        }
        return filters;
    }

    private static int indexOfFilter(List<Filter> filters, Class<? extends Filter> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfFilterByName(List<Filter> filters, String simpleName) {
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i).getClass().getSimpleName().equals(simpleName)) {
                return i;
            }
        }
        return -1;
    }
}
