package com.foremen.service.procurement.shipment.tracking;

/**
 * FOR-11-04 (R7.1): checked failure of {@link CarrierTrackingAdapter#fetch}. The message must never
 * carry a carrier response body or the owner data of a tracking number (R7.9).
 */
public class TrackingUnavailableException extends Exception {

    private final TrackingFailure failure;

    public TrackingUnavailableException(TrackingFailure failure, String message) {
        super(message);
        this.failure = failure == null ? TrackingFailure.OTHER : failure;
    }

    public TrackingUnavailableException(TrackingFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure == null ? TrackingFailure.OTHER : failure;
    }

    public TrackingFailure getFailure() {
        return failure;
    }
}
