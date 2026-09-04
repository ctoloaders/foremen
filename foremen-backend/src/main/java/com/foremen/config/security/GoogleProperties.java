package com.foremen.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Google Sign-In, bound from the {@code foremen.google}
 * namespace in {@code application.yml} (and {@code application-docker.yml}).
 *
 * <p>Holds the Google OAuth client id used as the expected audience when verifying an
 * inbound Google ID token via {@code GoogleIdTokenVerifier} on {@code POST /api/auth/google}.
 *
 * <p>{@code clientId} is intentionally not {@code @NotBlank}: the property binds from
 * {@code ${FOREMEN_GOOGLE_CLIENT_ID:}} and may be empty in environments where Google
 * Sign-In is not configured, so context startup must not abort when it is unset.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.google")
public record GoogleProperties(String clientId) {
}
