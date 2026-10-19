package com.foremen.service.payment.dto;

import java.time.OffsetDateTime;

/**
 * FOR-06-07 (Requirements 5.9, 5.10, 5.11): the input to the mark-tranche-paid action — settling a
 * specific tranche confirmed off-platform (cash, an agreed waiver, a reconciled bank line) directly,
 * independent of the nearest-open cascade. The service records a {@code MANUAL}/{@code CONFIRMED}
 * transaction for the tranche's <b>remaining</b> amount, allocates it to exactly that tranche, and
 * derives it {@code PAID}; it is idempotent (a {@code PAID} tranche re-marked is a no-op, R5.11).
 *
 * <p>Both fields are optional (R5.10): {@link #receivedAt} defaults to now and {@link #note} to none.
 *
 * @param note       an optional free-text note (R5.10), or {@code null}
 * @param receivedAt the settlement date (R5.10); may be {@code null} (the service stamps now)
 */
public record ManualPayInput(
        String note,
        OffsetDateTime receivedAt) {
}
