package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.service.payroll.AdvanceCreditSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-11 (Requirements 1.1, 1.4) — the WorkerPayout persistence root (design §Data Models): the
 * project-scoped record a FINANCIER / MANAGER / ADMIN issues to pay a worker for the work performed
 * (or, for an advance, to be performed) on an {@code ACTIVE} / {@code COMPLETED} project. It is the
 * money-OUT counterpart of the FOR-06-07 client payment. Maps the {@code worker_payouts} table
 * (changeset {@code 180-create-worker-payouts.xml}).
 *
 * <p>The cost is <strong>never</strong> computed here. A regular payout's gross value is
 * {@code Σ Cost_Value} over the worker's unpaid {@code APPROVED} report lines in the period, read
 * through the FOR-06-10 {@code WorkerCostService} verbatim; this entity only persists the resulting
 * {@link #amount} / {@link #suggestedAmount} / {@link #advanceApplied} snapshots (no second cost
 * formula). Which report lines a regular payout paid is recorded by the nullable
 * {@link WorkReportRoomEntity#getPaidByPayout() work_report_rooms.paid_by_payout_id} FK
 * (Requirement 6), not a copy table.
 *
 * <p>Project-scoped on {@code project.id} ({@code getProjectIdPath() = "project.id"}, R10.2). The
 * entity carries <strong>no</strong> {@code nameRU}/{@code namePL} i18n columns — a payout is
 * transactional, not a dictionary; the worker / work-item / room / tier names are read from the
 * referenced entities with the {@code ru → pl → code} fallback (Requirement 8.1).
 *
 * <p>Implements {@link AdvanceCreditSource} so the pure {@code AdvanceCreditCalculator} can fold the
 * advance-credit running balance from a {@code List<WorkerPayoutEntity>} verbatim — Lombok's
 * {@code @Getter} generates {@link #isAdvance()}, {@link #getAmount()}, and
 * {@link #getAdvanceApplied()} matching the interface (Requirements 5.5, 5.6).
 */
@Entity
@Table(name = "worker_payouts")
@Getter
@Setter
@NoArgsConstructor
public class WorkerPayoutEntity extends BaseEntity implements AdvanceCreditSource {

    /** Owner FK to the project, NOT NULL — drives the project-scope filter ({@code "project.id"}, R10.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** The worker being paid, NOT NULL (R1.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private UserEntity worker;

    /** Start of the payout period; {@code periodTo >= periodFrom} enforced at the service + DB (R1.1, R4.5). */
    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    /** End of the payout period. */
    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    /** The cash amount paid; defaults to {@link #suggestedAmount}, overridable by the issuer (R1.4, R4.4). */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** The computed suggested value snapshot captured at creation; never changes afterwards (R1.4). */
    @Column(name = "suggested_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal suggestedAmount;

    /** The advance credit offset applied on a regular payout; 0 for an advance (R5.5, R5.6). */
    @Column(name = "advance_applied", nullable = false, precision = 19, scale = 2)
    private BigDecimal advanceApplied = BigDecimal.ZERO;

    /** The {@link PaymentChannel} ({@code BANK_TRANSFER} / {@code CASH}) the worker was paid through (R2.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel", nullable = false)
    private PaymentChannel paymentChannel;

    /** The {@link PayoutStatus} ({@code PLANNED} / {@code PAID}); {@code PAID} is terminal (R7). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PayoutStatus status;

    /**
     * Cached classification: {@code true} WHEN {@link #periodTo} is after the payout's creation date
     * (R5.3) — written only by {@code AdvanceDeriver}, never a user-set flag. An advance increases the
     * worker's remaining credit and links no report line.
     */
    @Column(name = "is_advance", nullable = false)
    private boolean isAdvance;

    /** When it was paid; nullable until mark-paid (R7.1). {@code TIMESTAMP WITH TIME ZONE} → {@link OffsetDateTime}. */
    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    /** Free-form issuer note (R1.1, R4.1). */
    @Column(name = "note", columnDefinition = "text")
    private String note;
}
