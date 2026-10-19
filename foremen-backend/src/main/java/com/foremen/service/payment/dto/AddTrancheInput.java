package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-06-07 (Requirements 3.3, 3.4, 3.6): the input to the add-tranche-anytime action — appending a
 * single tranche by hand, permitted even while the schedule is {@code PUBLISHED}. The new tranche is
 * appended, its {@code seqNo} re-derived by {@code dueDate} (R3.2), and on a {@code PUBLISHED}
 * schedule {@code basisGross} is raised by {@link #amountGross} (an intentional change to the total
 * owed, R3.4) — never touching a {@code PAID} tranche or its allocations.
 *
 * <p>A negative/zero {@link #amountGross} or an absent {@link #dueDate} is rejected with
 * {@code error.payment.tranche.invalid} (R3.6).
 *
 * @param amountGross the new tranche's gross amount, strictly positive (R3.6)
 * @param dueDate     the new tranche's due date, required (R3.6)
 */
public record AddTrancheInput(
        BigDecimal amountGross,
        LocalDate dueDate) {
}
