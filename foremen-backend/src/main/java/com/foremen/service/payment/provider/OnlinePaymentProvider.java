package com.foremen.service.payment.provider;

import com.foremen.service.payment.dto.InitiatePayment;

/**
 * FOR-06-07 (Requirements 7.1, 7.2, 7.5; design §Components {@code Provider ports + No-Op stubs}):
 * the {@code Payment_Provider_Port} abstraction over the external online-payment channel
 * (przelewy24).
 *
 * <p>The abstraction supplies the three entry points the payment lifecycle needs: {@link #initiate}
 * to create a payment intent for a tranche/amount (returning a redirect/opaque result), {@link
 * #verifySignature} to authenticate an inbound webhook before it is trusted (Requirement 7.5), and
 * {@link #parse} to turn a verified webhook into an {@link OnlineWebhookEvent} (confirm/fail/refund
 * by {@code externalRef}, Requirement 7.3).
 *
 * <p>The concrete provider is <b>config-selected</b> via {@code foremen.payments.online.provider};
 * the default {@link NoOpOnlinePaymentProvider} (active when the property is {@code none} or absent)
 * succeeds-without-effect — {@code initiate} returns a {@linkplain OnlineInitiateResult#disabled()
 * disabled} result (no external call, no throw, nothing marked {@code CONFIRMED}) and {@link
 * #verifySignature} returns {@code false} (accepts no live webhook), so the rest of the system runs
 * unchanged until FOR-12 supplies credentials (Requirements 7.2, 7.5). A real przelewy24
 * implementation is pluggable later via config without redesign — mirroring the FOR-05-08
 * disabled-provider pattern.
 */
public interface OnlinePaymentProvider {

    /**
     * Initiates an online payment intent for the requested tranche/amount.
     *
     * @param req the initiate request (schedule, optional tranche, amount, return URL); never
     *            {@code null}
     * @return the initiate result — a live redirect/opaque result when a provider is configured, or
     *         the {@linkplain OnlineInitiateResult#disabled() disabled} result under the No-Op
     *         provider (Requirement 7.2)
     */
    OnlineInitiateResult initiate(InitiatePayment req);

    /**
     * Verifies the provider signature of an inbound webhook, so unverified calls can be rejected
     * (Requirement 7.5).
     *
     * @param raw the raw, unverified inbound webhook; never {@code null}
     * @return {@code true} iff the webhook signature is valid for a configured provider;
     *         {@code false} under the No-Op provider (no live webhook is accepted)
     */
    boolean verifySignature(RawWebhook raw);

    /**
     * Parses a (verified) inbound webhook into an {@link OnlineWebhookEvent} the transaction service
     * reconciles by {@code externalRef} (Requirement 7.3).
     *
     * @param raw the raw inbound webhook; never {@code null}
     * @return the parsed event
     */
    OnlineWebhookEvent parse(RawWebhook raw);
}
