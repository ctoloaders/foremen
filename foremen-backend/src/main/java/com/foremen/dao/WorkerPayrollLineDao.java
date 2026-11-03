package com.foremen.dao;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkerPayrollLineEntity;

/**
 * FOR-07-06 (design §PayrollLineService, §PayrollReadModelService) — DAO for the additional payroll
 * lines ({@link WorkerPayrollLineEntity}).
 */
@Repository
public interface WorkerPayrollLineDao extends AdminDao<WorkerPayrollLineEntity, Long> {

    /**
     * Additional lines of the given workers whose {@code month} lies in {@code [fromMonth, toMonth]}
     * (both first-of-month dates, inclusive) — one batched query for the read model. Pass a very old
     * {@code fromMonth} to load every line up to {@code toMonth} for the balance chain. The worker is
     * fetch-joined. Ordered by {@code (worker id, month, id)}.
     *
     * @param workerIds the worker ids; must not be empty
     * @param fromMonth inclusive lower bound (first day of a month)
     * @param toMonth   inclusive upper bound (first day of a month)
     * @return the matching lines, empty when none
     */
    @Query("SELECT l FROM WorkerPayrollLineEntity l "
            + "JOIN FETCH l.worker w "
            + "WHERE w.id IN :workerIds "
            + "AND l.month >= :fromMonth AND l.month <= :toMonth "
            + "ORDER BY w.id, l.month, l.id")
    List<WorkerPayrollLineEntity> findByWorkerIdsAndMonthBetween(@Param("workerIds") Collection<Long> workerIds,
                                                                 @Param("fromMonth") LocalDate fromMonth,
                                                                 @Param("toMonth") LocalDate toMonth);

    /**
     * The additional lines of one worker in one month (first-of-month date) — the input of the
     * previous-month hints and the current-month name set. Ordered by {@code id}.
     *
     * @param workerId the worker id
     * @param month    the month, as its first day
     * @return the worker's lines in that month, empty when none
     */
    @Query("SELECT l FROM WorkerPayrollLineEntity l "
            + "WHERE l.worker.id = :workerId AND l.month = :month "
            + "ORDER BY l.id")
    List<WorkerPayrollLineEntity> findByWorkerAndMonth(@Param("workerId") Long workerId,
                                                       @Param("month") LocalDate month);
}
