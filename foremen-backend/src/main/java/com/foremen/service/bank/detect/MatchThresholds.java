package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * FOR-07-04 (Requirement 14.7) — decision-rule thresholds, bound by the detection service from
 * {@code foremen.bank-ledger.match.*} (defaults: auto 0.90, suggest 0.50, margin 0.20, max 3).
 *
 * @param autoThreshold    minimum top score for {@link Decision#AUTO_APPLY}
 * @param suggestThreshold minimum score for a stored suggestion
 * @param margin           minimum lead of the top candidate over the runner-up for auto-apply
 * @param maxSuggestions   maximum number of suggestions per line
 */
public record MatchThresholds(
        BigDecimal autoThreshold,
        BigDecimal suggestThreshold,
        BigDecimal margin,
        int maxSuggestions) {

    /** The design defaults. */
    public static final MatchThresholds DEFAULTS = new MatchThresholds(
            new BigDecimal("0.90"), new BigDecimal("0.50"), new BigDecimal("0.20"), 3);

    public MatchThresholds {
        Objects.requireNonNull(autoThreshold, "autoThreshold");
        Objects.requireNonNull(suggestThreshold, "suggestThreshold");
        Objects.requireNonNull(margin, "margin");
        if (maxSuggestions < 0) {
            throw new IllegalArgumentException("maxSuggestions must be >= 0");
        }
    }

    /** Convenience factory from configuration doubles. */
    public static MatchThresholds of(double auto, double suggest, double margin, int maxSuggestions) {
        return new MatchThresholds(BigDecimal.valueOf(auto), BigDecimal.valueOf(suggest),
                BigDecimal.valueOf(margin), maxSuggestions);
    }
}
