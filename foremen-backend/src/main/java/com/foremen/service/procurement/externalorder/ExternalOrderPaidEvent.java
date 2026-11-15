package com.foremen.service.procurement.externalorder;

/**
 * FOR-11-03 (R15.3, R16.3, R19): an external order became {@code PAID}. Published once per transition into
 * {@code PAID}; not published when the ledger only takes over a hand-set paid state (R16.4).
 *
 * @param externalOrderId the paid order
 * @param projectId       its project
 * @param actorUserId     the financier who marked it paid, or {@code null} when the ledger paid it
 */
public record ExternalOrderPaidEvent(Long externalOrderId, Long projectId, Long actorUserId) {
}
