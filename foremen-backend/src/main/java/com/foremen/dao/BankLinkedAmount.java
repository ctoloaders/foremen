package com.foremen.dao;

import java.math.BigDecimal;

/**
 * FOR-07-04 — JPQL constructor projection of {@link BankTransactionLinkDao}: Σ link amount per target
 * id (payout / procurement document).
 *
 * @param targetId the payout or document id
 * @param total    Σ of the link amounts, never {@code null}
 */
public record BankLinkedAmount(Long targetId, BigDecimal total) {
}
