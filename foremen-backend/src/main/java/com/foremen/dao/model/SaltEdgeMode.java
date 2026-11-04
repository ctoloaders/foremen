package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 8, 9.1): the Salt Edge access mode, selected per environment by
 * {@code foremen.bank-feed.saltedge.mode} and recorded on each connection as {@code provider_mode}.
 *
 * <p>{@link #PARTNER} — Partners API v1 under Salt Edge's PSD2 licence (production target);
 * {@link #CLIENT} — API v6 with own licence / own bank keys. A connection keeps the mode it was
 * created in. Stored as a string ({@code varchar(16)}).
 */
public enum SaltEdgeMode {
    PARTNER,
    CLIENT
}
