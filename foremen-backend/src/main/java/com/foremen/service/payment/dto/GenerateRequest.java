package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.service.payment.GeneratorStrategy;
import com.foremen.service.payment.Periodicity;

/**
 * FOR-06-07 (Requirement 2): the input to a tranche-generator run on a {@code DRAFT} schedule. The
 * service passes these through to the pure {@code TrancheGenerator}, which splits {@code basisGross}
 * into tranches by the chosen {@link #strategy} and {@link #periodicity}, replacing any existing
 * (all-unpaid) tranches (R2.6).
 *
 * <p>{@link #parts} is interpreted per strategy: ignored for {@code EQUAL} (use {@link #count}), an
 * ordered list of percentages summing to {@code 100} for {@code PERCENT}, or an ordered list of
 * explicit amounts summing to {@code basisGross} for {@code CUSTOM} (R2.2–2.4). {@link #customDates}
 * supplies the per-tranche due dates when {@link #periodicity} is {@code CUSTOM_DATES}; otherwise the
 * due dates step from {@link #startDate} by the periodicity.
 *
 * @param strategy    the split strategy {@code EQUAL}/{@code PERCENT}/{@code CUSTOM} (R2.1)
 * @param count       the tranche count N for {@code EQUAL}; ignored for {@code PERCENT}/{@code CUSTOM}
 *                    (which derive N from {@link #parts}); must be {@code >= 1} (R2.5)
 * @param parts       the percentages ({@code PERCENT}) or amounts ({@code CUSTOM}); {@code null}/empty
 *                    for {@code EQUAL}
 * @param startDate   the first tranche's due date; required (R2.5)
 * @param periodicity the due-date cadence {@code WEEKLY}/{@code BIWEEKLY}/{@code MONTHLY}/{@code CUSTOM_DATES} (R2.1)
 * @param customDates the explicit due dates for {@code CUSTOM_DATES}; {@code null} otherwise
 */
public record GenerateRequest(
        GeneratorStrategy strategy,
        Integer count,
        List<BigDecimal> parts,
        LocalDate startDate,
        Periodicity periodicity,
        List<LocalDate> customDates) {
}
