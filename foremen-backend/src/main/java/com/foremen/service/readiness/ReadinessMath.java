package com.foremen.service.readiness;

import java.util.List;

/**
 * Pure, Spring-free math helper that centralizes the readiness fallback mapping and the overall
 * roll-up (FOR-05-11 Decisions D3/D4/D5, Requirements 1.5, 4.3, 4.4).
 *
 * <p>Keeping these two rules in one small, side-effect-free place lets them be property-tested
 * without a Spring context:
 *
 * <ul>
 *   <li>{@link #fallbackPercentage(ReadinessState)} — the canonical state → percentage fallback an
 *       area uses when it has no fractional notion (Requirement 1.5).</li>
 *   <li>{@link #overallPercentage(List)} — the equal-weight average of the included area
 *       percentages (Requirement 4.3, Decision D4).</li>
 *   <li>{@link #overallState(List)} — the {@code BLOCKED > PARTIAL > DONE} state roll-up
 *       (Requirement 4.4, Decision D5).</li>
 * </ul>
 */
public final class ReadinessMath {

    private ReadinessMath() {
    }

    /**
     * The canonical state → percentage fallback (Requirement 1.5, Decision D3):
     * {@code DONE = 100}, {@code PARTIAL = 50}, {@code BLOCKED = 0}.
     *
     * @param state the area state (must not be {@code null})
     * @return the fallback percentage in {@code {0, 50, 100}}
     */
    public static int fallbackPercentage(ReadinessState state) {
        return switch (state) {
            case DONE -> 100;
            case PARTIAL -> 50;
            case BLOCKED -> 0;
        };
    }

    /**
     * The equal-weight overall percentage (Requirement 4.3, Decision D4):
     * {@code round(mean(percentage of every included contribution))}, every area weighing the same,
     * and {@code 0} when no contribution is included.
     *
     * <p>Rounding is arithmetic (half away from zero on a non-negative mean, i.e. {@code .5}
     * rounds up), matching {@code Math.round} over a non-negative average.
     *
     * @param included the included area contributions (must not be {@code null}; may be empty)
     * @return the overall percentage in {@code [0, 100]}
     */
    public static int overallPercentage(List<AreaContribution> included) {
        if (included.isEmpty()) {
            return 0;
        }
        long sum = 0L;
        for (AreaContribution contribution : included) {
            sum += contribution.percentage();
        }
        return Math.toIntExact(Math.round((double) sum / included.size()));
    }

    /**
     * The overall state roll-up (Requirement 4.4, Decision D5): {@code BLOCKED} if any included
     * contribution is {@code BLOCKED}, else {@code PARTIAL} if any is {@code PARTIAL}, else
     * {@code DONE}; an empty list rolls up to {@code BLOCKED}.
     *
     * @param included the included area contributions (must not be {@code null}; may be empty)
     * @return the overall readiness state
     */
    public static ReadinessState overallState(List<AreaContribution> included) {
        if (included.isEmpty()) {
            return ReadinessState.BLOCKED;
        }
        boolean anyPartial = false;
        for (AreaContribution contribution : included) {
            if (contribution.state() == ReadinessState.BLOCKED) {
                return ReadinessState.BLOCKED;
            }
            if (contribution.state() == ReadinessState.PARTIAL) {
                anyPartial = true;
            }
        }
        return anyPartial ? ReadinessState.PARTIAL : ReadinessState.DONE;
    }
}
