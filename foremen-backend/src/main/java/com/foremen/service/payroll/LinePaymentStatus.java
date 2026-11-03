package com.foremen.service.payroll;

import java.math.BigDecimal;

/**
 * Pure payment status of one approved work-report room line (FOR-07-06, Requirement 12.1;
 * design §Work-report payment status, Property 10 "Line status").
 *
 * <ul>
 *   <li>{@link #UNAVAILABLE} — the line cost cannot be resolved ({@code cost == null}); checked first.</li>
 *   <li>{@link #UNPAID} — paid amount is 0 ({@code null} paid is treated as 0).</li>
 *   <li>{@link #PAID} — paid amount ≥ cost.</li>
 *   <li>{@link #PARTIAL} — otherwise ({@code 0 < paid < cost}).</li>
 * </ul>
 *
 * <p>Precedence follows the glossary order: a zero-cost line with nothing paid is {@code UNPAID}
 * (the "paid = 0" rule wins over "paid ≥ cost"). A non-positive paid amount is treated as 0
 * (allocations are always {@code > 0}, so this is purely defensive). Comparisons use
 * {@link BigDecimal#compareTo}, so the scale is irrelevant.
 *
 * <p>No Spring, no persistence — a deterministic function of its arguments. The report aggregate
 * lives in {@link ReportPaymentStatus}.
 */
public enum LinePaymentStatus {
    UNPAID,
    PARTIAL,
    PAID,
    UNAVAILABLE;

    /**
     * Status of a single line (R12.1).
     *
     * @param cost       the line cost value, {@code null} when unavailable
     * @param paidAmount Σ allocation amounts on the line ({@code null} → 0)
     */
    public static LinePaymentStatus of(BigDecimal cost, BigDecimal paidAmount) {
        if (cost == null) {
            return UNAVAILABLE;
        }
        if (paidAmount == null || paidAmount.signum() <= 0) {
            return UNPAID;
        }
        return paidAmount.compareTo(cost) >= 0 ? PAID : PARTIAL;
    }
}
