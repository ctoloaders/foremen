package com.foremen.config.security.integration;

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

/**
 * Integration tests for the Spring Security wiring around the FOR-03-05 OTP endpoints
 * (Requirements 8.1, 8.2).
 *
 * <p>Boots the full application context with the real filter chain against a Testcontainers
 * PostgreSQL instance, following the project's established {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @DynamicPropertySource} pattern (mirrors
 * {@link InviteSecurityWiringIntegrationTest} and {@link SecurityConfigIntegrationTest}). The real
 * security filter chain is exercised through {@link MockMvc}, asserting the authorization decision
 * the chain makes <em>before</em> the request is dispatched to any controller:
 *
 * <ul>
 *   <li>{@code POST /api/auth/otp/request} is public: an unauthenticated request is NOT rejected by
 *       the security layer (not 401/403). It falls under the broad {@code /api/auth/**}
 *       {@code permitAll} rule inherited from FOR-03-01 (8.1, 8.2).</li>
 *   <li>{@code POST /api/auth/otp/verify} is likewise public (8.1, 8.2).</li>
 * </ul>
 *
 * <p><strong>Note on controller mappings:</strong> the {@code AuthController} handler methods for
 * the two OTP paths are wired in tasks 10.2/10.3, which may not be complete when this test runs.
 * This test therefore asserts the <em>security-wiring</em> reachability the filter chain enforces
 * regardless of whether a controller mapping exists: the authorization decision (401/403 vs.
 * pass-through) is made by the {@code AuthorizationFilter} before controller dispatch. For an
 * endpoint that passes the security layer but has no mapping yet, the servlet dispatcher returns
 * 404 — which is neither 401 nor 403 and thus still proves the request was <em>not</em> blocked by
 * security. Assertions are written as "not 401/403" (rather than a specific success status) so they
 * remain correct once the OTP handlers are wired.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class OtpSecurityWiringIntegrationTest {

    private static final String OTP_REQUEST_PATH = "/api/auth/otp/request";
    private static final String OTP_VERIFY_PATH = "/api/auth/otp/verify";

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

    // --- Requirement 8.1, 8.2 : otp/request is public (unauthenticated reaches past the security layer) ---

    @Test
    @DisplayName("POST /api/auth/otp/request is reachable unauthenticated (not blocked by security: not 401/403)")
    void otpRequestIsPublic() throws Exception {
        MvcResult result = mockMvc.perform(post(OTP_REQUEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "client@example.com"}
                                """))
                .andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("otp/request must be public — the security layer must not reject it with 401")
                .isNotEqualTo(401);
        assertThat(statusCode)
                .as("otp/request must be public — the security layer must not reject it with 403")
                .isNotEqualTo(403);
    }

    // --- Requirement 8.1, 8.2 : otp/verify is public (unauthenticated reaches past the security layer) ---

    @Test
    @DisplayName("POST /api/auth/otp/verify is reachable unauthenticated (not blocked by security: not 401/403)")
    void otpVerifyIsPublic() throws Exception {
        MvcResult result = mockMvc.perform(post(OTP_VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "client@example.com", "code": "123456"}
                                """))
                .andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("otp/verify must be public — the security layer must not reject it with 401")
                .isNotEqualTo(401);
        assertThat(statusCode)
                .as("otp/verify must be public — the security layer must not reject it with 403")
                .isNotEqualTo(403);
    }
}
