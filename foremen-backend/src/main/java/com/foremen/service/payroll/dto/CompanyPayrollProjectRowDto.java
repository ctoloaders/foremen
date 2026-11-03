package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

/**
 * FOR-07-06 (Requirement 10.3; design §DTO sketches) — one project sub-row of a worker row in the
 * company payroll table.
 *
 * @param projectId   the project id
 * @param projectName the project name
 * @param reported    Σ available line costs of the worker's lines on this project in the period
 * @param planned     Σ planned-basis cost of those lines
 * @param paid        Σ allocation amounts on this project's lines whose payout is attributed to the period
 */
public record CompanyPayrollProjectRowDto(Long projectId, String projectName, BigDecimal reported,
                                          BigDecimal planned, BigDecimal paid) {
}
