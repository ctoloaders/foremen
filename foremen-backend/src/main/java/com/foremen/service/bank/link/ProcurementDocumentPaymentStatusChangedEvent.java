package com.foremen.service.bank.link;

/**
 * FOR-11-03 (R16.2, R7.8) - published by {@link LinkEffects} when a {@code PROCUREMENT} ledger link is
 * created or removed, i.e. when the derived {@code DocumentPaymentStatus} of the document may have
 * changed. Listeners (task 5.3) re-evaluate the external orders the document is attached to.
 *
 * @param procurementDocumentId the document whose link sum changed
 */
public record ProcurementDocumentPaymentStatusChangedEvent(Long procurementDocumentId) {
}
