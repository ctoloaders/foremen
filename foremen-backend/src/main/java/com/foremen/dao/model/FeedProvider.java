package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 1.1, 4.1, 4.3): the bank-feed provider a {@code Bank_Connection} is
 * collected through. Each value maps to exactly one {@code BankFeedConnector} bean.
 *
 * <p>{@link #SALT_EDGE} — the Salt Edge connector (PARTNER / CLIENT mode); {@link #STUB} — the
 * deterministic dev/test connector enabled by {@code foremen.bank-feed.stub.enabled=true}.
 * Stored as a string ({@code varchar(32)}).
 */
public enum FeedProvider {
    SALT_EDGE,
    STUB
}
