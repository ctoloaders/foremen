package com.foremen.service.procurement;

/**
 * FOR-11-00 (R13.2, R13.3) — a purchase request became overdue (one-shot, stamped by the sweep).
 * Consumed AFTER_COMMIT by {@link ProcurementNotificationEmitter}.
 *
 * @param requestId       the purchase request id (deep-link target)
 * @param projectId       the owning project id (its ACTIVE MANAGER members are notified)
 * @param assignedBuyerId the assigned buyer's user id, or {@code null} when unassigned (all active BUYERs are notified)
 */
public record PurchaseRequestOverdueEvent(Long requestId, Long projectId, Long assignedBuyerId) {
}
