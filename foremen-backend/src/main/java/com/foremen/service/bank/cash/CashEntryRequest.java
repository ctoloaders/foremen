package com.foremen.service.bank.cash;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.service.bank.link.LinkRequest;

/**
 * FOR-07-04 (Requirement 27.3) — a manual cash entry ("Add cash entry"); also used for edits, where
 * {@link #link} is ignored (links are managed through the link API).
 *
 * @param accountId        the cash account; {@code null} → the default PLN cash account (create only)
 * @param direction        expense (cash out) or income (cash in)
 * @param amount           positive magnitude; stored signed by {@code direction}
 * @param date             the operation / booking date
 * @param counterpartyName optional counterparty
 * @param title            optional description
 * @param link             optional link created in the same transaction (create only)
 */
public record CashEntryRequest(Long accountId, CashDirection direction, BigDecimal amount, LocalDate date,
                               String counterpartyName, String title, LinkRequest link) {
}
