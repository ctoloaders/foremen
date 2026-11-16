package com.foremen.service.procurement.receipt;

/**
 * FOR-11-06 (R12.8) - published by {@code GoodsReceiptService.record} the first time a receipt event with a
 * given {@code delegation_id} is stored. Consumed AFTER_COMMIT: {@code notification.receipt.acceptedByDelegate}
 * to the MANAGER who delegated ({@code delegatedByUserId}) and the project MANAGER members, minus the actor.
 *
 * @param delegationId      the delegation the first event was recorded under
 * @param shipmentId        the delegated shipment
 * @param projectId         its project
 * @param delegatedByUserId the MANAGER (or ADMIN) who created the delegation, may be {@code null}
 * @param actorUserId       the delegated worker who recorded the event (never notified), may be {@code null}
 */
public record ReceiptFirstDelegateEvent(Long delegationId, Long shipmentId, Long projectId,
                                        Long delegatedByUserId, Long actorUserId) {
}
