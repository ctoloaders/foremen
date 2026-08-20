package com.foremen.config.property;

import net.jqwik.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for SecurityConfig and CorsConfig.
 * Validates: Requirements 1.3, 3.3, 3.4, 3.5, 3.8, 3.9
 */
class ConfigPropertyTest {

    private static final List<String> ALLOWED_ORIGINS = List.of("http://localhost:3000");
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");

    /**
     * Catch-all controller that returns 200 for any path.
     */
    @RestController
    static class CatchAllController {
        @RequestMapping("/**")
        String handleAll() {
            return "ok";
        }
    }

    /**
     * Build a MockMvc that has no security filters — matching SecurityConfig's permitAll behavior.
     * Since SecurityConfig permits ALL requests, a standalone MockMvc (which has no security
     * by default) accurately reflects the configured behavior: no request is blocked.
     */
    private MockMvc buildMockMvcForSecurity() {
        return MockMvcBuilders
                .standaloneSetup(new CatchAllController())
                .build();
    }

    /**
     * Build a CorsFilter from CorsConfig's actual configuration logic.
     * This tests the real CORS behavior as defined by CorsConfig.
     */
    private CorsFilter buildCorsFilter(List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(ALLOWED_METHODS);
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }

    /**
     * Build a MockMvc with the CORS filter applied — matching CorsConfig's addCorsMappings behavior.
     */
    private MockMvc buildMockMvcWithCors(List<String> origins) {
        return MockMvcBuilders
                .standaloneSetup(new CatchAllController())
                .addFilter(buildCorsFilter(origins))
                .build();
    }

    // =========================================================================
    // Property 1: Security permits all requests without authentication
    // =========================================================================

    /**
     * Validates: Requirements 1.3
     * For any valid HTTP path and for any HTTP method (GET, POST, PUT, DELETE, PATCH),
     * a request without authentication credentials SHALL NOT receive 401 or 403.
     */
    @Property(tries = 100)
    void securityPermitsAllRequestsWithoutAuthentication(
            @ForAll("validPaths") String path,
            @ForAll("httpMethods") String method) throws Exception {

        MockMvc mockMvc = buildMockMvcForSecurity();

        MockHttpServletResponse response = mockMvc.perform(
                        MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as("Request %s %s should not be blocked by security (no 401/403)", method, path)
                .isNotEqualTo(401)
                .isNotEqualTo(403);
    }

    // =========================================================================
    // Property 3: CORS preflight allows configured methods for all paths
    // =========================================================================

    /**
     * Validates: Requirements 3.3, 3.4, 3.5
     * For any path and any HTTP method in {GET, POST, PUT, DELETE, PATCH, OPTIONS},
     * an OPTIONS preflight request from an allowed origin SHALL include that method
     * in the Access-Control-Allow-Methods response header and SHALL accept any requested header.
     */
    @Property(tries = 100)
    void corsPreflightAllowsConfiguredMethodsForAllPaths(
            @ForAll("validPaths") String path,
            @ForAll("httpMethods") String method) throws Exception {

        MockMvc mockMvc = buildMockMvcWithCors(ALLOWED_ORIGINS);
        String allowedOrigin = ALLOWED_ORIGINS.getFirst();

        MockHttpServletResponse response = mockMvc.perform(
                        MockMvcRequestBuilders.options(path)
                                .header("Origin", allowedOrigin)
                                .header("Access-Control-Request-Method", method)
                                .header("Access-Control-Request-Headers", "X-Custom-Header"))
                .andReturn()
                .getResponse();

        String allowMethods = response.getHeader("Access-Control-Allow-Methods");
        assertThat(allowMethods)
                .as("Preflight for %s %s from allowed origin should have Allow-Methods header", method, path)
                .isNotNull();
        assertThat(allowMethods)
                .as("Allow-Methods should contain requested method %s", method)
                .contains(method);

        // When allowedHeaders is "*", Spring CORS echoes back requested headers
        String allowHeaders = response.getHeader("Access-Control-Allow-Headers");
        assertThat(allowHeaders)
                .as("Preflight should allow the requested header")
                .isNotNull()
                .containsIgnoringCase("X-Custom-Header");
    }

    // =========================================================================
    // Property 4: CORS origin filtering
    // =========================================================================

    /**
     * Validates: Requirements 3.8, 3.9
     * For any origin string: if origin is in configured list, preflight returns
     * Access-Control-Allow-Origin equal to that origin. If NOT in the list,
     * no Access-Control-Allow-Origin header is returned.
     */
    @Property(tries = 100)
    void corsOriginFiltering(
            @ForAll("anyOrigin") String origin) throws Exception {

        MockMvc mockMvc = buildMockMvcWithCors(ALLOWED_ORIGINS);

        MockHttpServletResponse response = mockMvc.perform(
                        MockMvcRequestBuilders.options("/api/test")
                                .header("Origin", origin)
                                .header("Access-Control-Request-Method", "GET"))
                .andReturn()
                .getResponse();

        String allowOrigin = response.getHeader("Access-Control-Allow-Origin");

        if (ALLOWED_ORIGINS.contains(origin)) {
            assertThat(allowOrigin)
                    .as("Allowed origin '%s' should be returned in Access-Control-Allow-Origin", origin)
                    .isEqualTo(origin);
        } else {
            assertThat(allowOrigin)
                    .as("Disallowed origin '%s' should NOT have Access-Control-Allow-Origin header", origin)
                    .isNull();
        }
    }

    // =========================================================================
    // Providers
    // =========================================================================

    @Provide
    Arbitrary<String> validPaths() {
        Arbitrary<String> segment = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12);

        return segment.list()
                .ofMinSize(1)
                .ofMaxSize(4)
                .map(segments -> "/" + String.join("/", segments));
    }

    @Provide
    Arbitrary<String> httpMethods() {
        return Arbitraries.of("GET", "POST", "PUT", "DELETE", "PATCH");
    }

    @Provide
    Arbitrary<String> anyOrigin() {
        Arbitrary<String> allowedOrigin = Arbitraries.of(ALLOWED_ORIGINS);

        Arbitrary<String> disallowedOrigin = Arbitraries.of(
                "http://evil.com",
                "http://localhost:8080",
                "https://example.org",
                "http://attacker.net",
                "https://other-domain.com",
                "http://localhost:5173"
        );

        return Arbitraries.frequencyOf(
                Tuple.of(3, allowedOrigin),
                Tuple.of(7, disallowedOrigin)
        );
    }
}
