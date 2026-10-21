package com.foremen.service.payroll;

import java.time.LocalDate;
import java.util.Objects;

/**
 * FOR-06-11 (design §PeriodResolver) — an immutable, persistence-free {@code [from, to]} calendar
 * interval resolved from a {@link PeriodResolver.QuickSelect} option. Both bounds are inclusive
 * {@link LocalDate}s.
 *
 * <p>This is the return shape of {@link PeriodResolver#resolve}. The repository carries no existing
 * {@code DateRange} value type, so this small record is introduced alongside the resolver that
 * produces it. It is deliberately minimal — it holds the two period dates and nothing else — so the
 * pure quick-select math stays directly jqwik-testable without any Spring/JPA context.
 *
 * @param from the inclusive period start ({@code periodFrom}), never {@code null}
 * @param to   the inclusive period end ({@code periodTo}), never {@code null}
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** @return {@code true} iff {@code to} is strictly after {@code from} is NOT required — bounds may coincide. */
    public boolean isValid() {
        return !to.isBefore(from);
    }
}
