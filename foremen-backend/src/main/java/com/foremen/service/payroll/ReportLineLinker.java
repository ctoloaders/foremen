package com.foremen.service.payroll;

import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;

/**
 * FOR-06-11 (Requirements 6.1, 6.3, 6.4, 6.5, design §ReportLineLinker) — the sole mutator of the
 * {@link WorkReportRoomEntity#getPaidByPayout() work_report_rooms.paid_by_payout_id} paid-by FK.
 *
 * <p>Two operations, each invoked <b>only</b> from within a {@code WorkerPayoutService} payout
 * create / delete transaction (never reachable from the FOR-06-01 work-reporting surface, R6.5):
 * <ul>
 *   <li>{@link #link(WorkerPayoutEntity, List)} — on a <b>regular</b> payout create, set the FK on
 *       every covered unpaid {@code APPROVED} report line to the new payout, so those lines are
 *       <b>paid</b> and can never be paid again (R6.1). An unavailable-cost line is never passed in
 *       by the caller, so it is never linked (R3.4);</li>
 *   <li>{@link #release(Long)} — on a {@code PLANNED} payout delete, clear the FK on every line
 *       referencing that payout, making them <b>payable</b> again (R6.3).</li>
 * </ul>
 *
 * <p>It <b>never runs for an advance</b> payout (an advance links no line and sets no FK, R6.4) — the
 * caller simply does not invoke {@link #link} for an advance, and {@link #release} of an advance
 * finds no referencing line (an empty release is a no-op). This collaborator adds no cost arithmetic
 * and no credit logic — it only flips the FK; the lifecycle, credit, and transaction boundary are
 * owned by {@code WorkerPayoutService}.
 */
@Component
public final class ReportLineLinker {

    private final WorkReportRoomDao workReportRoomDao;

    public ReportLineLinker(WorkReportRoomDao workReportRoomDao) {
        this.workReportRoomDao = workReportRoomDao;
    }

    /**
     * Link every covered unpaid {@code APPROVED} report line to {@code payout} by setting its
     * {@link WorkReportRoomEntity#setPaidByPayout(WorkerPayoutEntity) paidByPayout} FK (R6.1). Must be
     * called only for a <b>regular</b> (non-advance) payout, from within the create transaction; the
     * caller passes exactly the available, in-period, currently-unpaid lines that make up the gross
     * (an unavailable-cost line is excluded upstream and never linked, R3.4). Idempotent per line:
     * a line already pointing at this payout is left as is.
     *
     * @param payout the persisted regular payout the lines are paid by (must be non-null)
     * @param lines  the covered unpaid report lines to link; {@code null} / empty ⇒ no-op. {@code
     *               null} elements are skipped.
     */
    public void link(WorkerPayoutEntity payout, List<WorkReportRoomEntity> lines) {
        if (payout == null || lines == null || lines.isEmpty()) {
            return;
        }
        for (WorkReportRoomEntity line : lines) {
            if (line == null) {
                continue;
            }
            line.setPaidByPayout(payout);
            workReportRoomDao.save(line);
        }
    }

    /**
     * Clear the paid-by FK on every report line referencing the payout {@code payoutId}, making those
     * lines payable again (R6.3). Called from within the payout-delete transaction. For an advance
     * payout (which linked no line) this resolves an empty set and is a no-op (R6.4).
     *
     * @param payoutId the id of the {@code PLANNED} payout being deleted; {@code null} ⇒ no-op
     */
    public void release(Long payoutId) {
        if (payoutId == null) {
            return;
        }
        List<WorkReportRoomEntity> linked = workReportRoomDao.findByPaidByPayoutId(payoutId);
        for (WorkReportRoomEntity line : linked) {
            if (line == null) {
                continue;
            }
            line.setPaidByPayout(null);
            workReportRoomDao.save(line);
        }
    }
}
