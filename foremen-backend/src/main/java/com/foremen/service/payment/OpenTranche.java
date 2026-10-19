package com.foremen.service.payment;

import java.math.BigDecimal;

/**
 * An open (not-yet-{@code PAID}) tranche presented to {@link ReconciliationEngine#plan}, reduced to
 * exactly the two facts the cascade needs: its ordering key and how much is still owed on it.
 *
 * <p>Pure, persistence-free value. The caller supplies the list already ordered nearest-open-first
 * by {@code seqNo} (the lowest-{@code seqNo} unsettled tranche first); {@code remaining} is the
 * tranche's outstanding amount ({@code amountGross − paidGross}), which is always strictly positive
 * for an open tranche (Requirements 5.1, 5.2).
 *
 * @param seqNo     the tranche's 1-based position in the schedule (its ordering key) (Requirement
 *                  5.4)
 * @param remaining the outstanding amount still owed on the tranche
 *                  ({@code amountGross − paidGross}), strictly positive (Requirement 5.2)
 */
public record OpenTranche(int seqNo, BigDecimal remaining) {
}
