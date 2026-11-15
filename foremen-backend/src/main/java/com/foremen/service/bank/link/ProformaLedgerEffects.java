package com.foremen.service.bank.link;

/**
 * FOR-11-03 (R7.8, R8) — port called by {@link LinkEffects} after a {@code PROFORMA_PAYMENT} link was
 * created, replaced or removed. The implementation (task 5.2, {@code ProformaPaymentService}) recomputes
 * the proforma paid amount and status under a row lock, in the caller's transaction. Defined here so the
 * ledger has no compile-time dependency on the procurement package internals.
 */
public interface ProformaLedgerEffects {

    /**
     * Recomputes {@code paidGross = Σ PROFORMA_PAYMENT links}, the status, and releases the positions
     * when the proforma becomes fully paid.
     *
     * @param proformaId the proforma the changed link points at
     */
    void onLinkChanged(Long proformaId);
}
