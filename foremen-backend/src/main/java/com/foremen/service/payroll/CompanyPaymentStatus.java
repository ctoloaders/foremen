package com.foremen.service.payroll;

import java.math.BigDecimal;

/**
 * Pure per-worker payment-status summary of one company-payroll row (FOR-07-06 Round 1 / bug E;
 * design §Company payment status). Classifies a worker from the figures the company table already
 * computes for the period — no extra query.
 *
 * <p><b>Precedence</b> (first matching rule wins):
 * <ol>
 *   <li>{@link #NO_ACTIVITY} — the worker has no planned, no reported and no paid work in the
 *       period and the balance at {@code to} is zero (planning-mode filler row). Checked first so a
 *       truly idle worker is never mislabelled "fully paid".</li>
 *   <li>{@link #HAS_DEBT} — the company still owes the worker: the balance at {@code to} is strictly
 *       positive (earned &gt; paid over the whole history). This is the money-owed signal and wins
 *       over the planning signal below.</li>
 *   <li>{@link #UNREPORTED_WORK} — there is planned work that exceeds what has been reported
 *       ({@code planned > reported}); the worker has outstanding work to report but no outstanding
 *       debt.</li>
 *   <li>{@link #FULLY_PAID} — otherwise: there was activity, the balance is settled
 *       ({@code <= 0}) and nothing is left to report.</li>
 * </ol>
 *
 * <p>Comparisons use {@link BigDecimal#compareTo}, so scale is irrelevant; a {@code null} figure is
 * treated as zero (defensive — the table always supplies scaled, non-null sums). No Spring, no
 * persistence — a deterministic function of its arguments.
 */
public enum CompanyPaymentStatus {
    /** No planned / reported / paid work in the period and a zero balance at {@code to}. */
    NO_ACTIVITY,
    /** The company owes the worker (balance at {@code to} is strictly positive). */
    HAS_DEBT,
    /** Planned work exceeds reported work ({@code planned > reported}) with no outstanding debt. */
    UNREPORTED_WORK,
    /** Activity exists, the balance is settled and nothing is left to report. */
    FULLY_PAID;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * Classify a worker row from its period figures and closing balance (bug E).
     *
     * @param reported    Σ available reported-line costs attributed to the period
     * @param planned     Σ planned-basis cost ({@code plan_quantity}) of those lines
     * @param paid        Σ payout amounts attributed to the period
     * @param balanceAtTo the running balance at the end of the month of {@code to}
     * @return the summary status; never {@code null}
     */
    public static CompanyPaymentStatus of(BigDecimal reported, BigDecimal planned, BigDecimal paid,
                                          BigDecimal balanceAtTo) {
        BigDecimal reportedValue = reported == null ? ZERO : reported;
        BigDecimal plannedValue = planned == null ? ZERO : planned;
        BigDecimal paidValue = paid == null ? ZERO : paid;
        BigDecimal balanceValue = balanceAtTo == null ? ZERO : balanceAtTo;

        boolean noActivity = reportedValue.signum() == 0
                && plannedValue.signum() == 0
                && paidValue.signum() == 0
                && balanceValue.signum() == 0;
        if (noActivity) {
            return NO_ACTIVITY;
        }
        if (balanceValue.signum() > 0) {
            return HAS_DEBT;
        }
        if (plannedValue.compareTo(reportedValue) > 0) {
            return UNREPORTED_WORK;
        }
        return FULLY_PAID;
    }
}
