package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.service.payment.TrancheStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-07 (Requirements 2.1, 3.2, 3.6, 5.2, 9.3, 10.1, 10.3): a single installment of a
 * {@link PaymentScheduleEntity}. Maps the {@code payment_tranches} table (changeset
 * {@code 172-create-payment-tranches.xml}).
 *
 * <p>One row per {@code (schedule, seqNo)} — the UNIQUE {@code (schedule_id, seq_no)} constraint
 * bars a duplicate ordinal within a schedule (R3.2). {@link #seqNo} is the contiguous {@code 1..N}
 * ordering by {@link #dueDate} re-derived after every structural mutation and drives the
 * nearest-open-tranche reconciliation cascade (R2.1, R5). {@link #amountGross} is strictly positive
 * (DB CHECK, R3.6); {@link #paidGross} is the running settled total accumulated across allocations,
 * non-negative (R5.2). {@link #status} is the materialized cache of the derived {@code TrancheStatus}
 * written <b>only</b> by {@code TrancheStatusDeriver}, never hand-set (R10.1, R10.3).
 * {@link #overdueNotifiedAt} is the first-crossing marker so the overdue notification fires exactly
 * once per tranche (R9.3, R10.2).
 */
@Entity
@Table(name = "payment_tranches",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_payment_tranches_schedule_seq",
                columnNames = {"schedule_id", "seq_no"}))
@Getter
@Setter
@NoArgsConstructor
public class PaymentTrancheEntity extends BaseEntity {

    /** Owning schedule — the aggregate root; NOT NULL, ON DELETE CASCADE at the DB level. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private PaymentScheduleEntity schedule;

    /** Contiguous {@code 1..N} ordering by {@link #dueDate}; drives the nearest-open cascade (R2.1, R3.2). */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** Drives overdue detection + status derivation (R10.1). */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** The due amount; strictly positive (DB CHECK, R3.6). */
    @Column(name = "amount_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountGross;

    /** Running settled total accumulated across allocations (R5.2); non-negative (DB CHECK). */
    @Column(name = "paid_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidGross = BigDecimal.ZERO;

    /** Materialized cache of the derived {@code TrancheStatus}; written only by the deriver (R10.1, R10.3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TrancheStatus status = TrancheStatus.PENDING;

    /** First-crossing marker so the overdue notification fires exactly once (R9.3, R10.2); null until first OVERDUE. */
    @Column(name = "overdue_notified_at")
    private OffsetDateTime overdueNotifiedAt;
}
