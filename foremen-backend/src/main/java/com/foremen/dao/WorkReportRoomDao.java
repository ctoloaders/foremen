package com.foremen.dao;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkReportRoomEntity;

import jakarta.persistence.LockModeType;

/**
 * FOR-06-11 / FOR-07-06 (design §PayoutSuggestionService, §PayrollReadModelService) — DAO for the
 * FOR-06-01 {@link WorkReportRoomEntity} report line, used by payroll.
 *
 * <p>FOR-07-06: the FOR-06-11 paid-by FK finder was removed (lines are now paid through
 * {@code worker_payout_allocations} + {@code paid_amount}). The DAO serves the worker-wide payable
 * line candidates (plain and locked) and the batched approved-lines read for the read model. Cost is
 * computed in the service, so the candidate queries return <b>every</b> {@code APPROVED} line with
 * {@code performedFrom <= periodTo}; the service drops lines whose unpaid remainder
 * ({@code cost − paidAmount}) is 0 (R4.2).
 */
@Repository
public interface WorkReportRoomDao extends AdminDao<WorkReportRoomEntity, Long> {

    /**
     * FOR-07-06 (R4.2, R4.3) — the worker's payout candidate lines across <b>all</b> projects: every
     * line of the worker's {@code APPROVED} reports whose {@code performedFrom <= periodTo}
     * ({@code periodFrom} never excludes older unpaid lines), with report, work item, project and
     * room fetch-joined. Ordered in <b>distribution order</b>: report {@code performedFrom},
     * {@code performedTo}, report id, line id.
     *
     * @param workerId the worker id
     * @param periodTo the payout period end (inclusive)
     * @return the candidate lines in distribution order, empty when none
     */
    @Query("SELECT rr FROM WorkReportRoomEntity rr "
            + "JOIN FETCH rr.report r "
            + "JOIN FETCH r.workItem "
            + "JOIN FETCH r.project "
            + "LEFT JOIN FETCH rr.room "
            + "WHERE r.worker.id = :workerId "
            + "AND r.status = com.foremen.dao.model.WorkReportStatus.APPROVED "
            + "AND r.performedFrom <= :periodTo "
            + "ORDER BY r.performedFrom, r.performedTo, r.id, rr.id")
    List<WorkReportRoomEntity> findPayableLines(@Param("workerId") Long workerId,
                                                @Param("periodTo") LocalDate periodTo);

    /**
     * FOR-07-06 (R5.8) — the {@code PESSIMISTIC_WRITE} variant of
     * {@link #findPayableLines(Long, LocalDate)} used inside payout create, so two concurrent creates
     * for the same worker serialize on the candidate lines and cannot over-allocate. Same predicate,
     * fetch graph and distribution order.
     *
     * @param workerId the worker id
     * @param periodTo the payout period end (inclusive)
     * @return the locked candidate lines in distribution order, empty when none
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rr FROM WorkReportRoomEntity rr "
            + "JOIN FETCH rr.report r "
            + "JOIN FETCH r.workItem "
            + "JOIN FETCH r.project "
            + "LEFT JOIN FETCH rr.room "
            + "WHERE r.worker.id = :workerId "
            + "AND r.status = com.foremen.dao.model.WorkReportStatus.APPROVED "
            + "AND r.performedFrom <= :periodTo "
            + "ORDER BY r.performedFrom, r.performedTo, r.id, rr.id")
    List<WorkReportRoomEntity> findPayableLinesForUpdate(@Param("workerId") Long workerId,
                                                         @Param("periodTo") LocalDate periodTo);

    /**
     * FOR-07-06 (R9.3, R10, R11) — the read-model input: every line of the given workers'
     * {@code APPROVED} reports with {@code performedTo <= to} (a line is attributed to the month of
     * its report's {@code performedTo}), with report, worker, work item, project and room
     * fetch-joined — one batched query for all selected workers (no N+1). Ordered by
     * {@code (worker id, performedTo, report id, line id)}.
     *
     * @param workerIds the worker ids; must not be empty
     * @param to        inclusive upper bound on the report's {@code performedTo}
     * @return the matching lines, empty when none
     */
    @Query("SELECT rr FROM WorkReportRoomEntity rr "
            + "JOIN FETCH rr.report r "
            + "JOIN FETCH r.worker w "
            + "JOIN FETCH r.workItem "
            + "JOIN FETCH r.project "
            + "LEFT JOIN FETCH rr.room "
            + "WHERE w.id IN :workerIds "
            + "AND r.status = com.foremen.dao.model.WorkReportStatus.APPROVED "
            + "AND r.performedTo <= :to "
            + "ORDER BY w.id, r.performedTo, r.id, rr.id")
    List<WorkReportRoomEntity> findApprovedLinesForWorkers(@Param("workerIds") Collection<Long> workerIds,
                                                           @Param("to") LocalDate to);
}
