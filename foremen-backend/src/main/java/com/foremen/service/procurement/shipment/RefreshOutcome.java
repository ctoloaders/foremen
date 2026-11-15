package com.foremen.service.procurement.shipment;

/**
 * FOR-11-04 (R7.9, R10.4): result of {@link ShipmentTrackingService#refresh}. Never an exception.
 *
 * <p>{@code UPDATED}, {@code UNCHANGED} and {@code FAILED} map to the API {@code refreshResult}.
 * {@code REJECTED} (manual refresh refused by a guard) and {@code SKIPPED} (scheduler refresh not performed)
 * mean no carrier call was made; the controller maps {@link #REASON_TOO_SOON} to {@code 429} with
 * {@code retryAfterSeconds}, {@link #REASON_CARRIER_PAUSED} and {@link #REASON_NOT_AVAILABLE} to their
 * {@code error.shipment.refresh.*} keys.
 *
 * @param reason            failure classification ({@code TrackingFailure} name) or one of the
 *                          {@code REASON_*} codes; {@code null} on success
 * @param retryAfterSeconds seconds until a manual refresh is allowed again ({@code TOO_SOON} only)
 */
public record RefreshOutcome(Result result, String reason, Long retryAfterSeconds) {

    public enum Result { UPDATED, UNCHANGED, FAILED, REJECTED, SKIPPED }

    public static final String REASON_TOO_SOON = "TOO_SOON";
    public static final String REASON_CARRIER_PAUSED = "CARRIER_PAUSED";
    public static final String REASON_NOT_AVAILABLE = "NOT_AVAILABLE";
    public static final String REASON_NOT_DUE = "NOT_DUE";
    public static final String REASON_SPACING = "SPACING";

    public static RefreshOutcome of(Result result) {
        return new RefreshOutcome(result, null, null);
    }

    public static RefreshOutcome of(Result result, String reason) {
        return new RefreshOutcome(result, reason, null);
    }

    /** {@code true} when a carrier call was made. */
    public boolean performed() {
        return result == Result.UPDATED || result == Result.UNCHANGED || result == Result.FAILED;
    }
}
