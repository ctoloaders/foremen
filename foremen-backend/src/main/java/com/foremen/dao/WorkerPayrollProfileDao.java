package com.foremen.dao;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkerPayrollProfileEntity;

/**
 * FOR-07-06 (design §PayrollProfileService) — DAO for {@link WorkerPayrollProfileEntity} (the
 * worker's opening balance). At most one row per worker.
 */
@Repository
public interface WorkerPayrollProfileDao extends AdminDao<WorkerPayrollProfileEntity, Long> {

    /**
     * The profile of one worker, if any (absent ⇒ opening balance 0).
     *
     * @param workerId the worker id
     * @return the profile, or empty
     */
    @Query("SELECT p FROM WorkerPayrollProfileEntity p WHERE p.worker.id = :workerId")
    Optional<WorkerPayrollProfileEntity> findByWorkerId(@Param("workerId") Long workerId);

    /**
     * The profiles of the given workers in one batched query (workers without a row are absent).
     * The worker is fetch-joined.
     *
     * @param workerIds the worker ids; must not be empty
     * @return the existing profiles
     */
    @Query("SELECT p FROM WorkerPayrollProfileEntity p JOIN FETCH p.worker w WHERE w.id IN :workerIds")
    List<WorkerPayrollProfileEntity> findByWorkerIds(@Param("workerIds") Collection<Long> workerIds);
}
