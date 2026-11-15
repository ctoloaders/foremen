package com.foremen.service.procurement.shipment;

/**
 * FOR-11-04 (R4.8): hook for the first tracking refresh of a new carrier shipment. Published inside the
 * creating transaction; the tracking service (task 3.9) listens with
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)}, swallows failures and
 * calls {@code refresh(shipmentId, false)}. Without a listener nothing happens.
 */
public record ShipmentRefreshRequestedEvent(Long shipmentId) {
}
