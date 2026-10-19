package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.service.payment.TrancheStatus;

/**
 * FOR-06-07 (Requirement 1.4): the read model of a single {@code PaymentTranche} inside a
 * {@link PaymentScheduleView}. A persistence-free value populated from
 * {@code PaymentTrancheEntity} by {@code PaymentDtoMapper}.
 *
 * <p>{@link #status} is the materialized cache of the derived {@code TrancheStatus} — localized on
 * the frontend via the {@code Enum_Label_I18n} convention, never surfaced as the raw constant
 * (R13.2, R13.3).
 *
 * @param id          the tranche id
 * @param seqNo       the contiguous {@code 1..N} ordinal, ordered by {@code dueDate} (R2.1, R3.2)
 * @param dueDate     the tranche due date (drives overdue detection, R10.1)
 * @param amountGross the due amount, strictly positive (R3.6)
 * @param paidGross   the running settled total accumulated across allocations (R5.2)
 * @param status      the derived tranche status (R10.1)
 */
public record PaymentTrancheDto(
        Long id,
        Integer seqNo,
        LocalDate dueDate,
        BigDecimal amountGross,
        BigDecimal paidGross,
        TrancheStatus status) {
}
