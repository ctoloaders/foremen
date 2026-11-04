package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirement 13.7): status of a detection suggestion
 * ({@code bank_transaction_suggestions.status}, {@code varchar(16)}, default {@link #OPEN}).
 */
public enum SuggestionStatus {
    OPEN,
    CONFIRMED,
    REJECTED,
    SUPERSEDED
}
