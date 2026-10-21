package com.foremen.service.payroll;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

/**
 * FOR-06-11 (R5.3): pure, persistence-free classifier of whether a worker payout is an
 * <em>advance</em>.
 *
 * <p>An advance is <strong>derived</strong> from the dates alone — never a flag the user sets: a
 * payout is an advance exactly when its {@code periodTo} falls after the payout's creation date.
 * This is the sole classification rule; the {@code WorkerPayout} entity may cache the result, but
 * the value is written only by this deriver.
 */
@Component
public final class AdvanceDeriver {

    /**
     * Derives whether a payout covering {@code [periodFrom, periodTo]} created on
     * {@code createdDate} is an advance.
     *
     * @param periodTo    the inclusive end of the payout period
     * @param createdDate the date the payout is created
     * @return {@code true} iff {@code periodTo} is strictly after {@code createdDate}
     */
    public boolean isAdvance(LocalDate periodTo, LocalDate createdDate) {
        return periodTo.isAfter(createdDate);
    }
}
