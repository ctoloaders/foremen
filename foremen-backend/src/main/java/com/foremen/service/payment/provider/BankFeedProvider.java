package com.foremen.service.payment.provider;

import java.util.List;

/**
 * FOR-06-07 (Requirements 8.1, 8.2; design §Components {@code Provider ports + No-Op stubs}): the
 * {@code Payment_Provider_Port} abstraction over the mass-collect bank incoming-transaction feed.
 *
 * <p>{@link #poll} yields the incoming bank transactions available to ingest — each an
 * {@link IncomingBankTxn} carrying amount, currency, {@code externalRef}, received-at, and a
 * per-project correlation key. The ingest creates a {@code BANK_TRANSFER}/{@code CONFIRMED}
 * transaction per correlated line (idempotent by {@code externalRef}, Requirement 5.8) and records
 * an uncorrelated line as unmatched (Requirement 8.4).
 *
 * <p>The concrete provider is <b>config-selected</b> via {@code foremen.payments.bankfeed.provider};
 * the default {@link NoOpBankFeedProvider} (active when the property is {@code none} or absent)
 * yields an empty list and never throws, so the rest of the system runs unchanged until FOR-12
 * supplies credentials (Requirement 8.2). A real bank implementation is pluggable later via config
 * without redesign — mirroring the FOR-05-08 disabled-provider pattern.
 */
public interface BankFeedProvider {

    /**
     * Polls the bank feed for incoming transactions available to ingest.
     *
     * @return the incoming transactions; an empty list under the No-Op provider (Requirement 8.2);
     *         never {@code null}
     */
    List<IncomingBankTxn> poll();
}
