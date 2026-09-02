package com.foremen.config.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * Configuration properties for invite-token generation, bound from the {@code foremen.invite}
 * namespace in {@code application.yml} (Requirement 7.5, 7.6).
 *
 * <p>Validated at startup for fail-fast behavior (mirroring {@code JwtProperties}): relaxed binding
 * rejects non-numeric / non-integer values for the {@link Integer} field, and the
 * {@link #isTtlHoursInRange()} predicate rejects an in-band value outside {@code [1, 8760]},
 * aborting context startup with a clear configuration error. The compact constructor defaults
 * {@code ttlHours} to {@code 72} when unset (Requirement 7.5).
 */
@Validated
@ConfigurationProperties(prefix = "foremen.invite")
public record InviteProperties(Integer ttlHours) {

    public InviteProperties {
        if (ttlHours == null) {
            ttlHours = 72;
        }
    }

    /**
     * Enforces the invite-token lifetime range {@code [1, 8760]} hours inclusive (Requirement 7.6).
     */
    @AssertTrue(message = "foremen.invite.ttl-hours must be an integer in [1, 8760]")
    public boolean isTtlHoursInRange() {
        return ttlHours != null && ttlHours >= 1 && ttlHours <= 8760;
    }
}
