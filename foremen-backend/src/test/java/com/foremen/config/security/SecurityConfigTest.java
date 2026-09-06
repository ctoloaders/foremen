package com.foremen.config.security;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for SecurityConfig after the FOR-03-08 catch-all migration
 * ({@code anyRequest().authenticated()}).
 *
 * <ul>
 *   <li>Unauthenticated requests to a non-public endpoint are rejected with 401 via the
 *       {@link JwtAuthenticationEntryPoint}.</li>
 *   <li>An authenticated principal reaches the controller (CSRF disabled, stateless session,
 *       frame options disabled).</li>
 * </ul>
 */
@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        PermissionResolver.class, MockMvcSecurityConfig.class, SecurityConfigTest.TestController.class})
class SecurityConfigTest {

    @MockitoBean
    private MessageResolver messageResolver;

    @MockitoBean
    private ForemenPermissionEvaluator permissionEvaluator;

    @RestController
    @RequestMapping("/security-test")
    static class TestController {

        @GetMapping
        String handleGet() {
            return "get-ok";
        }

        @PostMapping
        String handlePost(@RequestBody(required = false) String body) {
            return "post-ok";
        }

        @PutMapping
        String handlePut(@RequestBody(required = false) String body) {
            return "put-ok";
        }

        @DeleteMapping
        String handleDelete() {
            return "delete-ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    // --- FOR-03-08: catch-all requires authentication; unauthenticated -> 401 ---

    @Test
    @DisplayName("GET request without credentials returns 401")
    void getRequestWithoutAuthReturns401() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST request without credentials returns 401")
    void postRequestWithoutAuthReturns401() throws Exception {
        mockMvc.perform(post("/security-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT request without credentials returns 401")
    void putRequestWithoutAuthReturns401() throws Exception {
        mockMvc.perform(put("/security-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE request without credentials returns 401")
    void deleteRequestWithoutAuthReturns401() throws Exception {
        mockMvc.perform(delete("/security-test"))
                .andExpect(status().isUnauthorized());
    }

    // --- Authenticated principal reaches the controller ---

    @Test
    @DisplayName("GET with an authenticated principal reaches the controller")
    @WithMockUser(roles = "ADMIN")
    void getRequestAuthenticatedReachesController() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(status().isOk());
    }

    // --- Requirement 1.4: CSRF disabled ---

    @Test
    @DisplayName("POST without CSRF token is not rejected (CSRF disabled)")
    @WithMockUser(roles = "ADMIN")
    void postWithoutCsrfTokenNotRejected() throws Exception {
        mockMvc.perform(post("/security-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"value\"}"))
                .andExpect(status().isOk());
    }

    // --- Requirement 1.5: Stateless sessions — no Set-Cookie ---

    @Test
    @DisplayName("Response does not contain Set-Cookie header (stateless session)")
    @WithMockUser(roles = "ADMIN")
    void responseDoesNotContainSetCookie() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // --- Requirement 1.6: X-Frame-Options disabled ---

    @Test
    @DisplayName("Response does not contain X-Frame-Options header (frameOptions disabled)")
    @WithMockUser(roles = "ADMIN")
    void responseDoesNotContainXFrameOptions() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(header().doesNotExist("X-Frame-Options"));
    }
}
