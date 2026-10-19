package com.foremen.service.payment;

import java.math.BigDecimal;
import java.util.List;

/**
 * The pure result of {@link ReconciliationEngine#plan}: the per-tranche {@link Allocation}s the
 * incoming amount cascades into, plus any final surplus held back as {@code leftoverCredit}.
 *
 * <p>Pure, persistence-free value. Conservation always holds — {@code Σ allocations.applied +
 * leftoverCredit == amount} — and no allocation exceeds its tranche's remaining amount, so the plan
 * never over-settles a tranche (Requirements 5.3, 5.4, 5.5). The {@code leftoverCredit} is the
 * surplus that remains after the last open tranche is settled; the applying layer adds it to the
 * schedule's {@code unallocatedCredit} rather than fabricating a phantom tranche (Requirement 5.5).
 *
 * @param allocations    the planned per-tranche settlements, in cascade order by {@code seqNo};
 *                       empty when {@code amount} is zero or there are no open tranches
 * @param leftoverCredit the non-negative surplus left after the last open tranche is settled
 *                       (Requirement 5.5)
 */
public record AllocationPlan(List<Allocation> allocations, BigDecimal leftoverCredit) {
}
