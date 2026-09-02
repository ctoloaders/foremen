package com.foremen.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for the permission cache, bound from the
 * {@code foremen.permission} namespace in {@code application.yml}.
 *
 * <p>Binds {@code cache-ttl-minutes} — the idle expiry ({@code expireAfterAccess}) safety
 * net for the dedicated permission {@link com.foremen.service.permission.PermissionCache}.
 * Defaults to {@code 1440} (24 hours) when unset, so profiles without explicit config
 * (e.g. {@code integration-test}, {@code integration}) start cleanly.
 *
 * <p>Validated at startup: {@code @Positive} rejects non-positive values, aborting context
 * startup with a clear configuration error (Requirements 8.3, 8.4).
 */
@Validated
@ConfigurationProperties(prefix = "foremen.permission")
public record PermissionProperties(@Positive Integer cacheTtlMinutes) {

    public PermissionProperties {
        if (cacheTtlMinutes == null) {
            cacheTtlMinutes = 1440; // 24h idle safety net
        }
    }
}
