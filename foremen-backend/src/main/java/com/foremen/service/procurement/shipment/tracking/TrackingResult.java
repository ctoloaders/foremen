package com.foremen.service.procurement.shipment.tracking;

import com.foremen.dao.model.ShipmentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * FOR-11-04 (R7.1): normalised outcome of one carrier lookup. {@code eventText} is capped at
 * {@value #MAX_EVENT_TEXT} characters (it becomes the event note).
 */
public record TrackingResult(ShipmentStatus status, Instant lastEventAt, LocalDate etaDate, String eventText) {

    public static final int MAX_EVENT_TEXT = 255;

    public TrackingResult {
        Objects.requireNonNull(status, "status");
        if (eventText != null) {
            eventText = eventText.strip();
            if (eventText.length() > MAX_EVENT_TEXT) {
                eventText = eventText.substring(0, MAX_EVENT_TEXT);
            }
            if (eventText.isEmpty()) {
                eventText = null;
            }
        }
    }
}
