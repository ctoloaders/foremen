package com.foremen.service.payment.dto;

import java.math.BigDecimal;

/**
 * FOR-06-07 (Requirement 6): the input to re-planning the unpaid remainder of a {@code PUBLISHED}
 * schedule after an amendment changes the project total. The service recomputes {@code basisGross}
 * to {@link #newBasisGross} and re-authors ONLY the still-open (not {@code PAID}) tranches so they
 * re-sum to the unpaid remainder ({@code newBasisGross − Σ paidGross}); every {@code PAID} tranche
 * and its allocations stay immutable and the schedule stays {@code PUBLISHED} (R6.1, R6.2, R6.4).
 *
 * <p>{@link #generate} optionally carries a fresh generator spec for the open tranches; when
 * {@code null} the open tranches are re-authored in place keeping their count/dates and only their
 * amounts re-proportioned to the new remainder.
 *
 * @param newBasisGross the new project total to re-plan against (R6.1)
 * @param generate      an optional generator spec for the open tranches, or {@code null} to
 *                      re-proportion the existing open tranches in place
 */
public record RePlanRequest(
        BigDecimal newBasisGross,
        GenerateRequest generate) {
}
