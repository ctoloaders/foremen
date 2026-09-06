package com.foremen.controller.integration;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the public-auth matchers and the {@code SecurityConfig} matcher order after
 * the FOR-03-08 filter-chain migration ({@code anyRequest().permitAll()} → {@code
 * anyRequest().authenticated()}).
 *
 * <p>These tests exercise the <b>real</b> {@code SecurityFilterChain} over the full MVC + Spring
 * Security context (no {@code @WithMockUser} at class level, so every request below is
 * <b>unauthenticated</b>). They assert that the specific auth matchers declared before the migrated
 * catch-all still take precedence:
 * <ul>
 *   <li>{@code /api/auth/**} ({@code permitAll}) stays reachable without a principal — the filter
 *       chain does NOT short-circuit with 401; the request reaches its handler (13.6, 14.3, 15.4,
 *       16.3).</li>
 *   <li>{@code GET /api/auth/me} ({@code authenticated}) yields 401 when unauthenticated, proving
 *       the specific {@code /api/auth/me} matcher wins over the broad {@code /api/auth/**}
 *       {@code permitAll} declared after it (13.6, 14.4, 14.6).</li>
 *   <li>{@code POST /api/auth/resend-invite} ({@code hasRole('ADMIN')}) yields 401 when
 *       unauthenticated, proving the specific {@code POST /api/auth/resend-invite} matcher wins over
 *       the broad {@code /api/auth/**} {@code permitAll} declared after it (13.6, 14.5, 14.6).</li>
 * </ul>
 *
 * <p>Validates: Requirements 13.6, 14.3, 14.4, 14.5, 14.6, 15.4, 16.3
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class PublicAuthMatcherOrderIntegrationTest {

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

    // --- /api/auth/** stays Filter_Chain_Public (reaches handler, NOT filter-chain 401) ---

    @Test
    @DisplayName("Unauthenticated POST /api/auth/password-reset/request → reaches handler (200), NOT filter-chain 401")
    void publicAuthEndpoint_unauthenticated_reachesHandler_notRejectedWith401() throws Exception {
        // POST /api/auth/password-reset/request is matched by the broad /api/auth/** permitAll rule.
        // It is an anti-enumeration endpoint that always returns HTTP 200 regardless of whether the
        // email exists, so a 200 here unambiguously proves the request reached the controller rather
        // than being short-circuited by the filter chain. The key assertion is "not 401": the
        // migrated anyRequest().authenticated() catch-all did NOT reject this public path.
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(status().is(not(401)));
    }

    @Test
    @DisplayName("Unauthenticated POST /api/auth/login with bad body → 400 from handler, NOT filter-chain 401")
    void publicLoginEndpoint_unauthenticated_reachesHandler_notRejectedWith401() throws Exception {
        // POST /api/auth/login is also matched by /api/auth/** permitAll. A blank email trips
        // @Valid → HTTP 400 from the MVC layer (the request reached the controller/validation), so
        // the response is NOT the filter-chain 401. Asserting 400 (a handler-produced status) and
        // "not 401" proves the public matcher let the unauthenticated request through.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "   ", "password": "password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(status().is(not(401)));
    }

    // --- /api/auth/me stays authenticated() (specific matcher wins over /api/auth/** permitAll) ---

    @Test
    @DisplayName("Unauthenticated GET /api/auth/me → 401 (specific matcher precedes /api/auth/** permitAll)")
    void meEndpoint_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /api/auth/resend-invite stays hasRole(ADMIN) (specific matcher wins over permitAll) ---

    @Test
    @DisplayName("Unauthenticated POST /api/auth/resend-invite → 401 (specific matcher precedes /api/auth/** permitAll)")
    void resendInviteEndpoint_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/resend-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
