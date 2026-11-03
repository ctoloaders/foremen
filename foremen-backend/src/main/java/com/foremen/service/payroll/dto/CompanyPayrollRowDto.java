package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-07-06 (Requirement 10.2; design §DTO sketches) — one worker row of the company payroll table.
 *
 * @param workerId          the worker's user id
 * @param workerName        the worker's name
 * @param workerTypeName    the resolved tier name (the base type's name on fallback)
 * @param seniorityPct      the worker's seniority fraction
 * @param workerTypeMissing {@code true} when the base-tier fallback applied (R2.2 warning)
 * @param sums              the period sums
 * @param balanceAtTo       the balance at the end of the month of {@code to} (data up to {@code to})
 * @param projects          the per-project sub-rows
 */
public record CompanyPayrollRowDto(Long workerId, String workerName, String workerTypeName, BigDecimal seniorityPct,
                                   boolean workerTypeMissing, PayrollSumsDto sums, BigDecimal balanceAtTo,
                                   List<CompanyPayrollProjectRowDto> projects) {
}
