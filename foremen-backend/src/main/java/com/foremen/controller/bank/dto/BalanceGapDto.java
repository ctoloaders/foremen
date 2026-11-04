package com.foremen.controller.bank.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * FOR-07-04 (Requirement 7.3) — a balance-continuity gap of a bank account.
 *
 * @param hasBalanceGap the account's current {@code hasBalanceGap} flag (still {@code true} while
 *                      any unacknowledged gap remains)
 */
public record BalanceGapDto(
        Long id,
        Long accountId,
        OffsetDateTime periodFrom,
        OffsetDateTime periodTo,
        BigDecimal expected,
        BigDecimal actual,
        BigDecimal difference,
        OffsetDateTime acknowledgedAt,
        String acknowledgedBy,
        String note,
        LocalDateTime createdDate,
        boolean hasBalanceGap) {
}
