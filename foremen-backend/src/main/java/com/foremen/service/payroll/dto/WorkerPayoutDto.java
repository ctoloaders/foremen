package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;

/**
 * FOR-06-11 (design §Data Models / §API) — the read view of a single {@code WorkerPayout} row, as
 * rendered in the Payroll (Rozliczenie wynagrodzeń) tab's list.
 *
 * <p>A flat projection of {@code WorkerPayoutEntity}: the stored cash {@link #amount}, the creation
 * {@link #suggestedAmount} snapshot, the {@link #advanceApplied} credit offset, the
 * {@link #paymentChannel} / {@link #status} / {@link #isAdvance} classification, the period, the
 * optional note, and {@link #paidAt}. The {@link #workerName} is the worker's localized name
 * ({@code ru → pl → code} fallback) — the entity carries no i18n columns of its own (Requirement
 * 8.1). The {@code paymentChannel} / {@code status} enums are localized on the frontend via the
 * enum-label i18n convention, never surfaced as the raw constant (Requirements 2.3, 8.2).
 *
 * @param id              the payout id
 * @param workerId        the paid worker's user id
 * @param workerName      the worker's localized name ({@code ru → pl → code} fallback)
 * @param periodFrom      start of the payout period
 * @param periodTo        end of the payout period
 * @param amount          the cash amount paid (defaults to {@code suggestedAmount}, overridable, R1.4)
 * @param suggestedAmount the computed suggestion snapshot captured at creation, immutable after (R1.4)
 * @param advanceApplied  the advance-credit offset applied on a regular payout, else {@code 0} (R5.6)
 * @param paymentChannel  {@code BANK_TRANSFER} / {@code CASH} (R2.1)
 * @param status          {@code PLANNED} / {@code PAID} ({@code PAID} terminal, R7)
 * @param isAdvance        {@code true} when {@code periodTo} is after the creation date (R5.3)
 * @param paidAt          when it was marked paid; {@code null} while {@code PLANNED} (R7.1)
 * @param note            optional issuer note
 */
public record WorkerPayoutDto(
        Long id,
        Long workerId,
        String workerName,
        LocalDate periodFrom,
        LocalDate periodTo,
        BigDecimal amount,
        BigDecimal suggestedAmount,
        BigDecimal advanceApplied,
        PaymentChannel paymentChannel,
        PayoutStatus status,
        boolean isAdvance,
        OffsetDateTime paidAt,
        String note) {
}
