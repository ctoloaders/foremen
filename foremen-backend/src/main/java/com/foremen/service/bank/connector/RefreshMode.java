package com.foremen.service.bank.connector;

/**
 * FOR-07-04 (Requirements 9.5, 9.6): how a refresh is requested.
 *
 * <p>{@link #INTERACTIVE} — user-initiated "Refresh now"; may return a widget URL for SCA.
 * {@link #BACKGROUND} — provider background refresh (PSD2-limited); not called by the scheduler.
 */
public enum RefreshMode {
    INTERACTIVE,
    BACKGROUND
}
