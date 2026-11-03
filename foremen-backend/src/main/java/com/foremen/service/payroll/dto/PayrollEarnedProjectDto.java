package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-07-06 (Requirement 11.2; design §DTO sketches) — one earned row (per project) of a worker card
 * month block, drillable to its lines.
 *
 * @param projectId   the project id
 * @param projectName the project name
 * @param amount      Σ available line costs attributed to the month
 * @param lines       the project's lines attributed to the month
 */
public record PayrollEarnedProjectDto(Long projectId, String projectName, BigDecimal amount,
                                      List<PayrollEarnedLineDto> lines) {
}
