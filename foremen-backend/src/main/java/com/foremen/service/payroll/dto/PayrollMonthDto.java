package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * FOR-07-06 (Requirements 11.2, 11.3; design §DTO sketches) — one month block of the worker card.
 *
 * @param month           the calendar month
 * @param earned          earned rows, one per project
 * @param additionalLines the month's additional payroll lines
 * @param total           Σ earned rows + Σ additional lines
 * @param payouts         the month's payouts (by payout date)
 * @param paid            Σ payout amounts
 * @param previousBalance the balance at the end of the previous month
 * @param balance         {@code previousBalance + total − paid}
 * @param hints           previous-month hints; only for the current calendar month, else empty
 */
public record PayrollMonthDto(YearMonth month, List<PayrollEarnedProjectDto> earned, List<PayrollLineDto> additionalLines,
                              BigDecimal total, List<WorkerPayoutDto> payouts, BigDecimal paid,
                              BigDecimal previousBalance, BigDecimal balance, List<PayrollLineDto> hints) {
}
