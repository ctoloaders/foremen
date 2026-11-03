package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

/**
 * FOR-07-06 (Requirement 9.2) — payload of {@code PUT /api/payroll/workers/{workerId}/opening-balance}.
 *
 * @param openingBalance any number, may be negative; {@code null} / non-numeric → 422
 *                       {@code error.payrollProfile.openingBalance.invalid}
 */
public record OpeningBalanceRequest(BigDecimal openingBalance) {
}
