package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutAllocationEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.payroll.PayoutDistributor.Allocation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-07-06 (Requirements 5.3, 5.9, 6.2; design §WorkerPayoutService) — the sole mutator of
 * {@code worker_payout_allocations} and {@code work_report_rooms.paid_amount}. Replaces the FOR-06-11
 * {@code ReportLineLinker}.
 *
 * <p>Both operations run inside a {@link WorkerPayoutService} create / delete transaction and keep
 * the invariant {@code paid_amount = Σ allocation amounts} per line (R5.9):
 * <ul>
 *   <li>{@link #write} — for every allocation of a regular payout insert one allocation row and add
 *       its amount to the (already {@code PESSIMISTIC_WRITE}-locked) line's {@code paidAmount};</li>
 *   <li>{@link #release} — for every allocation row of the payout lock + refresh the line, subtract
 *       the amount from its {@code paidAmount} (floored at 0 as a last line of defence), and delete
 *       the rows (R6.2).</li>
 * </ul>
 * No cost arithmetic, no credit logic.
 */
@Component
public class AllocationWriter {

    private static final int SCALE = 2;

    private final WorkerPayoutAllocationDao allocationDao;
    private final EntityManager entityManager;

    public AllocationWriter(WorkerPayoutAllocationDao allocationDao, EntityManager entityManager) {
        this.allocationDao = allocationDao;
        this.entityManager = entityManager;
    }

    /**
     * Persist the allocations of {@code payout} and raise each line's {@code paidAmount} by the
     * allocated amount. Allocations with a non-positive amount or an unknown line are skipped.
     *
     * @param payout      the persisted regular payout
     * @param allocations the {@link PayoutDistributor} output (distribution order)
     * @param linesById   the locked candidate lines keyed by id
     * @return the persisted allocation rows (empty when nothing was allocated)
     */
    public List<WorkerPayoutAllocationEntity> write(WorkerPayoutEntity payout,
                                                    List<Allocation> allocations,
                                                    Map<Long, WorkReportRoomEntity> linesById) {
        if (payout == null || allocations == null || allocations.isEmpty() || linesById == null) {
            return List.of();
        }
        List<WorkerPayoutAllocationEntity> rows = new ArrayList<>(allocations.size());
        for (Allocation allocation : allocations) {
            if (allocation == null || allocation.amount() == null || allocation.amount().signum() <= 0) {
                continue;
            }
            WorkReportRoomEntity line = linesById.get(allocation.lineId());
            if (line == null) {
                continue;
            }
            BigDecimal amount = allocation.amount().setScale(SCALE, RoundingMode.HALF_UP);
            line.setPaidAmount(nz(line.getPaidAmount()).add(amount).setScale(SCALE, RoundingMode.HALF_UP));

            WorkerPayoutAllocationEntity row = new WorkerPayoutAllocationEntity();
            row.setPayout(payout);
            row.setWorkReportRoom(line);
            row.setAmount(amount);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<WorkerPayoutAllocationEntity> saved = new ArrayList<>(rows.size());
        for (WorkerPayoutAllocationEntity row : rows) {
            saved.add(allocationDao.save(row));
        }
        return saved;
    }

    /**
     * Reverse every allocation of the payout {@code payoutId}: lower each affected line's
     * {@code paidAmount} by the allocation amount and delete the allocation rows (R6.2). An advance
     * (no allocations) is a no-op.
     *
     * @param payoutId the {@code PLANNED} payout being deleted; {@code null} ⇒ no-op
     * @return the released allocation rows
     */
    public List<WorkerPayoutAllocationEntity> release(Long payoutId) {
        if (payoutId == null) {
            return List.of();
        }
        List<WorkerPayoutAllocationEntity> rows = allocationDao.findByPayoutId(payoutId);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        for (WorkerPayoutAllocationEntity row : rows) {
            WorkReportRoomEntity line = row.getWorkReportRoom();
            if (line == null) {
                continue;
            }
            // Lock + re-read the line so a concurrent create cannot lose this decrement.
            entityManager.refresh(line, LockModeType.PESSIMISTIC_WRITE);
            BigDecimal next = nz(line.getPaidAmount()).subtract(nz(row.getAmount()));
            line.setPaidAmount(next.max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP));
        }
        allocationDao.deleteAll(rows);
        return rows;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
