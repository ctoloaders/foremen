package com.foremen.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.TrancheAllocationEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payment.dto.ManualPayInput;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;

/**
 * FOR-06-07 (Requirements 5.1–5.7, 5.9–5.12; design §ReconciliationService): the thin apply-layer
 * that turns a confirmed payment into persisted settlements, and the dedicated manual
 * mark-tranche-paid action.
 *
 * <p>All invariant cascade arithmetic lives in the pure, persistence-free
 * {@link ReconciliationEngine} (nearest-open-first cascade, carry-over, held leftover credit) and
 * {@link TrancheStatusDeriver} (status derivation); this service only <b>applies</b> their results —
 * it writes {@code TrancheAllocation} rows, bumps each tranche's {@code paidGross}, adds the
 * leftover to {@code schedule.unallocatedCredit}, and writes the materialized status cache through
 * the deriver (never hand-setting a status, R10.3).
 *
 * <h2>{@link #reconcile(PaymentTransactionEntity)} (Requirements 5.1–5.7)</h2>
 * Given a {@code CONFIRMED} transaction bound to a schedule, it allocates the transaction's
 * {@code amountGross} across the schedule's open (not-{@code PAID}) tranches ordered nearest-open
 * first by {@code seqNo}: underpayment leaves the tranche {@code PARTIALLY_PAID}; an exact / over
 * payment settles it to {@code PAID} and carries the surplus forward; any final surplus with no open
 * tranche left is held as {@code unallocatedCredit} (no phantom tranche, R5.5). The schedule rolls to
 * {@code SETTLED} iff every tranche is {@code PAID} (R5.6). It is idempotent per transaction: a
 * transaction that already carries allocations is a no-op (no double-allocation, R5.8), so
 * re-processing a re-delivered confirmed payment settles nothing twice.
 *
 * <h2>{@link #markTranchePaid(Long, ManualPayInput)} (Requirements 5.9–5.12)</h2>
 * Settles <b>exactly one</b> tranche confirmed off-platform (cash, an agreed waiver, a reconciled
 * bank line), independent of the nearest-open cascade. It records a {@code MANUAL}/{@code CONFIRMED}
 * transaction for the tranche's <b>remaining</b> amount ({@code amountGross − paidGross}), allocates
 * it to that tranche only, sets {@code paidGross = amountGross}, derives {@code PAID}, attributes the
 * acting user ({@code recordedBy}), and accepts an optional note + received-at (R5.10). It is
 * idempotent: an already-{@code PAID} tranche is a no-op (no second transaction, no double
 * allocation); a {@code PARTIALLY_PAID} tranche is settled on only its remainder — the already-paid
 * part is never re-charged (R5.11). The settlement triggers the same downstream effects as any
 * confirmed settlement (the {@code SETTLED} roll-up when it is the last open tranche, an auditable
 * allocation; the notifications are emitted by the best-effort emitter wired in a later task, R5.12).
 *
 * <p><b>Localized error codes</b> referenced here (added to the message bundle by task 10.1):
 * {@code error.entity.not.found} (404, missing tranche).
 */
@Service
@Transactional
public class ReconciliationService {

    /** 404 for a missing tranche / schedule, matching the generic not-found convention. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private static final int SCALE = 2;

    private final ReconciliationEngine reconciliationEngine;
    private final TrancheStatusDeriver trancheStatusDeriver;
    private final PaymentDtoMapper paymentDtoMapper;
    private final PaymentScheduleDao paymentScheduleDao;
    private final PaymentTransactionDao paymentTransactionDao;
    private final UserDao userDao;
    private final ApplicationEventPublisher eventPublisher;

    public ReconciliationService(ReconciliationEngine reconciliationEngine,
                                 TrancheStatusDeriver trancheStatusDeriver,
                                 PaymentDtoMapper paymentDtoMapper,
                                 PaymentScheduleDao paymentScheduleDao,
                                 PaymentTransactionDao paymentTransactionDao,
                                 UserDao userDao,
                                 ApplicationEventPublisher eventPublisher) {
        this.reconciliationEngine = reconciliationEngine;
        this.trancheStatusDeriver = trancheStatusDeriver;
        this.paymentDtoMapper = paymentDtoMapper;
        this.paymentScheduleDao = paymentScheduleDao;
        this.paymentTransactionDao = paymentTransactionDao;
        this.userDao = userDao;
        this.eventPublisher = eventPublisher;
    }

    // --- Cascade allocation of a confirmed payment (R5.1–5.7) ---

    /**
     * Applies a {@code CONFIRMED} transaction's {@code amountGross} to its schedule's open tranches
     * via the nearest-open-first cascade (Requirements 5.1–5.7). The fee is excluded upstream and
     * never reaches the planner (R7.4) — only {@code amountGross} is allocated.
     *
     * <ol>
     *   <li>Resolves the schedule from the transaction (callers — {@code PaymentTransactionService}
     *       in task 5.4 — bind the transaction to its schedule before confirming); a transaction with
     *       no schedule (an unmatched bank line, R8.4) is a no-op.</li>
     *   <li><b>Idempotency (R5.8):</b> a transaction that already carries allocations was already
     *       reconciled — it is a no-op, so a re-delivered confirmed payment never double-allocates.</li>
     *   <li>Collects the open (not-{@code PAID}) tranches ordered by {@code seqNo}, plans the cascade
     *       with {@link ReconciliationEngine}, then applies the plan: one
     *       {@link TrancheAllocationEntity} per touched tranche and a bump of that tranche's
     *       {@code paidGross} (R5.4, R5.7).</li>
     *   <li>Adds any final surplus to {@code schedule.unallocatedCredit} (R5.5), re-derives every
     *       tranche's status, and rolls the schedule to {@code SETTLED} iff all are {@code PAID}
     *       (R5.6).</li>
     * </ol>
     *
     * @param confirmed the confirmed transaction to allocate (bound to its schedule); a {@code null}
     *                  transaction, a transaction with no schedule, or one already reconciled is a
     *                  no-op
     */
    public void reconcile(PaymentTransactionEntity confirmed) {
        if (confirmed == null) {
            return;
        }
        PaymentScheduleEntity schedule = confirmed.getSchedule();
        if (schedule == null) {
            // Unmatched transaction (no correlated schedule, R8.4) — nothing to allocate.
            return;
        }
        // R5.8: a transaction already carrying allocations was reconciled before — no double-allocation.
        if (confirmed.getAllocations() != null && !confirmed.getAllocations().isEmpty()) {
            return;
        }

        List<PaymentTrancheEntity> openBySeqNo = openTranchesBySeqNo(schedule);
        List<OpenTranche> planInput = new ArrayList<>(openBySeqNo.size());
        for (PaymentTrancheEntity tranche : openBySeqNo) {
            planInput.add(new OpenTranche(seqNoOf(tranche), remainingOf(tranche)));
        }

        BigDecimal amount = scale(confirmed.getAmountGross());
        AllocationPlan plan = reconciliationEngine.plan(planInput, amount);

        // Snapshot which tranches were already PAID so we can detect fresh settlements (R9.2 row 2).
        List<Long> paidBefore = settledTrancheIds(schedule);

        // Apply each planned allocation: write the TrancheAllocation row + bump paidGross (R5.4, R5.7).
        for (Allocation allocation : plan.allocations()) {
            PaymentTrancheEntity tranche = findBySeqNo(openBySeqNo, allocation.trancheSeqNo());
            if (tranche == null) {
                continue;
            }
            BigDecimal applied = scale(allocation.applied());
            applyAllocation(confirmed, tranche, applied);
        }

        // R5.5: hold any final surplus as the schedule's unallocated credit (no phantom tranche).
        BigDecimal leftover = scale(plan.leftoverCredit());
        if (leftover.signum() > 0) {
            schedule.setUnallocatedCredit(scale(nz(schedule.getUnallocatedCredit())).add(leftover));
        }

        rederiveStatuses(schedule);        // write the status cache + roll to SETTLED iff all PAID (R5.6).
        paymentScheduleDao.save(schedule);

        // Best-effort payment notifications, published for the AFTER_COMMIT emitter (task 5.6). The
        // emitter never rolls this reconciliation back (R9.5); publishing only stages the events.
        publishReconciliationEvents(schedule, paidBefore, plan, leftover);
    }

    /**
     * Stages the best-effort payment notifications for a committed reconciliation (R9.2 rows 1, 2, 4).
     * All three are consumed by the {@code AFTER_COMMIT} {@code PaymentNotificationEmitter}:
     *
     * <ul>
     *   <li><b>received</b> (row 1) — a confirmed payment was allocated against the schedule.</li>
     *   <li><b>tranchePaid</b> (row 2) — one event per tranche that <em>newly</em> reached
     *       {@code PAID} on this reconciliation (compared against the pre-apply snapshot).</li>
     *   <li><b>amountMismatch</b> (row 4) — the payment did not settle cleanly: a shortfall left an
     *       open tranche {@code PARTIALLY_PAID} (a planned allocation under its remaining) or a
     *       carry-over held surplus as unallocated credit.</li>
     * </ul>
     */
    private void publishReconciliationEvents(PaymentScheduleEntity schedule, List<Long> paidBefore,
                                             AllocationPlan plan, BigDecimal leftover) {
        Long scheduleId = schedule.getId();
        Long projectId = projectIdOf(schedule);

        // Row 1: a confirmed payment became allocated.
        eventPublisher.publishEvent(new PaymentReceivedEvent(scheduleId, projectId));

        // Row 2: one tranchePaid per tranche that first crossed into PAID on this reconciliation.
        for (PaymentTrancheEntity tranche : schedule.getTranches()) {
            if (tranche.getStatus() == TrancheStatus.PAID
                    && tranche.getId() != null && !paidBefore.contains(tranche.getId())) {
                eventPublisher.publishEvent(
                        new TranchePaidEvent(tranche.getId(), scheduleId, projectId));
            }
        }

        // Row 4: under/overpayment — a surplus held as credit, or any tranche left PARTIALLY_PAID.
        boolean carryOver = leftover != null && leftover.signum() > 0;
        boolean shortfall = schedule.getTranches().stream()
                .anyMatch(t -> t.getStatus() == TrancheStatus.PARTIALLY_PAID);
        if (carryOver || shortfall) {
            eventPublisher.publishEvent(new AmountMismatchEvent(scheduleId, projectId));
        }
    }

    /** The ids of the schedule's tranches currently settled ({@code paidGross >= amountGross}). */
    private static List<Long> settledTrancheIds(PaymentScheduleEntity schedule) {
        List<Long> ids = new ArrayList<>();
        for (PaymentTrancheEntity tranche : schedule.getTranches()) {
            if (tranche.getId() != null && remainingOf(tranche).signum() <= 0) {
                ids.add(tranche.getId());
            }
        }
        return ids;
    }

    /** The owning project id of a schedule, or {@code null} when unresolved. */
    private static Long projectIdOf(PaymentScheduleEntity schedule) {
        return schedule.getProject() == null ? null : schedule.getProject().getId();
    }

    // --- Manual mark-tranche-paid (R5.9–5.12) ---

    /**
     * Marks a single tranche {@code PAID} manually, independent of the nearest-open cascade
     * (Requirements 5.9–5.12). It settles <b>exactly</b> the targeted tranche's remaining amount
     * through a recorded {@code MANUAL}/{@code CONFIRMED} transaction and returns the refreshed
     * schedule view.
     *
     * <ul>
     *   <li><b>Idempotent (R5.11):</b> a tranche already settled ({@code paidGross >= amountGross}) is
     *       a no-op — no second transaction, no double allocation; the current view is returned.</li>
     *   <li><b>Remainder only (R5.11):</b> a {@code PARTIALLY_PAID} tranche is settled on only its
     *       remaining amount ({@code amountGross − paidGross}); the already-paid part is not
     *       re-charged.</li>
     *   <li>Records a {@code PaymentTransaction{channel=MANUAL, status=CONFIRMED,
     *       amountGross=remaining, receivedAt, note, recordedBy}}, allocates it to that tranche only,
     *       sets {@code paidGross = amountGross}, and derives {@code PAID} (R5.10).</li>
     *   <li>Rolls the schedule to {@code SETTLED} when this was the last open tranche (R5.12, 5.6).</li>
     * </ul>
     *
     * @param trancheId the tranche to settle (its owning schedule is resolved from it)
     * @param in        the optional note + received-at (both may be {@code null}; received-at defaults
     *                  to now, R5.10)
     * @return the refreshed {@link PaymentScheduleView} of the tranche's schedule
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the tranche does not exist
     */
    public PaymentScheduleView markTranchePaid(Long trancheId, ManualPayInput in) {
        PaymentTrancheEntity tranche = loadTranche(trancheId);
        PaymentScheduleEntity schedule = tranche.getSchedule();

        BigDecimal amountGross = scale(nz(tranche.getAmountGross()));
        BigDecimal paidGross = scale(nz(tranche.getPaidGross()));
        BigDecimal remaining = amountGross.subtract(paidGross);

        // R5.11: an already-PAID tranche is a no-op — no second transaction, no double allocation.
        if (remaining.signum() <= 0) {
            return toView(schedule);
        }

        // R5.10: record the MANUAL/CONFIRMED transaction for exactly the remaining amount.
        PaymentTransactionEntity txn = new PaymentTransactionEntity();
        txn.setSchedule(schedule);
        txn.setChannel(PaymentChannel.MANUAL);
        txn.setExternalRef(null);
        txn.setAmountGross(remaining);
        txn.setFeeGross(BigDecimal.ZERO);
        txn.setReceivedAt(in != null && in.receivedAt() != null
                ? in.receivedAt() : OffsetDateTime.now(ZoneOffset.UTC));
        txn.setStatus(TransactionStatus.CONFIRMED);
        txn.setNote(in == null ? null : in.note());
        txn.setRecordedBy(currentUser());
        PaymentTransactionEntity savedTxn = paymentTransactionDao.save(txn);

        // Allocate the remaining to exactly that tranche (not the cascade, R5.9) and settle it (R5.10).
        applyAllocation(savedTxn, tranche, remaining);

        rederiveStatuses(schedule);        // derive PAID + roll to SETTLED iff it was the last open (R5.12).
        PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);

        // R5.12: a manual settlement triggers the same downstream notifications as any confirmed one —
        // the tranchePaid notification (R9.2 row 2), staged for the AFTER_COMMIT emitter (task 5.6).
        eventPublisher.publishEvent(new TranchePaidEvent(
                tranche.getId(), saved.getId(), projectIdOf(saved)));
        return toView(saved);
    }

    // --- internal: allocation application ---

    /**
     * Writes one {@link TrancheAllocationEntity} linking {@code transaction} to {@code tranche} for
     * {@code applied} and bumps the tranche's {@code paidGross} by the same amount (R5.4, R5.7). The
     * allocation is appended to the transaction's owned collection so the cascade persist writes it;
     * the status cache is written later by {@link #rederiveStatuses(PaymentScheduleEntity)}.
     */
    private static void applyAllocation(PaymentTransactionEntity transaction,
                                        PaymentTrancheEntity tranche, BigDecimal applied) {
        TrancheAllocationEntity allocation = new TrancheAllocationEntity();
        allocation.setTransaction(transaction);
        allocation.setTranche(tranche);
        allocation.setAmount(applied);
        transaction.getAllocations().add(allocation);

        tranche.setPaidGross(scale(nz(tranche.getPaidGross())).add(applied));
    }

    // --- internal: status derivation + view assembly ---

    /**
     * Re-derive every tranche's materialized status from its current figures + now, then the schedule
     * status ({@code SETTLED} iff every tranche is {@code PAID}), via the pure
     * {@link TrancheStatusDeriver} — the only writer of a status (R10.3, R1.6, R5.6).
     */
    private void rederiveStatuses(PaymentScheduleEntity schedule) {
        Instant now = Instant.now();
        List<TrancheStatus> statuses = new ArrayList<>();
        for (PaymentTrancheEntity tranche : schedule.getTranches()) {
            TrancheStatus derived = trancheStatusDeriver.derive(
                    scale(nz(tranche.getPaidGross())),
                    scale(nz(tranche.getAmountGross())),
                    tranche.getDueDate(), now);
            tranche.setStatus(derived);
            statuses.add(derived);
        }
        schedule.setStatus(trancheStatusDeriver.deriveSchedule(statuses, schedule.getStatus()));
    }

    private PaymentScheduleView toView(PaymentScheduleEntity schedule) {
        rederiveStatuses(schedule);
        return paymentDtoMapper.toView(schedule);
    }

    // --- internal: open-tranche selection ---

    /**
     * The schedule's open (not-{@code PAID}) tranches ordered nearest-open-first by {@code seqNo} —
     * exactly the cascade input (R5.1). A tranche is open while {@code paidGross < amountGross}.
     */
    private static List<PaymentTrancheEntity> openTranchesBySeqNo(PaymentScheduleEntity schedule) {
        List<PaymentTrancheEntity> tranches = schedule.getTranches() == null
                ? List.of() : schedule.getTranches();
        List<PaymentTrancheEntity> open = new ArrayList<>();
        for (PaymentTrancheEntity tranche : tranches) {
            if (remainingOf(tranche).signum() > 0) {
                open.add(tranche);
            }
        }
        open.sort(Comparator.comparing(ReconciliationService::seqNoOf));
        return open;
    }

    private static PaymentTrancheEntity findBySeqNo(List<PaymentTrancheEntity> tranches, int seqNo) {
        for (PaymentTrancheEntity tranche : tranches) {
            if (seqNoOf(tranche) == seqNo) {
                return tranche;
            }
        }
        return null;
    }

    private static int seqNoOf(PaymentTrancheEntity tranche) {
        return tranche.getSeqNo() == null ? 0 : tranche.getSeqNo();
    }

    /** The tranche's outstanding amount ({@code amountGross − paidGross}), clamped at zero. */
    private static BigDecimal remainingOf(PaymentTrancheEntity tranche) {
        BigDecimal amount = scale(nz(tranche.getAmountGross()));
        BigDecimal paid = scale(nz(tranche.getPaidGross()));
        return amount.subtract(paid).max(BigDecimal.ZERO);
    }

    // --- internal: tranche load + current-user resolution ---

    private PaymentTrancheEntity loadTranche(Long trancheId) {
        PaymentScheduleEntity schedule = trancheId == null ? null
                : findScheduleByTrancheId(trancheId);
        if (schedule != null) {
            for (PaymentTrancheEntity tranche : schedule.getTranches()) {
                if (tranche.getId() != null && tranche.getId().equals(trancheId)) {
                    return tranche;
                }
            }
        }
        throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, trancheId);
    }

    /**
     * Resolves the owning schedule for a tranche id by scanning the project's schedules. The tranche
     * is a collection child of its schedule (owner FK {@code schedule_id}); loading the schedule keeps
     * the aggregate managed so the collection mutations persist on save. A linear scan over the (very
     * small) set of schedules is acceptable — there is at most one schedule per project.
     */
    private PaymentScheduleEntity findScheduleByTrancheId(Long trancheId) {
        for (PaymentScheduleEntity schedule : paymentScheduleDao.findAll()) {
            for (PaymentTrancheEntity tranche : schedule.getTranches()) {
                if (tranche.getId() != null && tranche.getId().equals(trancheId)) {
                    return schedule;
                }
            }
        }
        return null;
    }

    /**
     * The authenticated caller's {@link UserEntity} for the {@code recordedBy} attribution (R5.10),
     * or {@code null} when it cannot be resolved. The auth name is the caller's numeric user id
     * (matching {@code PaymentScheduleService}); it is resolved to the managed user via the
     * {@link UserDao}.
     */
    private UserEntity currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return userDao.findById(userId).orElse(null);
    }

    // --- internal: arithmetic helpers ---

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
