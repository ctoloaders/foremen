package com.foremen.service.procurement.shipment.tracking;

/**
 * FOR-11-04 (R7.1): SPI implemented once per carrier. Implementations call only hard-coded carrier
 * hosts through {@link TrackingHttpClient}; a user-supplied URL is never fetched.
 */
public interface CarrierTrackingAdapter {

    /** Upper-case carrier code, e.g. {@code INPOST}. */
    String code();

    /** Reads {@code foremen.procurement.tracking.carriers.<code>.enabled}. */
    boolean isEnabled();

    /** Soft pattern check of a normalised number (drives {@code trackingNumberSuspect}). */
    boolean matches(String number);

    /** Public carrier page for the number (https), URL-encoded. */
    String trackingPageUrl(String number);

    /** Looks the number up at the carrier; never called when the adapter is disabled. */
    TrackingResult fetch(String number) throws TrackingUnavailableException;
}
