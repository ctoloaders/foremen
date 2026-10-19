package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * FOR-06-07 (Requirements 8.5, 7.4, 5.10): the input to recording a MANUAL incoming payment — an
 * off-platform payment (e.g. cash, a reconciled bank line not on the feed) that an operator
 * registers and that reconciles identically through the nearest-open-tranche cascade (R8.5). The
 * recorded transaction is {@code channel = MANUAL}, {@code status = CONFIRMED}, attributed to the
 * acting user.
 *
 * @param amountGross the funds received, non-negative
 * @param feeGross    the client-borne commission, never deducted from the allocation (R7.4); may be
 *                    {@code null} (treated as zero)
 * @param receivedAt  when the funds were received; may be {@code null} (the service stamps now)
 * @param note        an optional free-text note (R5.10), or {@code null}
 */
public record ManualTxnInput(
        BigDecimal amountGross,
        BigDecimal feeGross,
        OffsetDateTime receivedAt,
        String note) {
}
