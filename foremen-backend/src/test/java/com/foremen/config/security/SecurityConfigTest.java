package com.foremen.config.security;

import com.foremen.config.i18n.MessageResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for SecurityConfig — verifies the security filter chain behavior:
 * - All HTTP methods are permitted without authentication (Requirement 1.3)
 * - CSRF protection is disabled (Requirement 1.4)
 * - Sessions are stateless — no Set-Cookie header (Requirement 1.5)
 * - X-Frame-Options header is disabled (Requirement 1.6)
 */
@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        SecurityConfigTest.TestController.class})
class SecurityConfigTest {

    @MockitoBean
    private MessageResolver messageResolver;

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

    // --- Requirement 1.3: All requests permitted without authentication ---

    @Test
    @DisplayName("GET request without credentials returns non-401/403")
    void getRequestPermittedWithoutAuth() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST request without credentials returns non-401/403")
    void postRequestPermittedWithoutAuth() throws Exception {
        mockMvc.perform(post("/security-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT request without credentials returns non-401/403")
    void putRequestPermittedWithoutAuth() throws Exception {
        mockMvc.perform(put("/security-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE request without credentials returns non-401/403")
    void deleteRequestPermittedWithoutAuth() throws Exception {
        mockMvc.perform(delete("/security-test"))
                .andExpect(status().isOk());
    }

    // --- Requirement 1.4: CSRF disabled ---

    @Test
    @DisplayName("POST without CSRF token is not rejected (CSRF disabled)")
    void postWithoutCsrfTokenNotRejected() throws Exception {
        mockMvc.perform(post("/security-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"value\"}"))
                .andExpect(status().isOk());
    }

    // --- Requirement 1.5: Stateless sessions — no Set-Cookie ---

    @Test
    @DisplayName("Response does not contain Set-Cookie header (stateless session)")
    void responseDoesNotContainSetCookie() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // --- Requirement 1.6: X-Frame-Options disabled ---

    @Test
    @DisplayName("Response does not contain X-Frame-Options header (frameOptions disabled)")
    void responseDoesNotContainXFrameOptions() throws Exception {
        mockMvc.perform(get("/security-test"))
                .andExpect(header().doesNotExist("X-Frame-Options"));
    }
}
