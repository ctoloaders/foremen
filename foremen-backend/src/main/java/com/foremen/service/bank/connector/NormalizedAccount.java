package com.foremen.service.bank.connector;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * FOR-07-04 (Requirements 3.1, 4.2): a provider account normalized by the connector. The ledger
 * services upsert {@code BANK} accounts from it keyed by {@code (connection, externalId)}.
 *
 * @param externalId       provider account id
 * @param name             provider account name
 * @param nature           as reported (e.g. {@code account}, {@code card}, {@code savings})
 * @param iban             raw IBAN; nullable
 * @param accountNumber    non-IBAN account number; nullable
 * @param currency         ISO 4217 code
 * @param balance          current balance; nullable
 * @param availableBalance available balance; nullable
 * @param holderName       account holder name as reported; nullable
 * @param balanceAt        when the balance was reported; nullable
 */
public record NormalizedAccount(
        String externalId,
        String name,
        String nature,
        String iban,
        String accountNumber,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,
        String holderName,
        Instant balanceAt) {
}
