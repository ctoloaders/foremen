package com.foremen.service.procurement.externalorder;

/**
 * FOR-11-03 (R15.1, R19): an external order was sent for payment. Consumed after commit by the financier
 * notification and by reverse bank matching.
 *
 * @param externalOrderId the order that moved to {@code SENT_FOR_PAYMENT}
 * @param projectId       its project
 * @param actorUserId     the buyer who sent it (never notified), may be {@code null}
 */
public record ExternalOrderSentEvent(Long externalOrderId, Long projectId, Long actorUserId) {
}
