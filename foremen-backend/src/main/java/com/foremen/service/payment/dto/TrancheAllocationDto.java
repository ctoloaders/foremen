package com.foremen.service.payment.dto;

import java.math.BigDecimal;

/**
 * FOR-06-07 (Requirements 5.4, 5.7): the read model of one {@code TrancheAllocation} — how much of a
 * {@link PaymentTransactionDto} was applied to a given tranche. Served inside
 * {@link PaymentTransactionDto#allocations}.
 *
 * <p>Allocations are the fully auditable, append-only record of each tranche's settlement (R5.7):
 * a transaction may own several allocations on an overpayment carry-over, and a tranche may
 * accumulate allocations across transactions on an underpayment.
 *
 * @param id          the allocation id
 * @param trancheId   the settled tranche id (R5.7)
 * @param trancheSeqNo the settled tranche's contiguous ordinal, for display ordering
 * @param amount      how much of the transaction applied to this tranche, strictly positive (R5.4)
 */
public record TrancheAllocationDto(
        Long id,
        Long trancheId,
        Integer trancheSeqNo,
        BigDecimal amount) {
}
