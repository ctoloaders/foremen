package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.payment.TrancheStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
 * ordering by due date re-derived after every structural mutation and drives the
 * nearest-open-tranche reconciliation cascade (R2.1, R5).
 *
 * <p>FOR-11-06 (design 1.3): the common fields (due date, amount, paid total, status) live on the
 * unified base row {@link #payment} ({@code client_payments}); the getters / setters of
 * {@code dueDate}, {@code amountGross}, {@code paidGross} and {@code status} delegate to it. The
 * status is the materialized cache of the derived {@code TrancheStatus} written <b>only</b> by
 * {@code TrancheStatusDeriver} (R10.1, R10.3). {@link #overdueNotifiedAt} is the first-crossing
 * marker so the overdue notification fires exactly once per tranche (R9.3, R10.2).
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

    /** Contiguous {@code 1..N} ordering by due date; drives the nearest-open cascade (R2.1, R3.2). */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** Unified base row (FOR-11-06): holds due date, amounts and status. */
    @OneToOne(optional = false, fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "client_payment_id", nullable = false)
    private ClientPaymentEntity payment = newPayment();

    /** First-crossing marker so the overdue notification fires exactly once (R9.3, R10.2); null until first OVERDUE. */
    @Column(name = "overdue_notified_at")
    private OffsetDateTime overdueNotifiedAt;

    private ClientPaymentEntity newPayment() {
        ClientPaymentEntity p = new ClientPaymentEntity(ClientPaymentKind.WORKS, TrancheStatus.PENDING.name());
        p.setTranche(this);
        return p;
    }

    /** Also keeps the base row's project and currency in step with the schedule. */
    public void setSchedule(PaymentScheduleEntity schedule) {
        this.schedule = schedule;
        if (schedule != null && payment != null) {
            payment.setProject(schedule.getProject());
            if (schedule.getCurrency() != null) {
                payment.setCurrency(schedule.getCurrency());
            }
        }
    }

    /** Drives overdue detection + status derivation (R10.1). */
    public LocalDate getDueDate() {
        return payment.getDueDate();
    }

    public void setDueDate(LocalDate dueDate) {
        payment.setDueDate(dueDate);
    }

    /** The due amount; strictly positive (R3.6). */
    public BigDecimal getAmountGross() {
        return payment.getAmountGross();
    }

    public void setAmountGross(BigDecimal amountGross) {
        payment.setAmountGross(amountGross);
    }

    /** Running settled total accumulated across allocations (R5.2); non-negative. */
    public BigDecimal getPaidGross() {
        return payment.getPaidGross();
    }

    public void setPaidGross(BigDecimal paidGross) {
        payment.setPaidGross(paidGross);
    }

    /** Materialized cache of the derived {@code TrancheStatus}; written only by the deriver (R10.1, R10.3). */
    public TrancheStatus getStatus() {
        return TrancheStatus.valueOf(payment.getStatus());
    }

    public void setStatus(TrancheStatus status) {
        payment.setStatus(status.name());
    }
}
