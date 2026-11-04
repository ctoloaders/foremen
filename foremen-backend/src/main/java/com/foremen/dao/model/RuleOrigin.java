package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 17.3): how a counterparty rule was created
 * ({@code bank_counterparty_rules.origin}, {@code varchar(8)}, default {@link #LEARNED}).
 */
public enum RuleOrigin {
    LEARNED,
    MANUAL
}
