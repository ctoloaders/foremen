package com.foremen.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for JWT authentication, bound from the {@code foremen.jwt}
 * namespace in {@code application.yml}.
 *
 * <p>Validated at startup: {@code @Positive} rejects non-positive lifetimes and relaxed
 * binding rejects non-numeric values for the {@code Integer} fields, aborting context
 * startup with a clear configuration error (Requirement 14.5).
 */
@Validated
@ConfigurationProperties(prefix = "foremen.jwt")
public record JwtProperties(
        @Positive Integer accessTtlMinutes,
        @Positive Integer refreshTtlDays,
        @Positive Integer clientAccessTtlMinutes,
        @Positive Integer clientRefreshTtlDays,
        @NotBlank String secret) {

    /**
     * Designated binding constructor. Because this record also declares a convenience
     * constructor, Spring Boot cannot infer which constructor to use for property
     * binding; {@code @ConstructorBinding} on the canonical constructor makes the choice
     * explicit so relaxed binding and the default fallbacks below apply.
     */
    @ConstructorBinding
    public JwtProperties {
        if (accessTtlMinutes == null) {
            accessTtlMinutes = 30;
        }
        if (refreshTtlDays == null) {
            refreshTtlDays = 7;
        }
        if (clientAccessTtlMinutes == null) {
            clientAccessTtlMinutes = 120;
        }
        if (clientRefreshTtlDays == null) {
            clientRefreshTtlDays = 30;
        }
    }

    /**
     * Convenience constructor that applies the client TTL defaults (120 minutes /
     * 30 days). Retained so callers that only care about the employee TTLs need not
     * supply the client values.
     */
    public JwtProperties(Integer accessTtlMinutes, Integer refreshTtlDays, String secret) {
        this(accessTtlMinutes, refreshTtlDays, null, null, secret);
    }
}
