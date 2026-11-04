package com.foremen.service.bank.link;

import com.foremen.dao.model.LinkStatus;

/**
 * FOR-07-04 — the result of a link mutation on a ledger line.
 *
 * @param transactionId    the ledger line
 * @param linkId           the created / replaced link, {@code null} for removals and line-level operations
 * @param linkStatus       the recomputed {@code link_status} cache
 * @param payoutStaysPaid  {@code true} when a removed / replaced {@code WORKER_PAYOUT} link pointed to a
 *                         {@code PAID} payout that stays paid (R16.8 — the UI warns)
 */
public record LinkOutcome(Long transactionId, Long linkId, LinkStatus linkStatus, boolean payoutStaysPaid) {
}
