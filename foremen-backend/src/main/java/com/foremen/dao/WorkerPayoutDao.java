package com.foremen.dao;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkerPayoutEntity;

/**
 * FOR-06-11 / FOR-07-06 (design §WorkerPayoutService, §PayrollReadModelService) — DAO for
 * {@link WorkerPayoutEntity}. The generic {@link AdminDao} CRUD + {@code JpaSpecificationExecutor}
 * surface backs the company-wide {@code WorkerPayoutService}.
 *
 * <p>FOR-07-06: payouts are worker-wide (no project), so the FOR-06-11 project finders were replaced
 * by worker finders: all payouts of one worker (fed verbatim to
 * {@code AdvanceCreditCalculator.remaining(...)} for the worker-wide advance credit) and a batched
 * finder for the read model (payouts of many workers with {@code payout_date <= to}).
 */
@Repository
public interface WorkerPayoutDao extends AdminDao<WorkerPayoutEntity, Long> {

    /**
     * All payouts of one worker (any status, any project), fed verbatim to
     * {@code AdvanceCreditCalculator.remaining(...)} to fold the worker-wide remaining advance credit
     * (R6.3, R7). Ordered by {@code (periodFrom, id)}.
     *
     * @param workerId the worker whose payouts are returned
     * @return the worker's payouts, empty when none
     */
    @Query("SELECT p FROM WorkerPayoutEntity p "
            + "WHERE p.worker.id = :workerId "
            + "ORDER BY p.periodFrom, p.id")
    List<WorkerPayoutEntity> findByWorker(@Param("workerId") Long workerId);

    /**
     * The payouts of the given workers whose {@code payoutDate <= to} (any status), with the worker
     * fetch-joined — one batched query for the company table / worker card balance chain
     * (R9.3, R10, R11). Ordered by {@code (worker id, payoutDate, id)}.
     *
     * @param workerIds the worker ids; must not be empty
     * @param to        inclusive upper bound on {@code payoutDate}
     * @return the matching payouts, empty when none
     */
    @Query("SELECT p FROM WorkerPayoutEntity p "
            + "JOIN FETCH p.worker w "
            + "WHERE w.id IN :workerIds AND p.payoutDate <= :to "
            + "ORDER BY w.id, p.payoutDate, p.id")
    List<WorkerPayoutEntity> findByWorkersAndPayoutDateLte(@Param("workerIds") Collection<Long> workerIds,
                                                           @Param("to") LocalDate to);

    /**
     * FOR-07-04 (Requirement 14.5, design "PayoutCandidates") — payouts of a payment channel in the given
     * statuses with {@code payoutDate} in {@code [from, to]}, worker fetch-joined, by id. Feeds the bank
     * detection matcher ({@code BANK_TRANSFER}, {@code PLANNED} / {@code PAID}).
     */
    @Query("SELECT p FROM WorkerPayoutEntity p "
            + "JOIN FETCH p.worker w "
            + "WHERE p.paymentChannel = :channel AND p.status IN :statuses "
            + "AND p.payoutDate BETWEEN :from AND :to "
            + "ORDER BY p.id")
    List<WorkerPayoutEntity> findLedgerCandidates(@Param("channel") com.foremen.dao.model.PaymentChannel channel,
                                                  @Param("statuses") Collection<com.foremen.dao.model.PayoutStatus> statuses,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);
}
