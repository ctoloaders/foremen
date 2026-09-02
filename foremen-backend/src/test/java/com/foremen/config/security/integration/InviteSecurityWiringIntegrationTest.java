package com.foremen.config.security.integration;

import com.foremen.config.security.JwtTokenProvider;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Spring Security wiring around the FOR-03-02 invite endpoints
 * (Requirements 5.2, 6.2, 6.4, 6.5).
 *
 * <p>Boots the full application context with the real filter chain against a Testcontainers
 * PostgreSQL instance, following the project's established {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @DynamicPropertySource} pattern (mirrors
 * {@link SecurityConfigIntegrationTest}). The real security filter chain is exercised through
 * {@link MockMvc}, asserting the authorization decisions the chain makes <em>before</em> the
 * request is dispatched to any controller:
 *
 * <ul>
 *   <li>{@code POST /api/auth/set-password} is public: an unauthenticated request is NOT rejected
 *       by the security layer (not 401/403). It falls under the broad {@code /api/auth/**}
 *       {@code permitAll} rule (5.2).</li>
 *   <li>{@code POST /api/auth/resend-invite} without a token &rarr; 401 via
 *       {@code JwtAuthenticationEntryPoint} (6.4).</li>
 *   <li>{@code POST /api/auth/resend-invite} with a non-ADMIN Bearer token &rarr; 403 via
 *       {@code AccessDeniedException} handled by {@code ForemenControllerAdvice} (6.5).</li>
 *   <li>{@code POST /api/auth/resend-invite} with a {@code ROLE_ADMIN} Bearer token satisfies the
 *       {@code hasRole("ADMIN")} rule, so the request passes the security layer and is dispatched
 *       past it (NOT 401/403) (6.2).</li>
 * </ul>
 *
 * <p><strong>Note on controller mappings:</strong> the {@code AuthController} handler methods for
 * {@code /api/auth/set-password} and {@code /api/auth/resend-invite} are added in task 9.4, which is
 * not yet complete at the time this test was written. This test therefore asserts the
 * <em>security-wiring</em> reachability that the filter chain enforces regardless of whether a
 * controller mapping exists: the authorization decision (401/403 vs. pass-through) is made by the
 * {@code AuthorizationFilter} before controller dispatch, so the ROLE-based assertions are valid
 * either way. For endpoints that pass the security layer but have no mapping yet, the servlet
 * dispatcher returns 404 — which is neither 401 nor 403 and thus still proves the request was
 * <em>not</em> blocked by security. Assertions are written as "not 401/403" (rather than a specific
 * success status) so they remain correct once task 9.4 wires the handlers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class InviteSecurityWiringIntegrationTest {

    private static final String SET_PASSWORD_PATH = "/api/auth/set-password";
    private static final String RESEND_INVITE_PATH = "/api/auth/resend-invite";

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String NON_ADMIN_ROLE = "WORKER";

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

    // --- Requirement 5.2 : set-password is public (unauthenticated reaches past the security layer) ---

    @Test
    @DisplayName("POST /api/auth/set-password is reachable unauthenticated (not blocked by security: not 401/403)")
    void setPasswordIsPublic() throws Exception {
        MvcResult result = mockMvc.perform(post(SET_PASSWORD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "some-token", "password": "password123"}
                                """))
                .andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("set-password must be public — the security layer must not reject it with 401")
                .isNotEqualTo(401);
        assertThat(statusCode)
                .as("set-password must be public — the security layer must not reject it with 403")
                .isNotEqualTo(403);
    }

    // --- Requirement 6.4 : resend-invite without a token -> 401 ---

    @Test
    @DisplayName("POST /api/auth/resend-invite without a token -> 401 via JwtAuthenticationEntryPoint")
    void resendInviteWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post(RESEND_INVITE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // --- Requirement 6.5 : resend-invite with a non-ADMIN token -> 403 ---

    @Test
    @DisplayName("POST /api/auth/resend-invite with a non-ADMIN Bearer token -> 403")
    void resendInviteWithNonAdminTokenReturns403() throws Exception {
        String nonAdminToken = jwtTokenProvider.generateAccessToken(
                42L, NON_ADMIN_ROLE, "worker@example.com");

        mockMvc.perform(post(RESEND_INVITE_PATH)
                        .header("Authorization", "Bearer " + nonAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1}
                                """))
                .andExpect(status().isForbidden());
    }

    // --- Requirement 6.2 : resend-invite with a ROLE_ADMIN token passes the security layer ---

    @Test
    @DisplayName("POST /api/auth/resend-invite with a ROLE_ADMIN Bearer token passes security (not 401/403)")
    void resendInviteWithAdminTokenPassesSecurity() throws Exception {
        String adminToken = jwtTokenProvider.generateAccessToken(
                1L, ADMIN_ROLE, "admin@example.com");

        MvcResult result = mockMvc.perform(post(RESEND_INVITE_PATH)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1}
                                """))
                .andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("a ROLE_ADMIN token satisfies hasRole(\"ADMIN\") — the request must not be rejected with 401")
                .isNotEqualTo(401);
        assertThat(statusCode)
                .as("a ROLE_ADMIN token satisfies hasRole(\"ADMIN\") — the request must not be rejected with 403")
                .isNotEqualTo(403);
    }
}
