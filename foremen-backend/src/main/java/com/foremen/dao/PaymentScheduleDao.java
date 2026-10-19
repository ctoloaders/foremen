package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.service.payment.ScheduleStatus;

/**
 * DAO for {@link PaymentScheduleEntity} (FOR-06-07, Requirements 1.1, 1.2): the per-project client
 * payment-plan aggregate root, following the {@code ProjectScheduleDao}/{@code OfferDao} convention
 * (the generic {@link AdminDao} CRUD surface plus a project-scoped finder).
 *
 * <p>At most one schedule row exists per project (the {@code project_id} FK is UNIQUE), so
 * {@link #findByProjectId(Long)} returns an {@link Optional}; a missing row means the project has no
 * schedule yet.
 */
@Repository
public interface PaymentScheduleDao extends AdminDao<PaymentScheduleEntity, Long> {

    /**
     * The payment schedule for {@code projectId}, or empty when the project has none yet.
     *
     * @param projectId the owning project id
     * @return the project's payment schedule, if any
     */
    Optional<PaymentScheduleEntity> findByProjectId(Long projectId);

    /**
     * Every schedule currently in {@code status} — used by the periodic overdue sweep
     * ({@code OverdueSweepService}, FOR-06-07 R10.2) to re-derive tranche statuses across only the
     * {@link ScheduleStatus#PUBLISHED} schedules (a {@code DRAFT} schedule is not client-visible and
     * a {@code SETTLED} one has no open tranche left to go overdue).
     *
     * @param status the schedule lifecycle status to match
     * @return the schedules in that status (possibly empty)
     */
    List<PaymentScheduleEntity> findByStatus(ScheduleStatus status);
}
