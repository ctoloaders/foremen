package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 16.4, 18.3, 28.4): the payment state of a procurement document derived from
 * its {@code PROCUREMENT} ledger links.
 *
 * <p>Not persisted (no column): always computed by
 * {@link com.foremen.service.bank.link.DocumentPaymentStatusDeriver} from Σ link amounts vs
 * {@code document_total}. The display label is resolved on the frontend
 * ({@code procurement.paymentStatus.*}).
 *
 * <p>{@code LINKED_FROM_LEDGER} is not a status: it is a {@code ProcurementPaymentChoice} upload
 * option (Requirement 16.5 / 28.5).
 */
public enum DocumentPaymentStatus {
    UNMATCHED,
    PARTIALLY_PAID,
    PAID
}
