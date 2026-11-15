package com.foremen.service.procurement.shipment.tracking;

/** FOR-11-04 (R7.7): normalised classification of a failed carrier call. */
public enum TrackingFailure {
    NOT_FOUND,
    RATE_LIMITED,
    BLOCKED,
    PARSE_ERROR,
    TIMEOUT,
    OTHER;

    /** {@code RATE_LIMITED} and {@code BLOCKED} pause the whole carrier (R7.7). */
    public boolean pausesCarrier() {
        return this == RATE_LIMITED || this == BLOCKED;
    }
}
