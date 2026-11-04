package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.service.payment.PaymentChannel;

/**
 * FOR-07-04 (Requirement 16.6; design §FOR-06-07 seam): the input to recording a {@code CONFIRMED}
 * client payment from a company money-ledger line through
 * {@code PaymentTransactionService.recordBankTransfer(scheduleId, BankTransferInput)}.
 *
 * @param amount      the funds received (gross), non-negative
 * @param currency    the ledger line currency (informational; the schedule is PLN-denominated)
 * @param bookingDate the ledger line booking date, stored as the transaction {@code receivedAt}
 * @param externalRef the idempotency key, {@code "bank-txn:{transactionId}:{linkId}"}
 * @param title       the ledger line title, stored as the transaction note; may be {@code null}
 * @param channel     {@code BANK_TRANSFER} for a bank line, {@code MANUAL} for a cash line
 */
public record BankTransferInput(
        BigDecimal amount,
        String currency,
        LocalDate bookingDate,
        String externalRef,
        String title,
        PaymentChannel channel) {
}
