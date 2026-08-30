package com.foremen.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
        @NotBlank String secret) {

    public JwtProperties {
        if (accessTtlMinutes == null) {
            accessTtlMinutes = 30;
        }
        if (refreshTtlDays == null) {
            refreshTtlDays = 7;
        }
    }
}
