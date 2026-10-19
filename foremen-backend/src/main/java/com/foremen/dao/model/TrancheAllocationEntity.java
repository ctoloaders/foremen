package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-07 (Requirements 5.4, 5.7, 6.2): the settlement link recording how much of a
 * {@link PaymentTransactionEntity} was applied to a given {@link PaymentTrancheEntity}. Maps the
 * {@code tranche_allocations} table (changeset {@code 174-create-tranche-allocations.xml}).
 *
 * <p>A transaction may own several allocations (an overpayment spanning tranches, R5.4) and a
 * tranche may accumulate allocations across transactions (an underpayment); this many-to-one join is
 * the fully auditable record of each tranche's settlement (R5.7). Allocations are <b>append-only</b>
 * and immutable once their tranche is {@code PAID} (R6.2) — the service never mutates or deletes a
 * row; lifecycle is governed by the schedule's cascade. {@link #transaction} is ON DELETE CASCADE;
 * {@link #tranche} is RESTRICT (a tranche with recorded allocations cannot be hard-deleted).
 * {@link #amount} is strictly positive (DB CHECK, R5.4).
 */
@Entity
@Table(name = "tranche_allocations")
@Getter
@Setter
@NoArgsConstructor
public class TrancheAllocationEntity extends BaseEntity {

    /** The settling transaction — the owner; NOT NULL, ON DELETE CASCADE at the DB level (R5.7). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private PaymentTransactionEntity transaction;

    /** The settled tranche; NOT NULL, RESTRICT at the DB level (R5.7, R6.2 immutability). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tranche_id", nullable = false)
    private PaymentTrancheEntity tranche;

    /** How much of the transaction applied to this tranche; strictly positive (DB CHECK, R5.4). */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}
