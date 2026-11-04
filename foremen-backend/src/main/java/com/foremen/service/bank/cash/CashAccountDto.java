package com.foremen.service.bank.cash;

import java.math.BigDecimal;

/**
 * FOR-07-04 (Requirement 27.1) — a cash account with its computed balance
 * ({@code openingBalance + Σ entry amounts}).
 */
public record CashAccountDto(Long id, String name, String currency, BigDecimal openingBalance,
                             BigDecimal balance, boolean defaultForCash, boolean archived) {
}
