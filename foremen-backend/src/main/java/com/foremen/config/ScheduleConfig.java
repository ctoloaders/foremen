package com.foremen.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the schedule configuration properties so the {@code foremen.schedule} namespace is bound
 * at application startup (FOR-05-10, Requirement 9).
 *
 * <p>{@link ScheduleProperties} carries the GLOBAL {@code Daily_Output_Rate} default consumed by
 * the planning-Gantt Auto_Create and Suggested_Duration logic. The value is internal only: it is
 * never returned by the Schedule_API nor shown in the UI, and has no per-project override.
 *
 * <p>{@link PlanActualProperties} (FOR-06-02) carries the plan/actual overlay's trajectory
 * thresholds ({@code riskMargin}, projected-finish horizon cap) bound from {@code foremen.plan-actual}.
 */
@Configuration
@EnableConfigurationProperties({ScheduleProperties.class, PlanActualProperties.class})
public class ScheduleConfig {
}
