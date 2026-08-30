package com.foremen.config.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
 * stay public while {@code /me} is protected (6.1, 9.3). All other paths remain {@code permitAll}
 * for now; the wholesale tightening is deferred to FOR-03-08.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
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
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().permitAll()
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
