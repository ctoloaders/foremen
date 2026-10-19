package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.service.payment.PaymentChannel;
import com.foremen.service.payment.TransactionStatus;

/**
 * FOR-06-07 (Requirements 5.7, 5.8, 7.4, 8.4, 5.10): the read model of one recorded incoming payment
 * against a project's schedule, with its per-tranche {@link TrancheAllocationDto allocations}.
 *
 * <p>{@link #channel} and {@link #status} are enums localized on the frontend via the
 * {@code Enum_Label_I18n} convention, never surfaced as the raw constant (R13.2, R13.3).
 * {@link #feeGross} is the commission under the {@code Payer_Borne_Fee} model — recorded for
 * reporting, never deducted from the amount allocated to tranches (R7.4). {@link #allocations} is
 * empty while a transaction is unmatched (R8.4) or not yet confirmed.
 *
 * @param id          the transaction id
 * @param channel     the payment channel ({@code BANK_TRANSFER}/{@code ONLINE}/{@code MANUAL})
 * @param externalRef the bank / przelewy24 reference (idempotency key, R5.8), or {@code null}
 * @param amountGross the funds received
 * @param feeGross    the client-borne commission, never deducted from the allocation (R7.4)
 * @param receivedAt  when the funds were received
 * @param status      the transaction status ({@code PENDING}/{@code CONFIRMED}/{@code FAILED}/{@code REFUNDED})
 * @param note        the optional free-text on a MANUAL record / mark-paid (R5.10), or {@code null}
 * @param allocations how this transaction was applied to tranches (R5.7)
 */
public record PaymentTransactionDto(
        Long id,
        PaymentChannel channel,
        String externalRef,
        BigDecimal amountGross,
        BigDecimal feeGross,
        OffsetDateTime receivedAt,
        TransactionStatus status,
        String note,
        List<TrancheAllocationDto> allocations) {
}
