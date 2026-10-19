package com.foremen.service.payment.provider;

import com.foremen.service.payment.TransactionStatus;
import java.math.BigDecimal;

/**
 * FOR-06-07 (Requirement 7.3): a parsed online-payment (przelewy24) webhook event, produced by
 * {@link OnlinePaymentProvider#parse} from a verified {@link RawWebhook}.
 *
 * <p>It identifies the affected transaction by its provider-side {@code externalRef} and carries the
 * reported outcome as a {@link TransactionStatus} ({@code CONFIRMED} on success → run
 * reconciliation; {@code FAILED} on failure; {@code REFUNDED} on reversal). The commission is
 * reported as {@code feeGross} and recorded under the {@code Payer_Borne_Fee} model — it is never
 * deducted from the amount allocated to tranches (Requirement 7.4).
 *
 * @param externalRef the provider-side payment reference the event concerns; never {@code null}
 * @param status      the reported outcome for the transaction; never {@code null}
 * @param amountGross the gross amount the event reports, or {@code null} when not applicable
 * @param feeGross    the client-borne commission reported by the provider, or {@code null} when not
 *                    applicable
 */
public record OnlineWebhookEvent(
        String externalRef, TransactionStatus status, BigDecimal amountGross, BigDecimal feeGross) {
}
