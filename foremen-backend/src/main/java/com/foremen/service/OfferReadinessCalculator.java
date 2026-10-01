package com.foremen.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure collaborator computing the {@code Offer_Readiness} metric (Requirement 17.7).
 *
 * <p>Readiness measures how close an offer that is {@code ON_APPROVAL} is to being
 * {@code APPROVED}. It combines two independent kinds of outstanding work:
 * <ol>
 *   <li>unresolved negotiation proposals — open/unaccepted rounds (Requirement 4); and</li>
 *   <li>unfilled finishing Placeholder slots — finishing materials in the package not yet
 *       chosen (reusing {@code Materials_Fulfilment} / the
 *       {@code Package_Derived_Finishing_Selection}).</li>
 * </ol>
 *
 * <p>The two factors are combined into a single percentage by treating every round and every
 * finishing Placeholder as one unit of work: readiness is the share of those units that are
 * already resolved, i.e.
 *
 * <pre>{@code
 *   resolvedItems = (totalRounds - openRounds) + (totalPlaceholders - unfilledPlaceholders)
 *   totalItems    = totalRounds + totalPlaceholders
 *   readiness     = 100 * resolvedItems / totalItems      (totalItems > 0)
 *   readiness     = 100                                    (totalItems == 0)
 * }</pre>
 *
 * <p>By construction readiness equals {@code 100} <strong>exactly</strong> if and only if
 * {@code openRounds == 0 AND unfilledPlaceholders == 0}: when both are zero every resolved item
 * count equals its total (so the ratio is 1), and when either is positive at least one unit is
 * outstanding (so the ratio is strictly below 1). An offer with no rounds and no finishing
 * Placeholders (zero totals) is defined as fully ready (100%).
 *
 * <p>This component is <strong>pure</strong>: it performs no persistence, holds no state, and its
 * result depends only on its arguments. It is exposed as a Spring {@code @Component} so services
 * can inject it, but the whole calculation is available through the {@code static}
 * {@link #readinessPercent(int, int, int, int)} core, which is what the property test exercises.
 */
@Component
public class OfferReadinessCalculator {

    /** Scale (number of decimal places) of the returned readiness percentage. */
    private static final int PERCENT_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Computes the offer readiness percentage in the range {@code [0, 100]}.
     *
     * @param openRounds          number of currently open/unresolved negotiation rounds; must be
     *                            {@code >= 0} and {@code <= totalRounds}
     * @param totalRounds         total number of negotiation rounds on the offer; must be {@code >= 0}
     * @param unfilledPlaceholders number of finishing Placeholder slots not yet filled; must be
     *                            {@code >= 0} and {@code <= totalPlaceholders}
     * @param totalPlaceholders   total number of finishing Placeholder slots; must be {@code >= 0}
     * @return the readiness percentage, {@code 100} exactly iff {@code openRounds == 0} and
     *         {@code unfilledPlaceholders == 0}
     * @throws IllegalArgumentException if any argument is negative or an "open"/"unfilled" count
     *         exceeds its corresponding total
     */
    public BigDecimal readiness(int openRounds,
                                int totalRounds,
                                int unfilledPlaceholders,
                                int totalPlaceholders) {
        return readinessPercent(openRounds, totalRounds, unfilledPlaceholders, totalPlaceholders);
    }

    /**
     * Pure static core of {@link #readiness(int, int, int, int)}; see that method for semantics.
     */
    public static BigDecimal readinessPercent(int openRounds,
                                              int totalRounds,
                                              int unfilledPlaceholders,
                                              int totalPlaceholders) {
        requireNonNegative(openRounds, "openRounds");
        requireNonNegative(totalRounds, "totalRounds");
        requireNonNegative(unfilledPlaceholders, "unfilledPlaceholders");
        requireNonNegative(totalPlaceholders, "totalPlaceholders");
        if (openRounds > totalRounds) {
            throw new IllegalArgumentException(
                    "openRounds (" + openRounds + ") cannot exceed totalRounds (" + totalRounds + ")");
        }
        if (unfilledPlaceholders > totalPlaceholders) {
            throw new IllegalArgumentException(
                    "unfilledPlaceholders (" + unfilledPlaceholders
                            + ") cannot exceed totalPlaceholders (" + totalPlaceholders + ")");
        }

        long totalItems = (long) totalRounds + (long) totalPlaceholders;
        // No rounds and no placeholders => nothing outstanding => fully ready.
        if (totalItems == 0L) {
            return scaled(HUNDRED);
        }

        long resolvedItems = ((long) totalRounds - openRounds)
                + ((long) totalPlaceholders - unfilledPlaceholders);

        BigDecimal ratio = BigDecimal.valueOf(resolvedItems)
                .divide(BigDecimal.valueOf(totalItems), MathContext.DECIMAL64);
        return scaled(ratio.multiply(HUNDRED));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
        }
    }
}
