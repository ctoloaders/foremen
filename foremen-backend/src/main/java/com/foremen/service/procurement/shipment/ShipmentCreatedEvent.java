package com.foremen.service.procurement.shipment;

import java.util.List;

/**
 * FOR-11-04 (R4.9, R15): a shipment was created. Published inside the creating transaction.
 *
 * @param shipmentId  the new shipment
 * @param projectId   its project
 * @param positionIds positions on its lines
 * @param actorUserId the buyer who created it, or {@code null}
 */
public record ShipmentCreatedEvent(Long shipmentId, Long projectId, List<Long> positionIds, Long actorUserId) {
}
