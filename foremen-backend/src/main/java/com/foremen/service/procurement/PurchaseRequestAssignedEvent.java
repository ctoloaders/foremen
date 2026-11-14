package com.foremen.service.procurement;

/**
 * FOR-11-00 (R13.1) — a purchase request's {@code assignedBuyer} was set or changed to a non-null user.
 * Consumed AFTER_COMMIT by {@link ProcurementNotificationEmitter}.
 *
 * @param requestId    the purchase request id (deep-link target)
 * @param buyerUserId  the newly assigned buyer's user id
 */
public record PurchaseRequestAssignedEvent(Long requestId, Long buyerUserId) {
}
