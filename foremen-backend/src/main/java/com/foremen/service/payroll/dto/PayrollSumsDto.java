package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

/**
 * FOR-07-06 (Requirement 10.4; design §DTO sketches) — the period sums of a company payroll row (or
 * the totals row). All values are scale 2; unavailable line costs are excluded (never a fabricated
 * zero).
 *
 * @param earned   Reported work + Other payouts
 * @param paid     Σ payout amounts attributed to the period (PLANNED and PAID)
 * @param reported Σ available line costs of the APPROVED lines attributed to the period
 * @param planned  Σ planned-basis cost ({@code plan_quantity}) of those lines
 * @param other    Σ additional-line amounts attributed to the period
 */
public record PayrollSumsDto(BigDecimal earned, BigDecimal paid, BigDecimal reported, BigDecimal planned,
                             BigDecimal other) {
}
