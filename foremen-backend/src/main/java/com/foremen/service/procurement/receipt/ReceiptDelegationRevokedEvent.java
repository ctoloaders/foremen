package com.foremen.service.procurement.receipt;

/**
 * FOR-11-06 (R12.8) - published when an ACTIVE delegation is revoked (not on reassignment, which uses
 * {@link ReceiptDelegatedEvent#previousAssigneeId()}). Consumed AFTER_COMMIT:
 * {@code notification.receipt.delegationRevoked} to {@code previousAssigneeId}.
 */
public record ReceiptDelegationRevokedEvent(Long delegationId, Long shipmentId, Long projectId,
                                            Long previousAssigneeId, Long actorUserId) {
}
