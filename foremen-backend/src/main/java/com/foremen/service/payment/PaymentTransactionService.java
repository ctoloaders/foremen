package com.foremen.service.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payment.dto.ManualTxnInput;
import com.foremen.service.payment.dto.PaymentTransactionDto;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;
import com.foremen.service.payment.provider.IncomingBankTxn;
import com.foremen.service.payment.provider.OnlineWebhookEvent;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-07 (Requirements 5.8, 7.3, 7.4, 8.3, 8.4, 8.5; design §PaymentTransactionService): records
 * and confirms incoming {@code PaymentTransaction}s from the three channels and delegates the
 * actual tranche allocation to {@link ReconciliationService}.
 *
 * <p><b>Idempotency (R5.8).</b> A confirmed transaction is keyed on
 * {@code (schedule, channel, externalRef)}: a transaction whose {@code externalRef} is already
 * recorded for the schedule is a <b>no-op</b> on re-delivery (no second row, no re-allocation),
 * backed by {@link PaymentTransactionDao#findByScheduleIdAndChannelAndExternalRef} and the DB partial
 * unique index. A {@code MANUAL} record carries no {@code externalRef} and so is never
 * idempotency-deduplicated here (operator intent records each time).
 *
 * <p><b>Bank-transfer ingest (R8.3, R8.4).</b> {@link #ingestBankTransaction} correlates the line to
 * a schedule by its {@code correlationKey}; a correlated line is recorded
 * {@code channel = BANK_TRANSFER}, {@code status = CONFIRMED} and reconciled (R8.3), while a line
 * that cannot be correlated to any schedule is recorded <b>unmatched</b> (schedule {@code null},
 * not allocated) and surfaced for manual correlation <b>without failing ingest</b> (R8.4).
 *
 * <p><b>Online webhook (R7.3, R7.4).</b> {@link #onOnlineWebhook} looks the transaction up by
 * {@code externalRef} on the {@code ONLINE} channel and maps the reported outcome: {@code CONFIRMED}
 * &rarr; mark confirmed + reconcile; {@code FAILED} &rarr; {@code FAILED}; a reversal
 * ({@code REFUNDED}) &rarr; {@code REFUNDED}. A failure or reversal publishes an
 * {@link OnlineFailedEvent} so the best-effort {@code PaymentNotificationEmitter} (task 5.6) can emit
 * the {@code onlineFailed} notification (R9.2 row 6) after commit — this service never emits directly.
 * The commission is stored as {@code feeGross} under the {@code Payer_Borne_Fee} model and is
 * <b>never</b> deducted from the amount reconciled against tranches (R7.4).
 *
 * <p><b>Allocation is delegated.</b> This service only records / transitions the transaction and
 * then calls {@link ReconciliationService#reconcile(PaymentTransactionEntity)} for a confirmed
 * transaction (design §ReconciliationService); the cascade arithmetic, {@code TrancheAllocation}
 * writes, {@code paidGross} bumps, status re-derivation, unallocated-credit roll-up and the
 * {@code SETTLED} roll-up all live there.
 *
 * <p><b>Localized error codes</b> referenced here (added to the message bundle by task 10.1):
 * {@code error.payment.schedule.noProjectTotal} is <em>not</em> raised here; the only reject is
 * {@code error.entity.not.found} (404) when a {@code recordManual}/webhook targets a missing
 * schedule / unknown {@code externalRef}.
 */
@Service
@Transactional
public class PaymentTransactionService {

    /** 404 for a missing schedule (recordManual) or an unknown online {@code externalRef} (webhook). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private static final int SCALE = 2;

    private final PaymentTransactionDao paymentTransactionDao;
    private final PaymentScheduleDao paymentScheduleDao;
    private final UserDao userDao;
    private final ReconciliationService reconciliationService;
    private final PaymentDtoMapper paymentDtoMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public PaymentTransactionService(PaymentTransactionDao paymentTransactionDao,
                                     PaymentScheduleDao paymentScheduleDao,
                                     UserDao userDao,
                                     ReconciliationService reconciliationService,
                                     PaymentDtoMapper paymentDtoMapper,
                                     ApplicationEventPublisher eventPublisher,
                                     EntityManager entityManager) {
        this.paymentTransactionDao = paymentTransactionDao;
        this.paymentScheduleDao = paymentScheduleDao;
        this.userDao = userDao;
        this.reconciliationService = reconciliationService;
        this.paymentDtoMapper = paymentDtoMapper;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    // --- Bank-transfer ingest (R8.3, R8.4, R5.8) ---

    /**
     * Ingests one incoming bank-feed line (R8.3, R8.4). The line is correlated to a schedule by its
     * {@link IncomingBankTxn#correlationKey()}:
     *
     * <ul>
     *   <li><b>Correlated</b> &rarr; record a {@code channel = BANK_TRANSFER}, {@code status =
     *       CONFIRMED} transaction for the schedule and reconcile it (R8.3). Idempotent by
     *       {@code (schedule, BANK_TRANSFER, externalRef)}: a re-delivered line with the same
     *       {@code externalRef} already recorded for the schedule is a no-op (R5.8).</li>
     *   <li><b>Uncorrelated</b> &rarr; record the transaction <b>unmatched</b> (schedule {@code null},
     *       not allocated) and surface it for manual correlation; ingest never fails on an
     *       uncorrelated line (R8.4). Unmatched lines are de-duplicated by {@code externalRef} across
     *       the unmatched set so a re-poll does not pile up duplicates.</li>
     * </ul>
     *
     * @param txn the incoming bank transaction; a {@code null} or amountless line is ignored
     */
    public void ingestBankTransaction(IncomingBankTxn txn) {
        if (txn == null) {
            return;
        }

        PaymentScheduleEntity schedule = correlateSchedule(txn.correlationKey());

        if (schedule == null) {
            // R8.4: uncorrelated -> record unmatched, surface for manual correlation, do not fail.
            recordUnmatchedIdempotent(txn);
            return;
        }

        // R5.8: idempotent for an already-recorded (schedule, BANK_TRANSFER, externalRef) line.
        if (alreadyRecorded(schedule.getId(), PaymentChannel.BANK_TRANSFER, txn.externalRef())) {
            return;
        }

        PaymentTransactionEntity confirmed = new PaymentTransactionEntity();
        confirmed.setSchedule(schedule);
        confirmed.setChannel(PaymentChannel.BANK_TRANSFER);
        confirmed.setExternalRef(txn.externalRef());
        confirmed.setAmountGross(scale(txn.amount()));
        confirmed.setFeeGross(BigDecimal.ZERO);
        confirmed.setReceivedAt(txn.receivedAt() == null ? OffsetDateTime.now()
                : txn.receivedAt().atOffset(OffsetDateTime.now().getOffset()));
        confirmed.setStatus(TransactionStatus.CONFIRMED);

        PaymentTransactionEntity saved = paymentTransactionDao.save(confirmed);
        entityManager.flush();

        // R8.3: a confirmed bank transfer settles tranches through the shared reconciliation cascade.
        reconciliationService.reconcile(saved);
    }

    // --- Online webhook (R7.3, R7.4) ---

    /**
     * Applies a parsed online-payment (przelewy24) webhook outcome (R7.3, R7.4). The affected
     * transaction is found by its {@link OnlineWebhookEvent#externalRef()} on the {@code ONLINE}
     * channel and transitioned per the reported {@link OnlineWebhookEvent#status()}:
     *
     * <ul>
     *   <li>{@code CONFIRMED} &rarr; stamp the reported {@code amountGross}/{@code feeGross}, mark
     *       {@code CONFIRMED} and reconcile (R7.3). Idempotent: an already-{@code CONFIRMED}
     *       transaction is left untouched and not re-allocated (R5.8).</li>
     *   <li>{@code FAILED} &rarr; mark {@code FAILED}; publish an {@link OnlineFailedEvent} (R9.2 row
     *       6). No allocation.</li>
     *   <li>{@code REFUNDED} (reversal) &rarr; mark {@code REFUNDED}; publish an
     *       {@link OnlineFailedEvent} (R9.2 row 6). No re-allocation here (a reversal's unwind is a
     *       later concern; this records the terminal state and notifies).</li>
     * </ul>
     *
     * <p>The commission is recorded as {@code feeGross} under the {@code Payer_Borne_Fee} model and is
     * never deducted from the reconciled {@code amountGross} (R7.4).
     *
     * @param event the parsed, provider-verified webhook event; a {@code null} event is ignored
     * @throws ForemenApiException 404 when no {@code ONLINE} transaction matches the event's
     *                             {@code externalRef}
     */
    public void onOnlineWebhook(OnlineWebhookEvent event) {
        if (event == null || event.externalRef() == null) {
            return;
        }

        PaymentTransactionEntity txn = findOnlineByExternalRef(event.externalRef());
        TransactionStatus reported = event.status();

        if (reported == TransactionStatus.CONFIRMED) {
            // R5.8: idempotent — an already-confirmed online payment is not re-stamped or re-allocated.
            if (txn.getStatus() == TransactionStatus.CONFIRMED) {
                return;
            }
            if (event.amountGross() != null) {
                txn.setAmountGross(scale(event.amountGross())); // reconciled amount (R7.3).
            }
            if (event.feeGross() != null) {
                // R7.4: fee recorded under Payer_Borne_Fee; never deducted from amountGross.
                txn.setFeeGross(scale(event.feeGross()));
            }
            txn.setStatus(TransactionStatus.CONFIRMED);
            PaymentTransactionEntity saved = paymentTransactionDao.save(txn);
            entityManager.flush();
            reconciliationService.reconcile(saved);
            return;
        }

        if (reported == TransactionStatus.FAILED || reported == TransactionStatus.REFUNDED) {
            txn.setStatus(reported); // R7.3: FAILED -> FAILED; reversal -> REFUNDED.
            paymentTransactionDao.save(txn);
            entityManager.flush();
            // R9.2 row 6: emit onlineFailed on failure/reversal — delegated to the best-effort emitter.
            publishOnlineFailed(txn, reported);
        }
        // PENDING or any other reported status is a no-op: nothing to confirm or fail yet.
    }

    // --- Manual record (R8.5, R5.8, R7.4) ---

    /**
     * Records a {@code MANUAL} incoming payment (R8.5) — an off-platform settlement (cash, a
     * reconciled bank line not on the feed) an operator registers, which reconciles identically
     * through the shared cascade. The transaction is {@code channel = MANUAL},
     * {@code status = CONFIRMED}, attributed to the acting user ({@code recordedBy}), with its
     * {@code feeGross} recorded but never deducted from the reconciled amount (R7.4).
     *
     * @param scheduleId the schedule to record the payment against
     * @param in         the manual payment payload (amount, optional fee / received-at / note)
     * @return the recorded, reconciled transaction view
     * @throws ForemenApiException 404 when {@code scheduleId} does not resolve to a schedule
     */
    public PaymentTransactionDto recordManual(Long scheduleId, ManualTxnInput in) {
        PaymentScheduleEntity schedule = paymentScheduleDao.findById(scheduleId)
                .orElseThrow(() -> notFound(scheduleId));

        PaymentTransactionEntity manual = new PaymentTransactionEntity();
        manual.setSchedule(schedule);
        manual.setChannel(PaymentChannel.MANUAL);
        manual.setExternalRef(null); // a bare MANUAL record carries no provider reference (R5.8).
        manual.setAmountGross(scale(in == null ? BigDecimal.ZERO : in.amountGross()));
        manual.setFeeGross(scale(in == null ? null : in.feeGross())); // R7.4: recorded, not deducted.
        manual.setReceivedAt(in != null && in.receivedAt() != null ? in.receivedAt() : OffsetDateTime.now());
        manual.setStatus(TransactionStatus.CONFIRMED);
        manual.setNote(in == null ? null : in.note());
        manual.setRecordedBy(resolveActingUser());

        PaymentTransactionEntity saved = paymentTransactionDao.save(manual);
        entityManager.flush();

        // R8.5: a MANUAL/CONFIRMED record settles tranches through the same nearest-open cascade.
        reconciliationService.reconcile(saved);
        entityManager.flush();

        return paymentDtoMapper.toDto(saved);
    }

    // --- internal: correlation + idempotency helpers ---

    /**
     * Correlates a bank-feed {@code correlationKey} to a schedule (R8.3). The mass-collect feed keys
     * each line to a project via a per-project virtual account / payment reference; here the key is
     * the owning project id, so the schedule is resolved through
     * {@link PaymentScheduleDao#findByProjectId(Long)}. A blank/unparseable key or a project with no
     * schedule yields {@code null} &rarr; the line is recorded unmatched (R8.4).
     */
    private PaymentScheduleEntity correlateSchedule(String correlationKey) {
        if (correlationKey == null || correlationKey.isBlank()) {
            return null;
        }
        Long projectId;
        try {
            projectId = Long.valueOf(correlationKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return paymentScheduleDao.findByProjectId(projectId).orElse(null);
    }

    /** True iff a transaction is already recorded for this {@code (schedule, channel, externalRef)} (R5.8). */
    private boolean alreadyRecorded(Long scheduleId, PaymentChannel channel, String externalRef) {
        if (externalRef == null) {
            return false; // no provider reference -> not idempotency-deduplicated.
        }
        return paymentTransactionDao
                .findByScheduleIdAndChannelAndExternalRef(scheduleId, channel, externalRef)
                .isPresent();
    }

    /**
     * Records an uncorrelated bank line as unmatched (schedule {@code null}), de-duplicating by
     * {@code externalRef} across the current unmatched set so a re-poll does not pile up duplicates
     * (R8.4).
     */
    private void recordUnmatchedIdempotent(IncomingBankTxn txn) {
        String externalRef = txn.externalRef();
        if (externalRef != null) {
            boolean alreadyUnmatched = paymentTransactionDao.findByScheduleIsNull().stream()
                    .anyMatch(existing -> externalRef.equals(existing.getExternalRef())
                            && existing.getChannel() == PaymentChannel.BANK_TRANSFER);
            if (alreadyUnmatched) {
                return;
            }
        }
        PaymentTransactionEntity unmatched = new PaymentTransactionEntity();
        unmatched.setSchedule(null); // R8.4: unmatched, not allocated.
        unmatched.setChannel(PaymentChannel.BANK_TRANSFER);
        unmatched.setExternalRef(externalRef);
        unmatched.setAmountGross(scale(txn.amount()));
        unmatched.setFeeGross(BigDecimal.ZERO);
        unmatched.setReceivedAt(txn.receivedAt() == null ? OffsetDateTime.now()
                : txn.receivedAt().atOffset(OffsetDateTime.now().getOffset()));
        unmatched.setStatus(TransactionStatus.CONFIRMED);
        paymentTransactionDao.save(unmatched);
        entityManager.flush();
    }

    /**
     * Finds the single {@code ONLINE} transaction recorded for an online {@code externalRef}, across
     * all schedules (a webhook is correlated by reference alone). Returns the match or raises 404 when
     * the reference is unknown. Uses a JPA {@code Specification} (via the {@code JpaSpecificationExecutor}
     * surface {@link PaymentTransactionDao} inherits from {@code AdminDao}) so the lookup is a targeted
     * query rather than a full-table scan.
     */
    private PaymentTransactionEntity findOnlineByExternalRef(String externalRef) {
        Specification<PaymentTransactionEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("channel"), PaymentChannel.ONLINE),
                cb.equal(root.get("externalRef"), externalRef));
        return paymentTransactionDao.findAll(spec).stream()
                .findFirst()
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE));
    }

    /** Publishes the online-failed transition so the best-effort emitter (task 5.6) can notify (R9.2 row 6). */
    private void publishOnlineFailed(PaymentTransactionEntity txn, TransactionStatus status) {
        Long scheduleId = txn.getSchedule() == null ? null : txn.getSchedule().getId();
        eventPublisher.publishEvent(new OnlineFailedEvent(txn.getId(), scheduleId, status));
    }

    /** The acting operator for a MANUAL record (R5.10), or {@code null} when the caller cannot be resolved. */
    private UserEntity resolveActingUser() {
        Long userId = callerUserId();
        if (userId == null) {
            return null;
        }
        return userDao.findById(userId).orElse(null);
    }

    /** The authenticated caller's numeric user id, or {@code null} when it cannot be resolved. */
    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id);
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, java.math.RoundingMode.HALF_UP);
    }

    /**
     * FOR-06-07 (Requirement 9.2 row 6): the transition event raised when an online payment ends in
     * {@code FAILED} or {@code REFUNDED}. Published by {@link #onOnlineWebhook} and consumed by the
     * best-effort {@code PaymentNotificationEmitter} (task 5.6) via an {@code AFTER_COMMIT}
     * {@code @TransactionalEventListener}, so an emission failure never rolls back the webhook
     * transition (R9.5). Following the repo {@code OfferStateChangeEvent} convention — a plain
     * immutable record published through {@code ApplicationEventPublisher}.
     *
     * @param transactionId the online transaction that failed / was reversed
     * @param scheduleId    the owning schedule id, or {@code null} if unlinked
     * @param status        the terminal status ({@code FAILED} or {@code REFUNDED})
     */
    public record OnlineFailedEvent(Long transactionId, Long scheduleId, TransactionStatus status) {
    }
}
