package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-11 (Requirement 3) — the create-dialog preview: the gross over the worker's unpaid
 * {@code APPROVED} work in the chosen period, the credit arithmetic, the suggested cash amount, and
 * the per-line breakdown. Computed by {@code WorkerPayoutService.suggest} /
 * {@code PayoutSuggestionService} without persisting anything.
 *
 * <p>For a <b>non-advance</b> period: {@link #gross} is {@code Σ Cost_Value} over the available
 * unpaid in-period lines (FOR-06-10's cost, verbatim), {@link #appliedCredit} is
 * {@code min(remainingCredit, gross)}, and {@link #suggested} is {@code max(0, gross −
 * remainingCredit)} (Requirement 3.2). For an <b>advance</b> period ({@link #isAdvance} {@code
 * true}) the basis is the worker's planned work and <b>no</b> credit is deducted
 * ({@code appliedCredit = 0}, Requirement 3.5).
 *
 * @param gross           {@code Σ Cost_Value} over the available (unpaid, in-period) lines (R3.1)
 * @param remainingCredit the worker's remaining advance credit entering this payout (R5.8)
 * @param appliedCredit   {@code min(remainingCredit, gross)} for a regular payout, {@code 0} for an advance (R3.2, R3.5)
 * @param suggested       {@code max(0, gross − remainingCredit)} — the default cash amount (R3.2)
 * @param isAdvance        {@code true} when the period is forward-looking (advance basis, R3.5)
 * @param breakdown       the per-line contribution, unavailable lines marked + excluded (R3.3, R3.4)
 */
public record PayoutSuggestionDto(
        BigDecimal gross,
        BigDecimal remainingCredit,
        BigDecimal appliedCredit,
        BigDecimal suggested,
        boolean isAdvance,
        List<PayoutLineDto> breakdown) {
}
