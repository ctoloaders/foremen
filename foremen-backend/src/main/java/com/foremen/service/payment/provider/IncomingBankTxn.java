package com.foremen.service.payment.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * FOR-06-07 (Requirement 8.1): one incoming transaction yielded by the mass-collect
 * {@link BankFeedProvider}.
 *
 * <p>It carries the amount, currency, provider-side {@code externalRef} (used for idempotent ingest
 * per Requirement 5.8), the received-at instant, and a {@code correlationKey} — the per-project
 * virtual account / payment reference the ingest uses to match the transaction to a project
 * schedule. A transaction that cannot be correlated is recorded <b>unmatched</b> without failing
 * ingest (Requirement 8.4).
 *
 * @param amount         the gross amount received; never {@code null}
 * @param currency       the ISO-4217 currency code; never {@code null}
 * @param externalRef    the bank-side reference used for idempotent ingest (Requirement 5.8); never
 *                       {@code null}
 * @param receivedAt     the instant the funds were received; never {@code null}
 * @param correlationKey the per-project virtual account / payment reference used to match the
 *                       transaction to a schedule, or {@code null} when absent (→ unmatched,
 *                       Requirement 8.4)
 */
public record IncomingBankTxn(
        BigDecimal amount,
        String currency,
        String externalRef,
        Instant receivedAt,
        String correlationKey) {
}
