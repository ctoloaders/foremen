package com.foremen.service.procurement;

/**
 * FOR-07-04 (Requirements 28.1, 28.4; design §Cash ledger) — the seam through which
 * {@code ProcurementDocumentService} records a cash payment of a procurement document (upload
 * {@code paymentChoice = PAID_CASH} or the "Mark paid in cash" action) without the procurement
 * package depending on the cash-ledger entities / services.
 *
 * <p>The bank ledger (FOR-07-04 task 6.12, {@code ProcurementCashHook}) contributes a Spring bean
 * implementing this interface: it creates the SYSTEM cash expense entry and the {@code PROCUREMENT}
 * link in the caller's transaction. Until such a bean exists the service falls back to
 * {@link #NONE} (a no-op).
 */
@FunctionalInterface
public interface ProcurementCashPaymentHandler {

    /** The fallback used while the cash ledger is absent: nothing is recorded. */
    ProcurementCashPaymentHandler NONE = payment -> { };

    /**
     * Records the cash payment (runs inside the caller's transaction; a failure rolls it back).
     *
     * @param payment the resolved cash payment (amount and date never {@code null})
     */
    void payInCash(ProcurementCashPayment payment);
}
