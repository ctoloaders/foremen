package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PaymentChannel;

/**
 * FOR-06-11 (Requirement 4.1) — the create payload for a worker payout. The service derives
 * {@code isAdvance} from {@code periodTo} vs. the creation date (never a caller flag, R5.3), computes
 * the {@code suggestedAmount} snapshot, and defaults {@link #amount} to it when {@code null} (an
 * overridable cash figure, independent of the linkage/credit, R4.4). The owning project is resolved
 * from the project-scope context, not supplied here.
 *
 * <p>The service validates the period ({@code periodTo >= periodFrom}, R4.5), the channel (required,
 * R2.2), the amount ({@code >= 0}, R4.4), and ACTIVE membership of the worker (R4.6) before
 * persisting a {@code PLANNED} payout.
 *
 * @param workerId       the worker to pay (required, must be an ACTIVE project member, R4.6)
 * @param periodFrom     start of the payout period (required)
 * @param periodTo       end of the payout period (required, {@code >= periodFrom}, R4.5)
 * @param paymentChannel {@code BANK_TRANSFER} / {@code CASH} (required, R2.1, R2.2)
 * @param amount         the cash amount; {@code null} defaults to the computed suggestion (R4.4)
 * @param note           an optional issuer note
 */
public record CreatePayoutRequest(
        Long workerId,
        LocalDate periodFrom,
        LocalDate periodTo,
        PaymentChannel paymentChannel,
        BigDecimal amount,
        String note) {
}
