package com.foremen.service.bank.link;

/**
 * FOR-11-03 (R7.8, R16) - port called by {@link LinkEffects} after an {@code EXTERNAL_ORDER_PAYMENT} link
 * was created, replaced or removed. The implementation (tasks 4.3 / 5.3,
 * {@code ExternalOrderPaymentService}) re-evaluates whether the order is paid, in the caller's
 * transaction.
 */
public interface ExternalOrderLedgerEffects {

    /**
     * Re-evaluates the paid state of the order after a ledger link change.
     *
     * @param externalOrderId the order the changed link points at
     */
    void onLinkChanged(Long externalOrderId);
}
