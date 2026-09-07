package com.foremen.config.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for stateless JWT authentication (Requirement 6).
 *
 * <p>The filter chain is stateless (no HTTP session), CSRF is disabled (consistent with the
 * Bearer-token model), and frame options are disabled. The {@link JwtAuthenticationFilter} is
 * registered before {@link UsernamePasswordAuthenticationFilter} so a valid access token
 * populates the {@code SecurityContext} before form-login processing (6.2), and
 * {@link JwtAuthenticationEntryPoint} produces the HTTP 401 body for unauthenticated access to
 * protected endpoints (6.4).
 *
 * <p>Authorization ordering: {@code /api/auth/me} requires authentication and is matched BEFORE
 * the broader {@code /api/auth/**} {@code permitAll} rule so that login/refresh/logout/password-reset
 * stay public while {@code /me} is protected (6.1, 9.3). The invite resend endpoint
 * {@code POST /api/auth/resend-invite} requires {@code ROLE_ADMIN} and is matched BEFORE the
 * broad {@code /api/auth/**} {@code permitAll} rule so that an unauthenticated caller gets 401
 * and a non-ADMIN caller gets 403, while {@code /api/auth/set-password} stays public under the
 * broad rule (FOR-03-02: 5.2, 6.2, 6.4, 6.5).
 *
 * <p>FOR-03-08: the catch-all is {@code anyRequest().authenticated()} — every path outside the
 * public {@code /api/auth/**} matchers now requires an authenticated principal (unauthenticated
 * access yields 401 via {@link JwtAuthenticationEntryPoint}). CORS preflight ({@code OPTIONS})
 * requests are permitted first via {@link org.springframework.web.cors.CorsUtils#isPreFlightRequest}
 * so the browser preflight is never rejected with 401.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, GoogleProperties.class, GooglePlacesProperties.class})
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    /**
     * The {@link JwtAuthenticationFilter} is instantiated here (not annotated as a
     * {@code @Component}) so it is registered exactly once, via {@code addFilterBefore}, and not
     * also auto-registered by the servlet container.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtTokenProvider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.web.cors.CorsUtils::isPreFlightRequest).permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/resend-invite").hasRole("ADMIN")
                        // FOR-03-05 (8.1, 8.2): the OTP client-auth endpoints POST /api/auth/otp/request
                        // and POST /api/auth/otp/verify are publicly reachable through this existing
                        // /api/auth/** permitAll rule. The Ant "**" wildcard spans multiple path
                        // segments, so both /api/auth/otp/request and /api/auth/otp/verify are matched
                        // here; neither is caught by the more specific /api/auth/me or
                        // /api/auth/resend-invite matchers declared above. Verified: no new matcher is
                        // needed for the OTP endpoints.
                        .requestMatchers("/api/auth/**").permitAll()
                        // FOR-04-13 (5.6): the standalone Google Places proxy AddressController is
                        // cross-cutting and intentionally NOT tied to the PROJECTS ABAC resource. It
                        // carries none of the three permission annotations (authenticated-any-user),
                        // so /api/addresses/** is guarded here purely by authentication: any
                        // authenticated principal may reach it, while an anonymous caller gets 401.
                        // This is redundant with the anyRequest().authenticated() catch-all below but
                        // stated explicitly to document the intended guard.
                        .requestMatchers("/api/addresses/**").authenticated()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
