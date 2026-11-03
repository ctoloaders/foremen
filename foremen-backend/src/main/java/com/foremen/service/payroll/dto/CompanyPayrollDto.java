package com.foremen.service.payroll.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * FOR-07-06 (Requirement 10; design §DTO sketches) — the company payroll table read model.
 *
 * @param from   the period start (inclusive)
 * @param to     the period end (inclusive)
 * @param rows   one row per visible worker
 * @param totals the totals row over all visible workers (R10.5)
 */
public record CompanyPayrollDto(LocalDate from, LocalDate to, List<CompanyPayrollRowDto> rows, PayrollSumsDto totals) {
}
