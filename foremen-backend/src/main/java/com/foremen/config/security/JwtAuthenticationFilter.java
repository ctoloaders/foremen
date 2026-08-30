package com.foremen.config.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the {@code Authorization: Bearer} header, validates the access token, and populates
 * the {@link SecurityContextHolder} with an authentication for the token owner (Requirement 5).
 *
 * <p>This filter is intentionally NOT a {@code @Component}. It is instantiated as a bean and
 * registered in the filter chain by {@code SecurityConfig} (task 11.3) via
 * {@code addFilterBefore}, which avoids the double registration that a servlet-container-scanned
 * {@code @Component} filter would cause.
 *
 * <ul>
 *   <li>Valid {@code Bearer} token &rarr; principal is the {@code sub} claim and authority is
 *       {@code ROLE_<role>} (Requirement 5.1).</li>
 *   <li>No {@code Authorization} header &rarr; context untouched, chain continues (Requirement 5.2).</li>
 *   <li>{@code Bearer} token the provider reports invalid &rarr; context untouched, chain continues
 *       (Requirement 5.3).</li>
 *   <li>Header not beginning with {@code Bearer } &rarr; context untouched, chain continues
 *       (Requirement 5.4).</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            tokenProvider.validate(token).ifPresent(claims -> {
                SimpleGrantedAuthority authority = new SimpleGrantedAuthority(ROLE_PREFIX + claims.role());
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(claims.sub(), null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}
