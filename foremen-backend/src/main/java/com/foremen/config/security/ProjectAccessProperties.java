package com.foremen.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for the project-access cache, bound from the
 * {@code foremen.project-access} namespace in {@code application.yml}.
 *
 * <p>Binds {@code cache-ttl-minutes} — the idle expiry ({@code expireAfterAccess}) safety
 * net for the dedicated {@link com.foremen.service.ProjectAccessCache}. Defaults to
 * {@code 1440} (24 hours) when unset, so profiles without explicit config
 * (e.g. {@code integration-test}, {@code integration}) start cleanly. Freshness is
 * guaranteed by explicit per-user invalidation, not by this idle TTL.
 *
 * <p>Validated at startup: {@code @Positive} rejects non-positive values, aborting context
 * startup with a clear configuration error (Requirements 5.1, 5.2, 5.3).
 *
 * <p>Mirrors {@link PermissionProperties} exactly, at the {@code foremen.project-access} prefix.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.project-access")
public record ProjectAccessProperties(@Positive Integer cacheTtlMinutes) {

    public ProjectAccessProperties {
        if (cacheTtlMinutes == null) {
            cacheTtlMinutes = 1440; // 24h idle safety net; freshness comes from explicit invalidation
        }
    }
}
