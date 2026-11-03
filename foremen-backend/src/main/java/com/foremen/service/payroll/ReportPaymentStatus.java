package com.foremen.service.payroll;

import java.util.Collection;

/**
 * Pure aggregate payment status of an approved work report (FOR-07-06, Requirement 12.1;
 * design §Work-report payment status).
 *
 * <p>Computed from the {@link LinePaymentStatus} of the report's room lines; lines that are
 * {@link LinePaymentStatus#UNAVAILABLE UNAVAILABLE} (or {@code null}) are ignored:
 * <ul>
 *   <li>{@code UNAVAILABLE} — no line has an available cost (also for an empty/{@code null} input);</li>
 *   <li>{@code PAID} — all available lines are {@code PAID};</li>
 *   <li>{@code UNPAID} — no available line has a paid amount (all are {@code UNPAID});</li>
 *   <li>{@code PARTIAL} — otherwise.</li>
 * </ul>
 *
 * <p>The aggregate uses the same value set as the line status, so it is returned as a
 * {@link LinePaymentStatus}. Non-{@code APPROVED} reports have no payment status — that rule is
 * applied by the caller ({@code WorkReportService}), not here.
 *
 * <p>No Spring, no persistence — a deterministic function of its argument.
 */
public final class ReportPaymentStatus {

    private ReportPaymentStatus() {
    }

    /**
     * Aggregate status of a report (R12.1).
     *
     * @param lines the per-line statuses ({@code null} → empty; {@code null} elements ignored)
     */
    public static LinePaymentStatus of(Collection<LinePaymentStatus> lines) {
        if (lines == null) {
            return LinePaymentStatus.UNAVAILABLE;
        }
        int available = 0;
        int paid = 0;
        int unpaid = 0;
        for (LinePaymentStatus line : lines) {
            if (line == null || line == LinePaymentStatus.UNAVAILABLE) {
                continue;
            }
            available++;
            if (line == LinePaymentStatus.PAID) {
                paid++;
            } else if (line == LinePaymentStatus.UNPAID) {
                unpaid++;
            }
        }
        if (available == 0) {
            return LinePaymentStatus.UNAVAILABLE;
        }
        if (paid == available) {
            return LinePaymentStatus.PAID;
        }
        if (unpaid == available) {
            return LinePaymentStatus.UNPAID;
        }
        return LinePaymentStatus.PARTIAL;
    }
}
