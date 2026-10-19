package com.foremen.service.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single tranche produced by {@link TrancheGenerator} — a pure, persistence-free value.
 *
 * @param seqNo       the 1-based position in the schedule (contiguous {@code 1..N}, ordered by
 *                    {@code dueDate}) (Requirement 2.1)
 * @param amountGross the tranche gross amount, rounded to 2 decimals; the generated set sums to
 *                    exactly {@code basisGross} (Requirement 2.2)
 * @param dueDate     the tranche due date, assigned by the chosen periodicity (Requirement 2.1)
 */
public record GeneratedTranche(int seqNo, BigDecimal amountGross, LocalDate dueDate) {
}
