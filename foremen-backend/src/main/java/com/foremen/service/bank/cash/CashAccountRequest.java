package com.foremen.service.bank.cash;

import java.math.BigDecimal;

/**
 * FOR-07-04 (Requirement 27.1) — create / edit payload of a cash account. On update a {@code null}
 * field keeps the current value.
 *
 * @param name           user-entered register name (required on create)
 * @param currency       ISO 4217 code (required on create; changeable only while the account has no entries)
 * @param openingBalance optional opening balance ({@code null} → 0 on create)
 * @param defaultForCash {@code true} makes it the default register of its currency (clears the others)
 * @param archived       {@code true} archives the register (it stops being the default)
 */
public record CashAccountRequest(String name, String currency, BigDecimal openingBalance,
                                 Boolean defaultForCash, Boolean archived) {
}
