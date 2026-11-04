package com.foremen.service.procurement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-04 (Requirements 28.1, 28.4; design §Cash ledger — {@code ProcurementCashHook}) — the
 * resolved "paid in cash" command handed by {@code ProcurementDocumentService} to the
 * {@link ProcurementCashPaymentHandler}: a SYSTEM cash expense entry
 * ({@code PROCUREMENT_CASH}, {@code documentId}) of {@code amount} on {@code date} is created on the
 * given (or default) cash account and linked {@code PROCUREMENT} to the document.
 *
 * @param documentId    the procurement document being paid
 * @param cashAccountId the cash account, or {@code null} for the default cash account
 * @param amount        the paid amount, {@code > 0} (never {@code null})
 * @param date          the payment date (never {@code null}; invoice date when known, else upload date)
 */
public record ProcurementCashPayment(
        Long documentId,
        Long cashAccountId,
        BigDecimal amount,
        LocalDate date) {
}
