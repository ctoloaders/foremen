package com.foremen.config.web;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.security.JwtAuthenticationEntryPoint;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.SecurityConfig;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for CorsConfig — verifies CORS preflight behavior:
 * - Allowed origin receives correct CORS headers (Requirements 3.3, 3.4, 3.5, 3.6, 3.7)
 * - All 6 HTTP methods are in Access-Control-Allow-Methods (Requirement 3.4)
 * - Credentials allowed (Requirement 3.6)
 * - Max-age is 3600 (Requirement 3.7)
 * - Disallowed origin gets no CORS headers (Requirements 3.8, 3.9)
 */
@WebMvcTest(controllers = CorsConfigTest.TestController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class, PermissionResolver.class})
class CorsConfigTest {

    @MockitoBean
    private MessageResolver messageResolver;

    @MockitoBean
    private ForemenPermissionEvaluator permissionEvaluator;

    @RestController
    @RequestMapping("/test/cors")
    static class TestController {

        @GetMapping
        String handle() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    // --- Requirements 3.3, 3.8: Allowed origin receives Access-Control-Allow-Origin ---

    @Test
    @DisplayName("OPTIONS preflight from allowed origin returns Access-Control-Allow-Origin header")
    void preflightFromAllowedOriginReturnsAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/test/cors")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
    }

    // --- Requirement 3.4: All 6 methods in Access-Control-Allow-Methods ---

    @Test
    @DisplayName("OPTIONS preflight returns all 6 allowed methods in Access-Control-Allow-Methods")
    void preflightReturnsAllAllowedMethods() throws Exception {
        mockMvc.perform(options("/test/cors")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("PUT")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("DELETE")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("OPTIONS")));
    }

    // --- Requirement 3.6: Credentials allowed ---

    @Test
    @DisplayName("OPTIONS preflight returns Access-Control-Allow-Credentials: true")
    void preflightReturnsAllowCredentialsTrue() throws Exception {
        mockMvc.perform(options("/test/cors")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    // --- Requirement 3.7: Max-age is 3600 ---

    @Test
    @DisplayName("OPTIONS preflight returns Access-Control-Max-Age: 3600")
    void preflightReturnsMaxAge3600() throws Exception {
        mockMvc.perform(options("/test/cors")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
    }

    // --- Requirements 3.8, 3.9: Disallowed origin gets no CORS headers ---

    @Test
    @DisplayName("OPTIONS preflight from disallowed origin does not return Access-Control-Allow-Origin")
    void preflightFromDisallowedOriginGetsNoCorsHeaders() throws Exception {
        mockMvc.perform(options("/test/cors")
                        .header(HttpHeaders.ORIGIN, "http://evil.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
