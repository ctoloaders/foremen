package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

/**
 * FOR-07-06 (Requirement 9.1, 9.2; design §PayrollProfileService) — a worker's payroll profile:
 * the opening balance the monthly balance chain starts from ({@code 0} when no profile row exists).
 *
 * @param workerId       the worker's user id
 * @param openingBalance the signed opening balance (scale 2)
 */
public record PayrollProfileDto(Long workerId, BigDecimal openingBalance) {
}
