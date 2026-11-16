package com.foremen.service.procurement.receipt;

/**
 * FOR-11-06 (R12.8) - published when a delegation is created or reassigned. Consumed AFTER_COMMIT by the
 * receipt notification emitter: {@code notification.receipt.delegated} to {@code assigneeId} and, when
 * {@code previousAssigneeId} is not null, {@code notification.receipt.delegationRevoked} to that user.
 */
public record ReceiptDelegatedEvent(Long delegationId, Long shipmentId, Long projectId, Long assigneeId,
                                    Long previousAssigneeId, Long actorUserId) {
}
