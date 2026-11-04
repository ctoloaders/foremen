package com.foremen.service.bank.cash;

import java.math.BigDecimal;

import com.foremen.dao.model.LinkStatus;

/**
 * FOR-07-04 (Requirements 27.2, 27.3) — the result of a cash entry write.
 *
 * @param transactionId the ledger line id
 * @param accountId     the cash account id
 * @param amount        the signed amount
 * @param linkStatus    the refreshed link status ({@code LINKED} with a full link, else {@code UNLINKED} / partial)
 * @param linkId        the link created with the entry, or {@code null}
 */
public record CashEntryOutcome(Long transactionId, Long accountId, BigDecimal amount, LinkStatus linkStatus,
                               Long linkId) {
}
