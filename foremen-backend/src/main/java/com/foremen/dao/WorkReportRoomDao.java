package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkReportRoomEntity;

/**
 * FOR-06-11 (design §ReportLineLinker, R6.3) — DAO for the FOR-06-01 {@link WorkReportRoomEntity}
 * report line, added by payroll to support the paid-by linkage release on payout delete.
 *
 * <p>FOR-06-01 accesses its room lines only through the owning {@code WorkReport} aggregate (there
 * was no standalone room DAO). Payroll adds this one project-scoped-by-ownership finder so
 * {@code ReportLineLinker} can resolve every line a payout linked (its {@code paidByPayout} FK
 * references the payout) and clear the FK within the payout-delete transaction (R6.3). The FK is
 * set/cleared <b>only</b> by payout create/delete and never through the FOR-06-01 reporting surface
 * (R6.5).
 */
@Repository
public interface WorkReportRoomDao extends AdminDao<WorkReportRoomEntity, Long> {

    /**
     * Every report line whose {@link WorkReportRoomEntity#getPaidByPayout() paidByPayout} FK
     * references the payout {@code payoutId} — i.e. the lines a regular payout linked at creation
     * (R6.1). {@code ReportLineLinker} loads these inside the payout-delete transaction and clears
     * the FK, making the lines payable again (R6.3). An advance (which links no line) yields an
     * empty list.
     *
     * @param payoutId the {@code WorkerPayout} id whose linked lines to resolve
     * @return the lines referencing the payout, empty when none
     */
    @Query("SELECT rr FROM WorkReportRoomEntity rr WHERE rr.paidByPayout.id = :payoutId")
    List<WorkReportRoomEntity> findByPaidByPayoutId(@Param("payoutId") Long payoutId);
}
