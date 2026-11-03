package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PaymentChannel;

/**
 * FOR-06-11 / FOR-07-06 (Requirement 5.1) — the create payload for a <b>worker-wide</b> payout. It
 * carries no project. The service derives {@code isAdvance} from {@code periodTo} vs. the creation
 * date (never a caller flag), computes the {@code suggestedAmount} snapshot, and defaults
 * {@link #amount} to it when {@code null} (an overridable cash figure, R5.7).
 *
 * <p>The service validates, in order, the period ({@code periodTo >= periodFrom}), the channel
 * (required), the amount ({@code >= 0}), and that the worker is an existing WORKER user before
 * persisting a {@code PLANNED} payout (R5.7).
 *
 * @param workerId       the worker to pay (required, must be a WORKER user)
 * @param payoutDate     the payout date; {@code null} defaults to today (R5.1)
 * @param periodFrom     start of the payout period (required)
 * @param periodTo       end of the payout period (required, {@code >= periodFrom})
 * @param paymentChannel {@code BANK_TRANSFER} / {@code CASH} (required)
 * @param amount         the cash amount; {@code null} defaults to the computed suggestion
 * @param note           an optional issuer note
 */
public record CreatePayoutRequest(
        Long workerId,
        LocalDate payoutDate,
        LocalDate periodFrom,
        LocalDate periodTo,
        PaymentChannel paymentChannel,
        BigDecimal amount,
        String note) {
}
