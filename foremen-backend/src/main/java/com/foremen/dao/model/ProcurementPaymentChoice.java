package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 16.5, 28.5): the payment option chosen when uploading a procurement document.
 * Not persisted — a request parameter of {@code POST /api/procurement-documents}.
 *
 * <ul>
 *   <li>{@link #NOT_LINKED} — default, nothing happens beyond reverse matching;</li>
 *   <li>{@link #PAID_CASH} — a SYSTEM cash line is created and linked in the upload transaction;</li>
 *   <li>{@link #FIND_BANK_TRANSACTION} — reverse matching is run eagerly for the document;</li>
 *   <li>{@link #LINKED_FROM_LEDGER} — the upload was started from a ledger line which links it; the
 *       payment choice and reverse matching are skipped.</li>
 * </ul>
 */
public enum ProcurementPaymentChoice {
    NOT_LINKED,
    PAID_CASH,
    FIND_BANK_TRANSACTION,
    LINKED_FROM_LEDGER
}
