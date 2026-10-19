package com.foremen.dao.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.foremen.service.payment.ScheduleSource;
import com.foremen.service.payment.ScheduleStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-07 (Requirements 1.1, 1.2, 1.6, 5.5): the {@code PaymentSchedule} aggregate root — the
 * per-project client payment plan. Maps the {@code payment_schedules} table (changeset
 * {@code 171-create-payment-schedules.xml}).
 *
 * <p>At most one schedule per project — the {@link #project} FK is NOT NULL and UNIQUE (R1.1); a
 * second create collides on the UNIQUE constraint and is mapped to a 409 at the service layer.
 * {@link #basisGross} is the total being scheduled, seeded from the project's {@code APPROVED} offer
 * total / {@code OFFER_BASE} (R1.2), raised by a manual add-tranche and recomputed by a re-plan;
 * never negative (DB CHECK). {@link #status} is {@code DRAFT}/{@code PUBLISHED}/{@code SETTLED} with
 * {@code SETTLED} <b>derived</b> (iff every tranche is {@code PAID}) and written only by the
 * derivation routine, never hand-set (R1.6). {@link #unallocatedCredit} holds the surplus carried
 * past the last open tranche on an overpayment (R5.5, R1.4).
 *
 * <p>{@link #tranches} is the owned child collection with {@code cascade = ALL} and
 * {@code orphanRemoval = true}, mirroring the DB-level ON DELETE CASCADE on
 * {@code payment_tranches.schedule_id}, so the service mutates the collection and saves the
 * aggregate.
 */
@Entity
@Table(name = "payment_schedules")
@Getter
@Setter
@NoArgsConstructor
public class PaymentScheduleEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL and UNIQUE — one schedule per project; {@code getProjectIdPath() = "project.id"} (R1.1). */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private ProjectEntity project;

    /** Schedule currency seeded from the project (R1.2). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** The total being scheduled, seeded from the APPROVED offer / OFFER_BASE; DB CHECK >= 0 (R1.2). */
    @Column(name = "basis_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal basisGross = BigDecimal.ZERO;

    /** {@code GENERATED}/{@code MANUAL} per the chosen creation path (R1.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ScheduleSource source;

    /** {@code DRAFT}/{@code PUBLISHED}/{@code SETTLED}; {@code SETTLED} derived, defaults to {@code DRAFT} (R1.6). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScheduleStatus status = ScheduleStatus.DRAFT;

    /** Held surplus carried past the last open tranche on an overpayment (R5.5, R1.4). */
    @Column(name = "unallocated_credit", nullable = false, precision = 19, scale = 2)
    private BigDecimal unallocatedCredit = BigDecimal.ZERO;

    /**
     * The owned tranches of this schedule (one installment each, R2.1). Cascades all operations and
     * removes orphans, so the service mutates this collection directly and saves the aggregate,
     * mirroring the DB-level ON DELETE CASCADE on {@code payment_tranches.schedule_id}.
     */
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PaymentTrancheEntity> tranches = new ArrayList<>();
}
