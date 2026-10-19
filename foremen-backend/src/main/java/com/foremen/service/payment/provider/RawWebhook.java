package com.foremen.service.payment.provider;

import java.util.Map;

/**
 * FOR-06-07 (Requirements 7.3, 7.5): the raw, unverified inbound online-payment (przelewy24)
 * webhook as received by the public webhook endpoint, before any provider-signature verification or
 * parsing.
 *
 * <p>It is handed to {@link OnlinePaymentProvider#verifySignature} (which rejects unverified calls
 * when credentials are present, and returns {@code false} under the No-Op provider — Requirement
 * 7.5) and to {@link OnlinePaymentProvider#parse} (which turns a verified payload into an
 * {@link OnlineWebhookEvent}).
 *
 * @param body    the raw request body bytes exactly as received (used for signature verification);
 *                never {@code null}
 * @param headers the request headers (e.g. the provider signature header); never {@code null}
 */
public record RawWebhook(byte[] body, Map<String, String> headers) {
}
