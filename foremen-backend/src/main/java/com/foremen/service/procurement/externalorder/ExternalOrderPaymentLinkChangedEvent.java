package com.foremen.service.procurement.externalorder;

/**
 * FOR-11-06 (R9.4): the payment link of an external order in {@code SENT_FOR_PAYMENT} was set or changed.
 * Consumed after commit by the financier notification.
 *
 * @param externalOrderId the order
 * @param projectId       its project
 * @param actorUserId     the user who changed the link (never notified), may be {@code null}
 */
public record ExternalOrderPaymentLinkChangedEvent(Long externalOrderId, Long projectId, Long actorUserId) {
}
