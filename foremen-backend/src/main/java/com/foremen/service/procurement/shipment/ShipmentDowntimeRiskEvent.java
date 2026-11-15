package com.foremen.service.procurement.shipment;

import java.time.LocalDate;

/**
 * FOR-11-04 (R11.4, R14.4, R15): the ETA is after the start of a dependent works category. One-shot per
 * distinct {@code etaDate} (guarded by {@code shipments.downtime_notified_for}).
 *
 * @param actorUserId the user who caused the change, {@code null} for tracking or the sweep
 */
public record ShipmentDowntimeRiskEvent(Long shipmentId, Long projectId, LocalDate etaDate, Long actorUserId) {
}
