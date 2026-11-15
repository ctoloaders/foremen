package com.foremen.service.procurement.shipment;

/** FOR-11-04 (R14.2, R15): a position's order-by date was reached while it is still before ORDERED. */
public record PositionOrderByMissedEvent(Long positionId, Long projectId) {
}
