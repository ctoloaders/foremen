package com.foremen.config.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Generates and validates stateless access tokens (Requirement 4).
 *
 * <p>Tokens are signed with HS256 using a {@link SecretKey} derived from
 * {@link JwtProperties#secret()}. The constructor fails fast if the configured secret is
 * shorter than 32 bytes, which is the minimum key length HS256 requires.
 */
@Component
public class JwtTokenProvider {

    /** Minimum key length in bytes required by HS256. */
    private static final int MIN_KEY_LENGTH_BYTES = 32;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    private final SecretKey signingKey;
    private final int accessTtlMinutes;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        byte[] keyBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "foremen.jwt.secret must be at least " + MIN_KEY_LENGTH_BYTES
                            + " bytes for HS256 signing (was " + keyBytes.length + " bytes)");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtlMinutes = jwtProperties.accessTtlMinutes();
    }

    /**
     * Generates a signed access token carrying {@code sub}, {@code role}, and {@code email}
     * claims using the employee access TTL. The {@code iat} claim is set to the current
     * instant and {@code exp} to {@code iat + accessTtlMinutes} (Requirements 4.1, 4.2,
     * 4.3, 14.3).
     *
     * <p>Delegates to {@link #generateAccessToken(Long, String, String, int)} with the
     * employee access TTL captured at construction, so employee token behavior is
     * unchanged (Requirement 7.4).
     */
    public String generateAccessToken(Long userId, String roleCode, String email) {
        return generateAccessToken(userId, roleCode, email, accessTtlMinutes);
    }

    /**
     * Generates a signed access token carrying {@code sub}, {@code role}, and {@code email}
     * claims, with an explicit token lifetime in minutes. The {@code iat} claim is set to
     * the current instant and {@code exp} to {@code iat + ttlMinutes}.
     *
     * <p>Used by the OTP verify path to issue client access tokens with the client access
     * TTL, independently of the employee access TTL (Requirement 7.3).
     */
    public String generateAccessToken(Long userId, String roleCode, String email, int ttlMinutes) {
        Instant now = Instant.now();
        Instant expiry = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, roleCode)
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates an access token: verifies the signature and expiry, then confirms all
     * required claims are present.
     *
     * <p>Returns {@link Optional#empty()} for any invalid, malformed, expired, or
     * wrong-signature token. All JJWT exceptions are caught so no unhandled exception
     * escapes (Requirement 4.7). After a successful parse, returns empty if any of
     * {@code sub}, {@code role}, or {@code email} is missing or null (Requirement 4.8).
     */
    public Optional<JwtClaims> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();

            String subject = claims.getSubject();
            String role = claims.get(CLAIM_ROLE, String.class);
            String email = claims.get(CLAIM_EMAIL, String.class);
            Date expiration = claims.getExpiration();

            if (subject == null || role == null || email == null || expiration == null) {
                return Optional.empty();
            }

            Long sub;
            try {
                sub = Long.valueOf(subject);
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }

            return Optional.of(new JwtClaims(sub, role, email, expiration.toInstant()));
        } catch (Exception ex) {
            // ExpiredJwtException, SignatureException, MalformedJwtException,
            // IllegalArgumentException, etc. -> token is invalid.
            return Optional.empty();
        }
    }
}
