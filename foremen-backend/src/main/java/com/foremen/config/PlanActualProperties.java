package com.foremen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * FOR-06-02 (Requirement 4.3, 7.4) — tunable thresholds of the plan/actual Gantt overlay's
 * trajectory classifier, bound from the {@code foremen.plan-actual} namespace in
 * {@code application.yml}.
 *
 * <p>{@link #riskMargin} is the at-risk tolerance consumed by
 * {@link com.foremen.service.schedule.TrajectoryCalculator#classify(double, double, double)}: a work
 * category is flagged {@code AT_RISK} only once its confirmed completion trails its elapsed-time
 * share by strictly more than this (default {@code 0.1}). It lives in configuration — not hard-coded
 * at the call site — so the at-risk sensitivity is tunable without a code change (R4.3).
 *
 * <p>{@link #maxProjectedFinishDay} caps the pace-extrapolated projected finish day (R4.4) so a
 * near-zero completion cannot project to an absurd horizon; it defaults to the planning Gantt's
 * 3650-day horizon.
 *
 * <p>Both values are validated, so a misconfiguration aborts context startup with a clear error.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.plan-actual")
public record PlanActualProperties(
        @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
        Double riskMargin,
        @NotNull @Positive Integer maxProjectedFinishDay) {

    /** Fills unset properties with the documented defaults ({@code riskMargin=0.1}, horizon 3650). */
    public PlanActualProperties {
        if (riskMargin == null) {
            riskMargin = 0.1;
        }
        if (maxProjectedFinishDay == null) {
            maxProjectedFinishDay = 3650;
        }
    }
}
