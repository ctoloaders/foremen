package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.foremen.dao.model.PaymentChannel;

/**
 * FOR-07-04 (Requirements 16.1, 27.4; design §Cash ledger / §Link effects — FOR-07-06 seams) — published
 * by {@link WorkerPayoutService} whenever a payout transitions {@code PLANNED → PAID}.
 *
 * <p>Plain immutable record published through {@code ApplicationEventPublisher} (repo convention,
 * cf. {@code OfferStateChangeEvent}). It is <b>not</b> a notification: FOR-07-06 R13 still emits no
 * notification for payouts. The consumer is the FOR-07-04 {@code PayoutCashHook}, a synchronous
 * {@code @EventListener} running inside the payroll transaction, which records a SYSTEM cash ledger
 * entry for a {@code CASH} payout marked paid in payroll ({@code fromLedger == false}). When the payout
 * was marked paid <i>from</i> a ledger line ({@link WorkerPayoutService#markPaidFromLedger}),
 * {@code fromLedger == true} and the hook creates nothing, so no duplicate cash entry appears.
 *
 * @param payoutId   the paid payout id
 * @param channel    the payout's payment channel after the transition
 * @param paidAt     the payout's {@code paidAt}
 * @param amount     the payout cash amount
 * @param currency   the payout currency (payroll is single-currency: {@value WorkerPayoutService#PAYOUT_CURRENCY})
 * @param workerName the worker's display name (for the cash entry description)
 * @param note       the payout note, may be {@code null}
 * @param fromLedger {@code true} when the payout was marked paid by a ledger link
 */
public record WorkerPayoutPaidEvent(
        Long payoutId,
        PaymentChannel channel,
        OffsetDateTime paidAt,
        BigDecimal amount,
        String currency,
        String workerName,
        String note,
        boolean fromLedger) {
}
