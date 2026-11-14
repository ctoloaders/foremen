package com.foremen.config.procurement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Procurement settings bound from the {@code foremen.procurement} namespace (FOR-11-01).
 *
 * <p>{@link #urgentDays} is the horizon of the planner "urgent" pick (Requirement 2.5): a position is
 * urgent when its site-delivery date is on or before Today plus this many days. Range 1..90; a value
 * outside the range, or a non-numeric one, fails startup validation (Requirement 10.2).
 */
@Validated
@ConfigurationProperties(prefix = "foremen.procurement")
public record ProcurementProperties(@NotNull @Min(1) @Max(90) Integer urgentDays) {

    /** Registers {@link ProcurementProperties}. */
    @Configuration
    @EnableConfigurationProperties(ProcurementProperties.class)
    public static class Registration {
    }
}
