package com.foremen.config.security.property;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import com.foremen.config.security.JwtClaims;
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
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link JwtTokenProvider} (Requirement 4).
 *
 * <p>Each test constructs a {@link JwtProperties} directly with a >= 32-byte secret and
 * exercises the provider against generated user ids, role codes, emails, and malformed
 * tokens. Wrong-signature, expired, and missing-claim tokens are built with JJWT
 * ({@code Jwts.builder}) directly so the provider's validation is tested in isolation.
 *
 * Property 1: JWT generation/validation round-trip — Validates: Requirements 4.1, 4.4, 4.9
 * Property 2: Wrong-signature tokens are invalid — Validates: Requirements 4.5
 * Property 3: Expired tokens are invalid — Validates: Requirements 4.6
 * Property 4: Malformed tokens are invalid without throwing — Validates: Requirements 4.7
 * Property 5: Missing required claims make a token invalid — Validates: Requirements 4.8
 * Property 6: Access-token expiry bound (±2s) — Validates: Requirements 4.2, 14.3
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 1-6: JwtTokenProvider")
class JwtTokenProviderPropertyTest {

    /** A fixed 32-byte secret used by the provider under test. */
    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /** A different 32-byte secret used to forge wrong-signature tokens. */
    private static final String OTHER_SECRET =
            "foremen-jwt-OTHER-secret-key-9876543210ZYXWVU";

    private static final int DEFAULT_ACCESS_TTL_MINUTES = 30;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static JwtTokenProvider provider(int accessTtlMinutes) {
        return new JwtTokenProvider(new JwtProperties(accessTtlMinutes, 7, SECRET));
    }

    private static JwtTokenProvider defaultProvider() {
        return provider(DEFAULT_ACCESS_TTL_MINUTES);
    }

    // Feature: FOR-03-01-jwt-auth, Property 1: JWT generation/validation round-trip
    // For all users (any userId, role code, email), generating an access token and then
    // validating it yields claims whose sub/role/email equal the values used at generation,
    // and reports the token as valid.
    // Validates: Requirements 4.1, 4.4, 4.9
    @Property(tries = 100)
    void jwtRoundTrip(@ForAll("userIds") long sub,
                      @ForAll("roleCodes") String role,
                      @ForAll("emails") String email) {
        JwtTokenProvider provider = defaultProvider();

        String token = provider.generateAccessToken(sub, role, email);
        Optional<JwtClaims> result = provider.validate(token);

        assertThat(result)
                .as("a freshly generated token must be valid")
                .isPresent();
        JwtClaims claims = result.get();
        assertThat(claims.sub()).isEqualTo(sub);
        assertThat(claims.role()).isEqualTo(role);
        assertThat(claims.email()).isEqualTo(email);
    }

    // Feature: FOR-03-01-jwt-auth, Property 2: Wrong-signature tokens are invalid
    // For all tokens signed with a key other than the configured secret, validate reports
    // the token as invalid (empty result).
    // Validates: Requirements 4.5
    @Property(tries = 100)
    void wrongSignatureIsInvalid(@ForAll("userIds") long sub,
                                 @ForAll("roleCodes") String role,
                                 @ForAll("emails") String email) {
        JwtTokenProvider provider = defaultProvider();

        Instant now = Instant.now();
        String forged = Jwts.builder()
                .subject(String.valueOf(sub))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.MINUTES)))
                .signWith(key(OTHER_SECRET))
                .compact();

        assertThat(provider.validate(forged))
                .as("a token signed with a different key must be invalid")
                .isEmpty();
    }

    // Feature: FOR-03-01-jwt-auth, Property 3: Expired tokens are invalid
    // For all tokens whose expiry is equal to or earlier than the current time, validate
    // reports the token as invalid.
    // Validates: Requirements 4.6
    @Property(tries = 100)
    void expiredIsInvalid(@ForAll("userIds") long sub,
                          @ForAll("roleCodes") String role,
                          @ForAll("emails") String email,
                          @ForAll @LongRange(min = 1, max = 100_000) long secondsAgo) {
        JwtTokenProvider provider = defaultProvider();

        Instant now = Instant.now();
        Instant expiry = now.minusSeconds(secondsAgo);
        String expired = Jwts.builder()
                .subject(String.valueOf(sub))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(expiry.minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(expiry))
                .signWith(key(SECRET))
                .compact();

        assertThat(provider.validate(expired))
                .as("a token whose expiry is in the past must be invalid")
                .isEmpty();
    }

    // Feature: FOR-03-01-jwt-auth, Property 4: Malformed tokens are invalid without throwing
    // For all strings that are not well-formed JWTs, validate returns an invalid result and
    // does not throw an unhandled exception.
    // Validates: Requirements 4.7
    @Property(tries = 100)
    void malformedIsInvalidWithoutThrowing(@ForAll("malformedTokens") String malformed) {
        JwtTokenProvider provider = defaultProvider();

        // Must not throw; must be empty.
        Optional<JwtClaims> result = provider.validate(malformed);
        assertThat(result)
                .as("a malformed token must be reported invalid without throwing")
                .isEmpty();
    }

    // Feature: FOR-03-01-jwt-auth, Property 5: Missing required claims make a token invalid
    // For all tokens with a valid signature and future expiry that are missing any of the
    // sub, role, or email claims, validate reports the token as invalid.
    // Validates: Requirements 4.8
    @Property(tries = 100)
    void missingClaimIsInvalid(@ForAll("userIds") long sub,
                               @ForAll("roleCodes") String role,
                               @ForAll("emails") String email,
                               @ForAll @IntRange(min = 0, max = 2) int omit) {
        JwtTokenProvider provider = defaultProvider();

        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.MINUTES)))
                .signWith(key(SECRET));

        // omit == 0 -> no subject; omit == 1 -> no role; omit == 2 -> no email.
        if (omit != 0) {
            builder.subject(String.valueOf(sub));
        }
        if (omit != 1) {
            builder.claim(CLAIM_ROLE, role);
        }
        if (omit != 2) {
            builder.claim(CLAIM_EMAIL, email);
        }

        String token = builder.compact();

        assertThat(provider.validate(token))
                .as("a token missing a required claim must be invalid")
                .isEmpty();
    }

    // Feature: FOR-03-01-jwt-auth, Property 6: Access-token expiry bound (±2s)
    // For all positive access-token lifetimes (minutes), a freshly generated access token
    // has an expiry within ±2 seconds of the issuance time plus that lifetime.
    // Validates: Requirements 4.2, 14.3
    @Property(tries = 100)
    void accessTokenExpiryBound(@ForAll("userIds") long sub,
                                @ForAll("roleCodes") String role,
                                @ForAll("emails") String email,
                                @ForAll @IntRange(min = 1, max = 1440) int ttlMinutes) {
        JwtTokenProvider provider = provider(ttlMinutes);

        Instant before = Instant.now();
        String token = provider.generateAccessToken(sub, role, email);
        Instant after = Instant.now();

        JwtClaims claims = provider.validate(token)
                .orElseThrow(() -> new AssertionError("generated token must be valid"));

        // The expiry must fall within [before + ttl - 2s, after + ttl + 2s].
        Instant lowerBound = before.plus(ttlMinutes, ChronoUnit.MINUTES).minusSeconds(2);
        Instant upperBound = after.plus(ttlMinutes, ChronoUnit.MINUTES).plusSeconds(2);

        // JWT expiry is second-precision, so compare after truncating the bounds.
        assertThat(claims.expiresAt())
                .as("expiry must be within ±2s of issuance + ttl")
                .isAfterOrEqualTo(lowerBound.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1))
                .isBeforeOrEqualTo(upperBound.plusSeconds(1));
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
     * Malformed tokens: wrong segment counts and non-base64/unparseable payloads. Excludes
     * the empty/blank cases (handled by an explicit guard in the provider) to keep the
     * generator focused on structural malformation.
     */
    @Provide
    Arbitrary<String> malformedTokens() {
        Arbitrary<String> segment = Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(1)
                .ofMaxLength(20);

        // Zero periods: a single opaque blob.
        Arbitrary<String> zeroDots = segment;
        // One period: only two segments.
        Arbitrary<String> oneDot = Combinators.combine(segment, segment)
                .as((a, b) -> a + "." + b);
        // Two periods but non-base64/garbage payloads.
        Arbitrary<String> twoDotsGarbage = Combinators.combine(segment, segment, segment)
                .as((a, b, c) -> a + "." + b + "." + c);
        // Four segments (too many).
        Arbitrary<String> tooMany = Combinators.combine(segment, segment, segment, segment)
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);

        return Arbitraries.oneOf(zeroDots, oneDot, twoDotsGarbage, tooMany);
    }
}
