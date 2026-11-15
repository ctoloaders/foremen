package com.foremen.service.procurement.shipment;

import java.time.LocalDate;

/**
 * FOR-11-04 (R11.3, R15): the effective ETA of an open shipment moved later (both dates known).
 *
 * @param actorUserId the user who caused the change, {@code null} for the carrier tracking
 */
public record ShipmentEtaDelayedEvent(Long shipmentId, Long projectId, LocalDate oldEta, LocalDate newEta,
                                      Long actorUserId) {
}
