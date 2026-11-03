package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-07-06 (Requirement 11; design §DTO sketches) — the worker payroll card read model.
 *
 * @param workerId               the worker's user id
 * @param workerName             the worker's name
 * @param workerTypeName         the resolved tier name (the base type's name on fallback)
 * @param seniorityPct           the worker's seniority fraction
 * @param workerTypeMissing      {@code true} when the base-tier fallback applied (R2.2 warning)
 * @param openingBalance         the opening balance (0 when no profile)
 * @param remainingAdvanceCredit the worker-wide remaining advance credit (R7.3)
 * @param months                 one block per calendar month overlapping {@code [from, to]}
 */
public record WorkerPayrollCardDto(Long workerId, String workerName, String workerTypeName, BigDecimal seniorityPct,
                                   boolean workerTypeMissing, BigDecimal openingBalance,
                                   BigDecimal remainingAdvanceCredit, List<PayrollMonthDto> months) {
}
