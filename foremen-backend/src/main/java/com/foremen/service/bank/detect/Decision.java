package com.foremen.service.bank.detect;

/**
 * FOR-07-04 (Requirement 14.7) — the outcome of the rule matcher's decision rule.
 *
 * <ul>
 *   <li>{@link #AUTO_APPLY} — top ≥ auto-threshold and (top − runner-up) ≥ margin;</li>
 *   <li>{@link #SUGGEST} — at least one candidate ≥ suggest-threshold (top N stored as suggestions);</li>
 *   <li>{@link #NONE} — otherwise.</li>
 * </ul>
 */
public enum Decision {
    AUTO_APPLY,
    SUGGEST,
    NONE
}
