package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-07-06 (Requirement 1.4, design §Data model) — one allocation of a regular worker-wide
 * {@link WorkerPayoutEntity payout} onto a FOR-06-01 {@link WorkReportRoomEntity report line}: the
 * part of the payout's distributed amount ({@code amount + advanceApplied}) that paid this line.
 * Maps {@code worker_payout_allocations} (changeset {@code 194}).
 *
 * <p>A line may be paid by many payouts (partial payments), a payout pays many lines; the pair is
 * unique ({@code uq_worker_payout_allocations_payout_line}). Both FKs are {@code ON DELETE CASCADE}.
 * The line's {@link WorkReportRoomEntity#getPaidAmount() paid_amount} always equals the sum of its
 * allocation amounts (design property 4). The DB CHECK keeps {@code amount > 0}.
 */
@Entity
@Table(name = "worker_payout_allocations",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_worker_payout_allocations_payout_line",
           columnNames = {"payout_id", "work_report_room_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkerPayoutAllocationEntity extends BaseEntity {

    /** The regular payout whose amount is allocated, NOT NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payout_id", nullable = false)
    private WorkerPayoutEntity payout;

    /** The report line receiving the allocated amount, NOT NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_report_room_id", nullable = false)
    private WorkReportRoomEntity workReportRoom;

    /** The allocated amount, strictly positive, scale 2. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}
