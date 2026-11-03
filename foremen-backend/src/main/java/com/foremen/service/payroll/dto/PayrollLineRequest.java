package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-06 (Requirement 8.1; design §Controllers and endpoints) — create / update payload of an
 * additional payroll line ({@code POST /api/payroll/lines}, {@code PUT /api/payroll/lines/{id}}).
 *
 * @param workerId the worker (must be an existing WORKER user, R8.7)
 * @param month    any day of the month; normalized to the first day (R8.1)
 * @param name     free text, 1–255 characters after trimming (R8.2)
 * @param amount   signed, non-zero (R8.3)
 * @param note     optional note
 */
public record PayrollLineRequest(Long workerId, LocalDate month, String name, BigDecimal amount, String note) {
}
