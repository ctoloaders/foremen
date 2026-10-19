package com.foremen.service.payment.provider;

/**
 * FOR-06-07 (Requirements 7.1, 7.2): the outcome of {@link OnlinePaymentProvider#initiate} — the
 * opaque result of creating an online (przelewy24) payment intent.
 *
 * <p>When a live provider is configured, {@code enabled} is {@code true}, {@code externalRef}
 * carries the provider-side correlation id, and {@code redirectUrl} is the URL the payer is sent to
 * for the payment ceremony. Under the {@code No_Op_Provider} (no credentials configured) the result
 * is <b>disabled</b> — {@code enabled == false}, no external call was made, no {@code redirectUrl},
 * and nothing is marked {@code CONFIRMED} (Requirement 7.2).
 *
 * @param enabled     {@code true} iff a live provider created a real payment intent; {@code false}
 *                    for the disabled No-Op result
 * @param externalRef the provider-side correlation reference, or {@code null} under the No-Op
 *                    provider
 * @param redirectUrl the URL to redirect the payer to for the provider ceremony, or {@code null}
 *                    under the No-Op provider
 */
public record OnlineInitiateResult(boolean enabled, String externalRef, String redirectUrl) {

    /**
     * The canonical disabled result returned by the No-Op online provider: no external call, no
     * redirect, nothing confirmed (Requirement 7.2).
     *
     * @return a disabled {@code OnlineInitiateResult}
     */
    public static OnlineInitiateResult disabled() {
        return new OnlineInitiateResult(false, null, null);
    }
}
