package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.service.payment.PaymentChannel;

/**
 * DAO for {@link PaymentTransactionEntity} (FOR-06-07, Requirements 5.8, 8.4): a recorded incoming
 * payment. Follows the {@code AdminDao} CRUD convention plus finders backing reconciliation
 * idempotency and the per-schedule transaction list.
 *
 * <p>Reconciliation idempotency is keyed on {@code (schedule, channel, external_ref)} — a confirmed
 * transaction whose {@code externalRef} is already recorded for the schedule is a no-op on
 * re-delivery (R5.8); {@link #findByScheduleIdAndChannelAndExternalRef} is the service-layer guard
 * backing the DB partial unique index.
 */
@Repository
public interface PaymentTransactionDao extends AdminDao<PaymentTransactionEntity, Long> {

    /**
     * The transaction already recorded for this {@code (schedule, channel, externalRef)} triple, if
     * any — the reconciliation idempotency lookup (R5.8).
     *
     * @param scheduleId  the owning schedule id
     * @param channel     the payment channel
     * @param externalRef the bank / przelewy24 reference
     * @return the existing transaction, if one is already recorded
     */
    Optional<PaymentTransactionEntity> findByScheduleIdAndChannelAndExternalRef(
            Long scheduleId, PaymentChannel channel, String externalRef);

    /**
     * Every transaction recorded against a schedule, oldest first.
     *
     * @param scheduleId the owning schedule id
     * @return the schedule's transactions
     */
    List<PaymentTransactionEntity> findByScheduleIdOrderByIdAsc(Long scheduleId);

    /**
     * Unmatched transactions (no schedule link) — ingested bank lines that could not be correlated
     * and are surfaced for manual correlation (R8.4).
     *
     * @return the unmatched transactions
     */
    List<PaymentTransactionEntity> findByScheduleIsNull();
}
