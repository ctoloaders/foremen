package com.foremen.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.TrancheAllocationEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.payment.dto.AddTrancheInput;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-07 (Requirement 3; design §ManualSchedulerService): authors / adjusts a schedule's tranches
 * <b>by hand</b>, the complement to the generator-driven {@link PaymentScheduleService}.
 *
 * <p>Two families of write paths, both gated by the {@code PAYMENTS} UPDATE operation at the
 * controller layer (Requirement 3.7 — only ADMIN / MANAGER(own) / FINANCIER(own) reach them; a
 * FOREMAN cannot):
 *
 * <ul>
 *   <li><b>{@code DRAFT} structural edits</b> ({@link #addTranche}, {@link #editTranche},
 *       {@link #removeTranche}, {@link #reorder}): add / edit (amount + due date) / re-order / remove
 *       tranches, setting {@code source = MANUAL} and re-deriving a contiguous {@code seqNo}
 *       {@code 1..N} by {@code dueDate} after every mutation (Requirements 3.1, 3.2). These are
 *       permitted only while {@code DRAFT} — a published schedule is structurally frozen except the
 *       add-tranche-anytime action and the re-plan path.</li>
 *   <li><b>Add-tranche-anytime</b> ({@link #addTrancheAnytime}): appends a single tranche by hand even
 *       while the schedule is {@code PUBLISHED}. It appends the tranche, re-derives {@code seqNo} by
 *       {@code dueDate}, raises {@code basisGross} by the added amount on a {@code PUBLISHED} schedule
 *       (an intentional change to the total owed, Requirement 3.4), keeps the schedule
 *       {@code PUBLISHED}, re-derives statuses, and re-runs allocation of any held
 *       {@code unallocatedCredit} against the newly open tranche — never touching a {@code PAID}
 *       tranche or its allocations (Requirements 3.3, 3.4, 6.2). The reconcile invariant
 *       {@code Σ amountGross == basisGross} holds after the add.</li>
 * </ul>
 *
 * <p><b>Validation.</b> A negative / zero amount or an absent due date on any add / edit is rejected
 * {@code 422 error.payment.tranche.invalid} (Requirement 3.6).
 *
 * <p><b>Pure collaborators.</b> Status is never hand-set: it is re-derived through the pure
 * {@link TrancheStatusDeriver} after every mutation (Requirement 10.3), and the held-credit re-run
 * uses the pure {@link ReconciliationEngine} planner (no compile coupling to the concurrently-built
 * {@code ReconciliationService}).
 */
@Service
@Transactional
public class ManualSchedulerService {

    /** 404 for a missing <em>or</em> a non-accessible schedule (Requirement 11.3). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 422 when a tranche amount is negative / zero or its due date is absent (Requirement 3.6). */
    static final String TRANCHE_INVALID_MESSAGE = "error.payment.tranche.invalid";

    /** 409 when a {@code DRAFT}-only structural edit targets a non-{@code DRAFT} schedule (Requirement 3.1). */
    static final String SCHEDULE_LOCKED_MESSAGE = "error.payment.schedule.locked";

    private static final int SCALE = 2;

    private final PaymentScheduleDao paymentScheduleDao;
    private final PaymentTransactionDao paymentTransactionDao;
    private final TrancheStatusDeriver trancheStatusDeriver;
    private final ReconciliationEngine reconciliationEngine;
    private final PaymentDtoMapper paymentDtoMapper;
    private final ProjectAccessCache projectAccessCache;
    private final EntityManager entityManager;

    public ManualSchedulerService(PaymentScheduleDao paymentScheduleDao,
                                  PaymentTransactionDao paymentTransactionDao,
                                  TrancheStatusDeriver trancheStatusDeriver,
                                  ReconciliationEngine reconciliationEngine,
                                  PaymentDtoMapper paymentDtoMapper,
                                  ProjectAccessCache projectAccessCache,
                                  EntityManager entityManager) {
        this.paymentScheduleDao = paymentScheduleDao;
        this.paymentTransactionDao = paymentTransactionDao;
        this.trancheStatusDeriver = trancheStatusDeriver;
        this.reconciliationEngine = reconciliationEngine;
        this.paymentDtoMapper = paymentDtoMapper;
        this.projectAccessCache = projectAccessCache;
        this.entityManager = entityManager;
    }

    // --- DRAFT structural edits (Requirements 3.1, 3.2) ---

    /**
     * Adds a tranche to a {@code DRAFT} schedule by hand (Requirements 3.1, 3.2). Sets
     * {@code source = MANUAL}, appends the tranche, and re-derives a contiguous {@code seqNo}
     * {@code 1..N} by {@code dueDate}. A non-{@code DRAFT} schedule is rejected {@code 409
     * error.payment.schedule.locked} — use {@link #addTrancheAnytime} to add to a published schedule.
     *
     * @param scheduleId the schedule to edit
     * @param in         the new tranche (amount + due date)
     * @return the edited schedule view
     * @throws ForemenApiException 404 missing / out of scope; 409 non-{@code DRAFT}; 422 invalid input
     */
    public PaymentScheduleView addTranche(Long scheduleId, AddTrancheInput in) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        assertDraft(schedule);
        validate(in);

        schedule.setSource(ScheduleSource.MANUAL);
        schedule.getTranches().add(newTranche(schedule, in.amountGross(), in.dueDate()));
        resequence(schedule);
        rederiveStatuses(schedule);
        return persist(schedule);
    }

    /**
     * Edits an existing tranche's amount + due date on a {@code DRAFT} schedule (Requirements 3.1,
     * 3.2). Sets {@code source = MANUAL} and re-derives {@code seqNo} by the (possibly changed)
     * {@code dueDate}.
     *
     * @param scheduleId the owning schedule
     * @param trancheId  the tranche to edit (must belong to {@code scheduleId})
     * @param in         the new amount + due date
     * @return the edited schedule view
     * @throws ForemenApiException 404 missing / out of scope / tranche not on schedule; 409
     *                             non-{@code DRAFT}; 422 invalid input
     */
    public PaymentScheduleView editTranche(Long scheduleId, Long trancheId, AddTrancheInput in) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        assertDraft(schedule);
        validate(in);

        PaymentTrancheEntity tranche = findOwnTranche(schedule, trancheId);
        schedule.setSource(ScheduleSource.MANUAL);
        tranche.setAmountGross(scale(in.amountGross()));
        tranche.setDueDate(in.dueDate());
        resequence(schedule);
        rederiveStatuses(schedule);
        return persist(schedule);
    }

    /**
     * Removes a tranche from a {@code DRAFT} schedule (Requirements 3.1, 3.2). Sets
     * {@code source = MANUAL} and re-derives a contiguous {@code seqNo} {@code 1..N} over the
     * survivors.
     *
     * @param scheduleId the owning schedule
     * @param trancheId  the tranche to remove (must belong to {@code scheduleId})
     * @return the edited schedule view
     * @throws ForemenApiException 404 missing / out of scope / tranche not on schedule; 409
     *                             non-{@code DRAFT}
     */
    public PaymentScheduleView removeTranche(Long scheduleId, Long trancheId) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        assertDraft(schedule);

        PaymentTrancheEntity tranche = findOwnTranche(schedule, trancheId);
        schedule.setSource(ScheduleSource.MANUAL);
        schedule.getTranches().remove(tranche);
        resequence(schedule);
        rederiveStatuses(schedule);
        return persist(schedule);
    }

    /**
     * Re-orders a {@code DRAFT} schedule's tranches (Requirements 3.1, 3.2). Ordering is a pure
     * function of each tranche's {@code dueDate}, so a re-order is a re-derivation of the contiguous
     * {@code seqNo} {@code 1..N} by {@code dueDate} over the current tranche set; it sets
     * {@code source = MANUAL} and makes the ordering explicit without changing amounts or dates.
     *
     * @param scheduleId the schedule whose tranches to re-sequence
     * @return the re-sequenced schedule view
     * @throws ForemenApiException 404 missing / out of scope; 409 non-{@code DRAFT}
     */
    public PaymentScheduleView reorder(Long scheduleId) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        assertDraft(schedule);

        schedule.setSource(ScheduleSource.MANUAL);
        resequence(schedule);
        rederiveStatuses(schedule);
        return persist(schedule);
    }

    // --- Add-tranche-anytime (Requirements 3.3, 3.4) ---

    /**
     * Adds a single tranche by hand at any time — permitted even while the schedule is
     * {@code PUBLISHED} (Requirements 3.3, 3.4). The tranche is appended, {@code seqNo} is re-derived
     * {@code 1..N} by {@code dueDate} (Requirement 3.2), and:
     *
     * <ul>
     *   <li>On a {@code PUBLISHED} (or {@code SETTLED}) schedule the added amount is an intentional
     *       increase to the total owed, so {@code basisGross} is raised by {@link AddTrancheInput#amountGross}
     *       (Requirement 3.4). The schedule stays {@code PUBLISHED}; it never un-publishes.</li>
     *   <li>On a {@code DRAFT} schedule {@code basisGross} is <b>not</b> touched (the draft still
     *       reconciles against its original basis before publish), matching {@link #addTranche}.</li>
     * </ul>
     *
     * <p>No {@code PAID} tranche or its allocations are touched (Requirement 6.2). After the add the
     * statuses are re-derived (Requirement 10) and any held {@code unallocatedCredit} is re-run
     * against the newly open tranche(s) via the pure {@link ReconciliationEngine} (Requirement 3.4,
     * 5.5). The reconcile invariant {@code Σ amountGross == basisGross} holds afterwards on a
     * published schedule.
     *
     * @param scheduleId the schedule to append to
     * @param in         the new tranche (amount + due date)
     * @return the updated schedule view
     * @throws ForemenApiException 404 missing / out of scope; 422 invalid input
     */
    public PaymentScheduleView addTrancheAnytime(Long scheduleId, AddTrancheInput in) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        validate(in);

        boolean published = schedule.getStatus() != ScheduleStatus.DRAFT;

        schedule.getTranches().add(newTranche(schedule, in.amountGross(), in.dueDate()));
        resequence(schedule);

        if (published) {
            // R3.4: a manual add to a published schedule raises the total owed.
            schedule.setBasisGross(scale(schedule.getBasisGross()).add(scale(in.amountGross())));
            // R3.4/R5.5: re-run any held unallocated credit against the now-open tranche(s).
            applyHeldCredit(schedule);
        }

        rederiveStatuses(schedule);
        return persist(schedule);
    }

    // --- held-credit re-run (Requirements 3.4, 5.5) ---

    /**
     * Re-applies any held {@code unallocatedCredit} to the schedule's open (not-{@code PAID}) tranches
     * in {@code seqNo} order via the pure {@link ReconciliationEngine} — used after an add-tranche to
     * a published schedule opens new capacity for previously-held surplus (Requirements 3.4, 5.5).
     *
     * <p>It plans the cascade, bumps each touched tranche's {@code paidGross}, reduces
     * {@code unallocatedCredit} by what was applied, and records an auditable {@link TrancheAllocationEntity}
     * on the most-recent {@code CONFIRMED} transaction of the schedule (the overpayment that produced
     * the surplus) so the settlement stays reproducible (Requirement 5.7). A {@code PAID} tranche is
     * never in the open set, so its allocations are untouched (Requirement 6.2). No-op when there is
     * no held credit or no open tranche.
     */
    private void applyHeldCredit(PaymentScheduleEntity schedule) {
        BigDecimal credit = scale(schedule.getUnallocatedCredit());
        if (credit.signum() <= 0) {
            return;
        }

        List<PaymentTrancheEntity> open = openTranchesBySeqNo(schedule);
        if (open.isEmpty()) {
            return;
        }

        List<OpenTranche> openView = new ArrayList<>(open.size());
        for (PaymentTrancheEntity t : open) {
            openView.add(new OpenTranche(t.getSeqNo(), remaining(t)));
        }

        AllocationPlan plan = reconciliationEngine.plan(openView, credit);
        if (plan.allocations().isEmpty()) {
            return;
        }

        PaymentTransactionEntity source = latestConfirmedTransaction(schedule);
        for (Allocation alloc : plan.allocations()) {
            PaymentTrancheEntity target = bySeqNo(open, alloc.trancheSeqNo());
            target.setPaidGross(scale(target.getPaidGross()).add(scale(alloc.applied())));
            if (source != null) {
                recordAllocation(source, target, scale(alloc.applied()));
            }
        }
        // Held credit shrinks by exactly what the cascade applied (conservation); leftover stays held.
        schedule.setUnallocatedCredit(scale(plan.leftoverCredit()));
    }

    private static void recordAllocation(PaymentTransactionEntity transaction,
                                         PaymentTrancheEntity tranche, BigDecimal amount) {
        TrancheAllocationEntity allocation = new TrancheAllocationEntity();
        allocation.setTransaction(transaction);
        allocation.setTranche(tranche);
        allocation.setAmount(amount);
        transaction.getAllocations().add(allocation);
    }

    private PaymentTransactionEntity latestConfirmedTransaction(PaymentScheduleEntity schedule) {
        List<PaymentTransactionEntity> txns =
                paymentTransactionDao.findByScheduleIdOrderByIdAsc(schedule.getId());
        PaymentTransactionEntity latest = null;
        for (PaymentTransactionEntity txn : txns) {
            if (txn.getStatus() == TransactionStatus.CONFIRMED) {
                latest = txn; // oldest-first ordering → last match is the most recent CONFIRMED.
            }
        }
        return latest;
    }

    // --- internal: tranche helpers ---

    private static List<PaymentTrancheEntity> openTranchesBySeqNo(PaymentScheduleEntity schedule) {
        List<PaymentTrancheEntity> open = new ArrayList<>();
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            if (!isPaid(t)) {
                open.add(t);
            }
        }
        open.sort(Comparator.comparing(PaymentTrancheEntity::getSeqNo,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return open;
    }

    private static PaymentTrancheEntity bySeqNo(List<PaymentTrancheEntity> tranches, int seqNo) {
        for (PaymentTrancheEntity t : tranches) {
            if (t.getSeqNo() != null && t.getSeqNo() == seqNo) {
                return t;
            }
        }
        throw new IllegalStateException("planned allocation references unknown seqNo " + seqNo);
    }

    private static BigDecimal remaining(PaymentTrancheEntity tranche) {
        return scale(tranche.getAmountGross()).subtract(scale(tranche.getPaidGross()));
    }

    private static boolean isPaid(PaymentTrancheEntity tranche) {
        return scale(tranche.getPaidGross()).compareTo(scale(tranche.getAmountGross())) >= 0;
    }

    /** Builds a new managed tranche attached to {@code schedule}; paidGross 0, status derived later. */
    private static PaymentTrancheEntity newTranche(PaymentScheduleEntity schedule,
                                                   BigDecimal amountGross, LocalDate dueDate) {
        PaymentTrancheEntity tranche = new PaymentTrancheEntity();
        tranche.setSchedule(schedule);
        tranche.setDueDate(dueDate);
        tranche.setAmountGross(scale(amountGross));
        tranche.setPaidGross(BigDecimal.ZERO);
        tranche.setStatus(TrancheStatus.PENDING);
        return tranche;
    }

    /** Re-derive a contiguous {@code seqNo} {@code 1..N} ordered by {@code dueDate} (R2.1, R3.2). */
    private static void resequence(PaymentScheduleEntity schedule) {
        List<PaymentTrancheEntity> ordered = new ArrayList<>(schedule.getTranches());
        ordered.sort(Comparator.comparing(PaymentTrancheEntity::getDueDate,
                Comparator.nullsLast(Comparator.naturalOrder())));
        int seq = 1;
        for (PaymentTrancheEntity t : ordered) {
            t.setSeqNo(seq++);
        }
    }

    /**
     * Re-derive every tranche's materialized status from its current figures + now, then the schedule
     * status ({@code SETTLED} iff every tranche is {@code PAID}), via the pure
     * {@link TrancheStatusDeriver} — the only writer of a status (R10.3, R1.6, R5.6).
     */
    private void rederiveStatuses(PaymentScheduleEntity schedule) {
        Instant now = Instant.now();
        List<TrancheStatus> statuses = new ArrayList<>();
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            TrancheStatus s = trancheStatusDeriver.derive(
                    t.getPaidGross() == null ? BigDecimal.ZERO : t.getPaidGross(),
                    t.getAmountGross() == null ? BigDecimal.ZERO : t.getAmountGross(),
                    t.getDueDate(), now);
            t.setStatus(s);
            statuses.add(s);
        }
        schedule.setStatus(trancheStatusDeriver.deriveSchedule(statuses, schedule.getStatus()));
    }

    // --- internal: validation + persistence ---

    /** R3.6: reject a null input, a null/negative/zero amount, or an absent due date with 422. */
    private static void validate(AddTrancheInput in) {
        if (in == null
                || in.amountGross() == null
                || in.amountGross().signum() <= 0
                || in.dueDate() == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, TRANCHE_INVALID_MESSAGE);
        }
    }

    /** R3.1: the DRAFT-only structural edits run only while the schedule is {@code DRAFT}. */
    private static void assertDraft(PaymentScheduleEntity schedule) {
        if (schedule.getStatus() != ScheduleStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_LOCKED_MESSAGE);
        }
    }

    private PaymentScheduleView persist(PaymentScheduleEntity schedule) {
        PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);
        entityManager.flush();
        return paymentDtoMapper.toView(saved);
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // --- Schedule access gate (R11.1, R11.3) ---

    /**
     * Loads the managed schedule by id and asserts the caller may access its owning project — all
     * manual write paths pass through here. A non-ADMIN caller must have the schedule's project id in
     * {@link ProjectAccessCache#get(Long)}; both a missing and an out-of-scope schedule yield an
     * identical {@code 404 error.entity.not.found} so they are indistinguishable (Requirement 11.3).
     * Fine-grained {@code PAYMENTS} UPDATE gating is enforced at the controller layer (Requirement
     * 3.7).
     */
    private PaymentScheduleEntity resolveInScope(Long scheduleId) {
        PaymentScheduleEntity schedule = scheduleId == null ? null
                : paymentScheduleDao.findById(scheduleId).orElse(null);
        if (schedule == null) {
            throw notFound(scheduleId);
        }
        if (!isCallerAdmin()) {
            Long userId = callerUserId();
            Set<Long> allowed = userId == null ? null : projectAccessCache.get(userId);
            Long projectId = schedule.getProject() == null ? null : schedule.getProject().getId();
            if (projectId == null || allowed == null || !allowed.contains(projectId)) {
                throw notFound(scheduleId);
            }
        }
        return schedule;
    }

    private PaymentTrancheEntity findOwnTranche(PaymentScheduleEntity schedule, Long trancheId) {
        if (trancheId != null) {
            for (PaymentTrancheEntity t : schedule.getTranches()) {
                if (trancheId.equals(t.getId())) {
                    return t;
                }
            }
        }
        throw notFound(trancheId);
    }

    private static ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id);
    }

    /** True iff the authenticated caller carries the exact ADMIN authority. */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return true;
            }
        }
        return false;
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
}
