package com.foremen.config.security.property;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import com.foremen.config.security.JwtAuthenticationFilter;
import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link JwtAuthenticationFilter} (Requirement 5).
 *
 * <p>Each test builds a real {@link JwtTokenProvider} over a >= 32-byte secret, drives the
 * filter with a {@link MockHttpServletRequest}/{@link MockFilterChain}, and asserts the
 * resulting {@link SecurityContextHolder} state. The context is cleared before and after
 * every try to avoid cross-contamination between iterations.
 *
 * Property 13: Filter authenticates on a valid Bearer token — Validates: Requirements 5.1
 * Property 14: Filter leaves context unauthenticated without a valid Bearer token
 *              — Validates: Requirements 5.2, 5.3, 5.4
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 13-14: JwtAuthenticationFilter")
class JwtAuthenticationFilterPropertyTest {

    /** A fixed 32+ byte secret used by the provider under test. */
    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /** A different 32+ byte secret used to forge wrong-signature (invalid) tokens. */
    private static final String OTHER_SECRET =
            "foremen-jwt-OTHER-secret-key-9876543210ZYXWVU";

    private static final int DEFAULT_ACCESS_TTL_MINUTES = 30;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static JwtTokenProvider provider() {
        return new JwtTokenProvider(new JwtProperties(DEFAULT_ACCESS_TTL_MINUTES, 7, SECRET));
    }

    private static JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(provider());
    }

    // Feature: FOR-03-01-jwt-auth, Property 13: Filter authenticates on a valid Bearer token
    // For all valid access tokens presented as "Authorization: Bearer <token>", the filter
    // populates the SecurityContext with a principal equal to the sub claim and an authority
    // derived from the role claim (ROLE_<role>).
    // Validates: Requirements 5.1
    @Property(tries = 100)
    void authenticatesOnValidBearerToken(@ForAll("userIds") long sub,
                                         @ForAll("roleCodes") String role,
                                         @ForAll("emails") String email) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            JwtTokenProvider provider = provider();
            JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider);
            String token = provider.generateAccessToken(sub, role, email);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            // The chain must have continued.
            assertThat(chain.getRequest())
                    .as("the filter chain must continue on a valid token")
                    .isSameAs(request);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication)
                    .as("a valid Bearer token must populate the SecurityContext")
                    .isNotNull();
            assertThat(authentication.getPrincipal())
                    .as("principal must equal the sub claim (userId)")
                    .isEqualTo(sub);
            assertThat(authentication.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .as("authority must be derived from the role claim")
                    .containsExactly("ROLE_" + role);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // Feature: FOR-03-01-jwt-auth, Property 14: Filter leaves context unauthenticated without a valid Bearer token
    // For all requests carrying no Authorization header, a header not beginning with "Bearer ",
    // or a Bearer value the provider reports invalid, the filter leaves the SecurityContext
    // unauthenticated and continues the filter chain.
    // Validates: Requirements 5.2, 5.3, 5.4
    @Property(tries = 100)
    void leavesContextUnauthenticatedWithoutValidBearer(@ForAll("noAuthHeaderCases") String headerValue,
                                                         @ForAll("userIds") long sub,
                                                         @ForAll("roleCodes") String role,
                                                         @ForAll("emails") String email,
                                                         @ForAll @IntRange(min = 0, max = 2) int mode)
            throws Exception {
        SecurityContextHolder.clearContext();
        try {
            JwtTokenProvider provider = provider();
            JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider);

            MockHttpServletRequest request = new MockHttpServletRequest();
            // mode 0: no Authorization header at all (5.2)
            // mode 1: header not beginning with "Bearer " (5.4)
            // mode 2: "Bearer " + an invalid token (5.3)
            if (mode == 1) {
                request.addHeader("Authorization", headerValue);
            } else if (mode == 2) {
                request.addHeader("Authorization", "Bearer " + forgeInvalidToken(sub, role, email));
            }

            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            // The chain must always continue.
            assertThat(chain.getRequest())
                    .as("the filter chain must continue regardless of the header")
                    .isSameAs(request);

            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .as("no valid Bearer token must leave the context unauthenticated")
                    .isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Forges a token that the provider reports invalid: signed with a different key
     * (wrong signature). This exercises Requirement 5.3.
     */
    private static String forgeInvalidToken(long sub, String role, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(sub))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.MINUTES)))
                .signWith(key(OTHER_SECRET))
                .compact();
    }

    // --- Providers ---

    /** User ids: positive longs across a broad range. */
    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    /** Role codes from the system's known set plus arbitrary non-blank codes. */
    @Provide
    Arbitrary<String> roleCodes() {
        Arbitrary<String> known = Arbitraries.of(
                "ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(20);
        return Arbitraries.oneOf(known, arbitrary);
    }

    /** Simple, well-formed email-like strings (non-blank local and domain parts). */
    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12);
        Arbitrary<String> domain = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(10);
        Arbitrary<String> tld = Arbitraries.of("com", "org", "net", "pl", "ru");
        return Combinators.combine(local, domain, tld)
                .as((l, d, t) -> l + "@" + d + "." + t);
    }

    /**
     * Authorization header values that do NOT begin with the {@code "Bearer "} prefix
     * (used only for mode 1). Includes other schemes and prefixes that resemble but do
     * not exactly match {@code "Bearer "}.
     */
    @Provide
    Arbitrary<String> noAuthHeaderCases() {
        Arbitrary<String> token = Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(1)
                .ofMaxLength(30);
        Arbitrary<String> schemes = Arbitraries.of(
                "Basic ", "Digest ", "Token ", "bearer ", "Bearer", "BearerX ", "");
        return Combinators.combine(schemes, token).as((s, t) -> s + t);
    }
}
