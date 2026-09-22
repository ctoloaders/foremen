package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.Collection;

import org.springframework.stereotype.Component;

/**
 * Implements the <b>MAX-collapse</b> rule (FOR-05-04, design §6.1 {@code collapseToSingle}) used
 * to resolve a work item's per-package values (catalog {@code netPrice} or material-consumption
 * {@code normQty}) to a single surviving value during migration.
 *
 * <p>The rule is a pure, total, deterministic function of its input collection: it performs no
 * I/O, holds no state, and always returns the same result for the same inputs — mirroring the
 * {@link DiscountCalculator} convention of a stateless Spring {@code @Component} exercised
 * directly by property-based tests.
 *
 * <p>This helper is used <b>only</b> by the migration-side Java tooling/tests that need the same
 * MAX-collapse semantics the Liquibase changesets (082, 084) already apply via SQL {@code MAX()}.
 * It is <b>not</b> wired into any read path: per Requirement 8.5, the single work price and single
 * material-consumption norm are read directly with no MAX-fallback step. The value this rule
 * produces is the same value the retired {@code EffectivePriceResolver}'s max-fallback branch
 * would have produced for a fully-populated package set, but this rule replaces that resolver as
 * the mechanism for the one-time migration (Requirement 1.5, 7.3, 8.3).
 */
@Component
public class MaxCollapseRule {

    /**
     * Returns the maximum value across {@code perPackageValues}, per design §6.1
     * {@code collapseToSingle} (Requirement 1.5, 7.3, 8.3).
     *
     * <p>Callers are responsible for archiving the original per-package rows before invoking this
     * rule (Requirement 8.4) — this method only computes the survivor value.
     *
     * @param perPackageValues a work item's per-package values ({@code netPrice} or
     *                         {@code normQty}); must be non-null and non-empty
     * @return the maximum value in {@code perPackageValues}
     * @throws IllegalArgumentException if {@code perPackageValues} is {@code null}, empty, or
     *                                   contains a {@code null} element
     */
    public BigDecimal collapseToSingle(Collection<BigDecimal> perPackageValues) {
        if (perPackageValues == null || perPackageValues.isEmpty()) {
            throw new IllegalArgumentException("perPackageValues must be non-empty");
        }

        BigDecimal max = null;
        for (BigDecimal value : perPackageValues) {
            if (value == null) {
                throw new IllegalArgumentException("perPackageValues must not contain null elements");
            }
            if (max == null || value.compareTo(max) > 0) {
                max = value;
            }
        }

        return max;
    }
}
