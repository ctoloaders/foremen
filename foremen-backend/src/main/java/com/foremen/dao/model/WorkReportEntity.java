package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-01 (Requirements 1.1, 4.1) — the WorkReport persistence root (design §3.1): the
 * project-scoped anchor a WORKER (or a FOREMAN on their behalf) authors to record the work actually
 * performed on an {@code ACTIVE} project. Its per-room quantity split lives in the owned child
 * collection {@link #rooms} ({@link WorkReportRoomEntity}). Maps the {@code work_reports} table
 * (changeset {@code 163-create-work-reports.xml}).
 *
 * <p>This spec owns the <strong>revenue</strong> side only: {@link #clientUnitPrice} (the frozen
 * {@code EstimateLine.unitPrice} plus signed amendment deltas) and the derived {@link #revenueValue}.
 * The cost side ({@code workerUnitPrice} / {@code costValue} / {@code margin}) is FOR-06-10/11/06 and
 * is NOT persisted here. {@link #totalQuantity} is derived as {@code Σ rooms.quantity} (R1.4) and
 * {@link #revenueValue} as {@code round2(clientUnitPrice × totalQuantity)} (R1.5); neither is
 * hand-entered.
 */
@Entity
@Table(name = "work_reports")
@Getter
@Setter
@NoArgsConstructor
public class WorkReportEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL — drives the project-scope filter ({@code "project.id"}, R10.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** The reported work item (R2.1); must be on the project's estimate (R3.4). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;

    /** Who created the report. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private UserEntity author;

    /** Whose work the report records (equal to {@link #author} for a WORKER self-report). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private UserEntity worker;

    /** Start of the performed-work date interval; {@code performedTo >= performedFrom} at the service. */
    @Column(name = "performed_from", nullable = false)
    private LocalDate performedFrom;

    /** End of the performed-work date interval. */
    @Column(name = "performed_to", nullable = false)
    private LocalDate performedTo;

    /** The {@link WorkReportStatus} ({@code SUBMITTED}/{@code APPROVED}/{@code REJECTED}/{@code CHANGES_REQUESTED}); no server DRAFT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkReportStatus status;

    /** The approver (set on approval / fast path); nullable until decided. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private UserEntity approvedBy;

    /** When it was approved; nullable until decided. {@code TIMESTAMP WITH TIME ZONE} → {@link OffsetDateTime}. */
    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    /** Required (non-empty) on {@code REJECTED} / {@code CHANGES_REQUESTED}, enforced at the service. */
    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    /** Frozen revenue unit price (R6); never hand-entered beyond the derivation. */
    @Column(name = "client_unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal clientUnitPrice = BigDecimal.ZERO;

    /** Derived {@code Σ rooms.quantity} (R1.4); never hand-entered. */
    @Column(name = "total_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    /** Derived {@code round2(clientUnitPrice × totalQuantity)} (R1.5); never hand-entered. */
    @Column(name = "revenue_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal revenueValue = BigDecimal.ZERO;

    /** Free-form author note. */
    @Column(name = "note", columnDefinition = "text")
    private String note;

    /**
     * The owned per-room quantity split (one row per room, R1.2) with {@code cascade = ALL} and
     * {@code orphanRemoval = true}, so the service mutates this collection directly and saving the
     * aggregate persists the rows; deleting the report discards them (R1.6, {@code ON DELETE CASCADE}).
     */
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<WorkReportRoomEntity> rooms = new ArrayList<>();
}
