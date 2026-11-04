package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-04 (Requirements 28.1, 28.4) — body of {@code POST /api/procurement-documents/{id}/paid-in-cash}.
 * Every field is optional.
 *
 * @param cashAccountId the cash account; {@code null} → the default cash account
 * @param amount        the paid amount; {@code null} → the document total
 * @param date          the payment date (ISO); {@code null} → the invoice date, else the upload date
 */
public record ProcurementPaidInCashRequest(Long cashAccountId, BigDecimal amount, LocalDate date) {
}
