package com.foremen.service.procurement.externalorder;

/**
 * FOR-11-03 (R10.3, R14.5, R16.6): published after the linked supplier documents of an external order
 * were replaced. Listeners re-evaluate the paid state and restart reverse bank matching.
 */
public record ExternalOrderDocumentsChangedEvent(Long externalOrderId) {
}
