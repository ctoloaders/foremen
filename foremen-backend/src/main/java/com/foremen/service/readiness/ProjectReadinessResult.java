package com.foremen.service.readiness;

import java.util.List;

/**
 * The service-level aggregated readiness model returned by
 * {@link ProjectReadinessService#getReadiness(Long)} (FOR-05-11 Requirement 4, Decision D2/D4/D5).
 *
 * <p>This is the <em>domain</em> result the aggregator composes: the project id, the equal-weight
 * {@link #overallPercentage()} (Requirement 4.3), the rolled-up {@link #overallState()}
 * (Requirement 4.4), and the ordered {@link #areas()} the caller may READ, each already a
 * well-formed {@link AreaContribution} (Requirement 4.1, 4.5, 4.6). It is a service-level model, not
 * the REST response DTO — the controller-facing {@code ProjectReadinessView} is defined by the
 * controller task and is mapped from this result.
 *
 * @param projectId         the project the readiness was computed for
 * @param overallPercentage the equal-weight mean of the included area percentages, {@code 0..100}
 *                          ({@code 0} when no area is included)
 * @param overallState      the {@code BLOCKED > PARTIAL > DONE} roll-up of the included area states
 *                          ({@code BLOCKED} when no area is included)
 * @param areas             the included area contributions in declared order (Requirement 7)
 */
public record ProjectReadinessResult(
        Long projectId,
        int overallPercentage,
        ReadinessState overallState,
        List<AreaContribution> areas
) {}
