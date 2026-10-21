package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkerPayoutEntity;

/**
 * FOR-06-11 (design §Components) — DAO for {@link WorkerPayoutEntity}. The generic {@link AdminDao}
 * CRUD + {@code JpaSpecificationExecutor} surface backs the core {@code WorkerPayoutService}, which
 * is a {@code ProjectScopedService}: its LIST reads flow through the inherited specification
 * ({@code addRequiredQuery()} project filter on {@code "project.id"}), and its by-id reads/mutations
 * go through the inherited {@code findById} / {@code save} / {@code delete} with the per-id
 * project-access guard.
 *
 * <p>Adds the two project-scoped finders the payroll read model and the advance-credit running
 * balance need: all of a project's payouts (with worker fetched) and all of a worker's payouts on a
 * project (fed verbatim to {@code AdvanceCreditCalculator.remaining(...)}).
 */
@Repository
public interface WorkerPayoutDao extends AdminDao<WorkerPayoutEntity, Long> {

    /**
     * All payouts of a project with the {@link WorkerPayoutEntity#getWorker() worker} fetch-joined,
     * so the payroll read model ({@code PayrollView}) resolves each row's worker display name without
     * an N+1 over the LAZY association (R11.1). Ordered by {@code (worker, periodFrom, id)} for a
     * stable list.
     *
     * @param projectId the owning project id
     * @return the project's payouts with worker fetched, empty when none
     */
    @Query("SELECT p FROM WorkerPayoutEntity p "
            + "JOIN FETCH p.worker "
            + "WHERE p.project.id = :projectId "
            + "ORDER BY p.worker.id, p.periodFrom, p.id")
    List<WorkerPayoutEntity> findByProjectWithWorker(@Param("projectId") Long projectId);

    /**
     * All payouts of a single worker on a project (any status), fed verbatim to
     * {@code AdvanceCreditCalculator.remaining(...)} to fold the worker's remaining advance credit
     * running balance (R5.6, R5.8). Ordered by {@code (periodFrom, id)}.
     *
     * @param projectId the owning project id
     * @param workerId  the worker whose payouts are returned
     * @return the worker's payouts on the project, empty when none
     */
    @Query("SELECT p FROM WorkerPayoutEntity p "
            + "WHERE p.project.id = :projectId AND p.worker.id = :workerId "
            + "ORDER BY p.periodFrom, p.id")
    List<WorkerPayoutEntity> findByProjectAndWorker(@Param("projectId") Long projectId,
                                                    @Param("workerId") Long workerId);
}
