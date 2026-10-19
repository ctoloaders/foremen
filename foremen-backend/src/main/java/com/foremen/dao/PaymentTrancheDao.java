package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.PaymentTrancheEntity;

/**
 * DAO for {@link PaymentTrancheEntity} (FOR-06-07, Requirements 2.1, 3.2, 5.2): a single installment
 * of a payment schedule. Follows the {@code AdminDao} CRUD convention plus per-schedule finders.
 *
 * <p>A tranche is a collection child of its {@code PaymentScheduleEntity} (owner FK {@code schedule_id},
 * ON DELETE CASCADE) and is normally reached through the parent schedule's {@code tranches}
 * collection; the derived finders back the reconciliation cascade (open tranches ordered by
 * {@code seqNo}) and the overdue sweep.
 */
@Repository
public interface PaymentTrancheDao extends AdminDao<PaymentTrancheEntity, Long> {

    /**
     * The schedule's tranches in sequence order ({@code seqNo} ascending).
     *
     * @param scheduleId the owning schedule id
     * @return the tranches, ordered by {@code seqNo}
     */
    List<PaymentTrancheEntity> findByScheduleIdOrderBySeqNoAsc(Long scheduleId);

    /**
     * A single tranche of a schedule by its sequence ordinal, enforced UNIQUE at the DB level.
     *
     * @param scheduleId the owning schedule id
     * @param seqNo      the sequence ordinal
     * @return the tranche, if present
     */
    Optional<PaymentTrancheEntity> findByScheduleIdAndSeqNo(Long scheduleId, Integer seqNo);
}
