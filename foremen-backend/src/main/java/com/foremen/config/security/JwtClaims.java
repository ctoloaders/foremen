package com.foremen.config.security;

import java.time.Instant;

/**
 * The validated claims extracted from an access token (Requirement 4).
 *
 * @param sub       the user identifier from the {@code sub} claim
 * @param role      the role code from the {@code role} claim
 * @param email     the user email from the {@code email} claim
 * @param expiresAt the token expiry instant
 */
public record JwtClaims(Long sub, String role, String email, Instant expiresAt) {
}
