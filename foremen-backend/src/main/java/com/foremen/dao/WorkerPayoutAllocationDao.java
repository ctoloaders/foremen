package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkerPayoutAllocationEntity;

/**
 * FOR-07-06 (design §WorkerPayoutService, §Controllers) — DAO for
 * {@link WorkerPayoutAllocationEntity}: the rows recording which report lines a regular payout paid
 * and by how much.
 */
@Repository
public interface WorkerPayoutAllocationDao extends AdminDao<WorkerPayoutAllocationEntity, Long> {

    /**
     * Every allocation of one payout, with the report line, its report, the report's work item and
     * project, and the line's room fetch-joined — feeds both the allocation release on payout delete
     * ({@code paid_amount -= amount}) and the {@code GET /api/worker-payouts/{id}/allocations}
     * listing without an N+1. Ordered in distribution order (report performedFrom, performedTo,
     * report id, line id).
     *
     * @param payoutId the payout id
     * @return the payout's allocations, empty for an advance or an unknown id
     */
    @Query("SELECT a FROM WorkerPayoutAllocationEntity a "
            + "JOIN FETCH a.workReportRoom rr "
            + "JOIN FETCH rr.report r "
            + "JOIN FETCH r.workItem "
            + "JOIN FETCH r.project "
            + "LEFT JOIN FETCH rr.room "
            + "WHERE a.payout.id = :payoutId "
            + "ORDER BY r.performedFrom, r.performedTo, r.id, rr.id")
    List<WorkerPayoutAllocationEntity> findByPayoutId(@Param("payoutId") Long payoutId);

    /**
     * Every allocation on the given report lines, with the payout fetch-joined (any status), e.g. to
     * show which payouts paid a line. Ordered by {@code (line id, payout id)}.
     *
     * @param lineIds the {@code work_report_rooms} ids; must not be empty
     * @return the allocations on those lines, empty when none
     */
    @Query("SELECT a FROM WorkerPayoutAllocationEntity a "
            + "JOIN FETCH a.payout "
            + "WHERE a.workReportRoom.id IN :lineIds "
            + "ORDER BY a.workReportRoom.id, a.payout.id")
    List<WorkerPayoutAllocationEntity> findByLineIds(@Param("lineIds") Collection<Long> lineIds);

    /**
     * FOR-07-06 (design §PayrollReadModelService, R10.3) — every allocation of the given payouts in
     * one query, with the payout and the line's report + project fetch-joined, so the company table
     * can sum the per-project Paid of the period's payouts without an N+1.
     *
     * @param payoutIds the payout ids; must not be empty
     * @return the allocations of those payouts, empty when none
     */
    @Query("SELECT a FROM WorkerPayoutAllocationEntity a "
            + "JOIN FETCH a.payout p "
            + "JOIN FETCH a.workReportRoom rr "
            + "JOIN FETCH rr.report r "
            + "JOIN FETCH r.project "
            + "WHERE p.id IN :payoutIds "
            + "ORDER BY p.id, a.id")
    List<WorkerPayoutAllocationEntity> findByPayoutIds(@Param("payoutIds") Collection<Long> payoutIds);
}
