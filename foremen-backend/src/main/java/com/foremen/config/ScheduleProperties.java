package com.foremen.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * GLOBAL default {@code Daily_Output_Rate} for the planning Gantt (FOR-05-10, Requirement 9),
 * bound from the {@code foremen.schedule} namespace in {@code application.yml}.
 *
 * <p>{@link #dailyOutputPerWorker} is the net estimate value one worker is assumed to produce per
 * calendar day. It is used ONLY inside Auto_Create and the Suggested_Duration computation
 * (Requirement 8, 5.4); it is NEVER returned by the Schedule_API and NEVER shown in the UI
 * (Requirement 9.3). There is no per-project override (Requirement 9.2).
 *
 * <p>The value is {@code @NotNull} and strictly positive ({@code @DecimalMin} exclusive of 0), so a
 * missing or non-positive configuration aborts context startup with a clear configuration error
 * (Requirement 9.1).
 */
@Validated
@ConfigurationProperties(prefix = "foremen.schedule")
public record ScheduleProperties(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal dailyOutputPerWorker) {
}
