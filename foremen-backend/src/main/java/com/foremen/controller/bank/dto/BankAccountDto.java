package com.foremen.controller.bank.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.HolderMatch;

/**
 * FOR-07-04 (Requirements 3.2, 3.8, 7.3, 19.5, 27.1) — one row of the Accounts tab: a {@code BANK}
 * account of a connection or a {@code CASH} register.
 *
 * <p>For {@code BANK} rows {@link #balance()} is the provider balance; for {@code CASH} rows it is the
 * computed balance ({@code openingBalance + Σ entries}) and the bank-only fields are {@code null} /
 * {@code false}. The provider cursor is never exposed.
 *
 * @param holderWarning {@code true} while the holder-mismatch warning shows ({@code MISMATCH} without
 *                      an override)
 */
public record BankAccountDto(
        Long id,
        BankAccountType type,
        Long connectionId,
        String bankName,
        String bankLogoUrl,
        String name,
        String nature,
        String holderName,
        HolderMatch holderMatch,
        boolean holderWarning,
        String holderOverrideBy,
        OffsetDateTime holderOverrideAt,
        String holderOverrideReason,
        String iban,
        String accountNumber,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,
        OffsetDateTime balanceUpdatedAt,
        BigDecimal openingBalance,
        boolean includedInLedger,
        boolean closedAtProvider,
        boolean hasBalanceGap,
        boolean defaultForCash,
        boolean archived) {
}
