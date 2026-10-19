package com.foremen.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PaymentScheduleServiceExtendedModel;
import com.foremen.service.model.PaymentScheduleServiceModel;
import com.foremen.service.model.mapper.PaymentScheduleServiceMapper;
import com.foremen.service.payment.dto.CreateScheduleRequest;
import com.foremen.service.payment.dto.GenerateRequest;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.RePlanRequest;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-07 (Requirements 1, 2.6, 4, 6; design §PaymentScheduleService): the project-scoped service
 * that owns the {@code PaymentSchedule} lifecycle — create (seed from the APPROVED offer), run the
 * tranche generator, publish, re-plan the unpaid remainder, and read the aggregate view.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code OfferService} /
 * {@code ProjectScheduleService}), it implements exactly one CRUD contract —
 * {@link ProjectScopedService} — supplies the standard CRUD plumbing ({@link #getDao()},
 * {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override {@link #getProjectIdPath()}
 * &rarr; {@code "project.id"} (entity-creation-rules step 4; Requirement 11.1 — the schedule resolves
 * its project boundary through its {@code @OneToOne project} FK), and wires
 * {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}.
 *
 * <p><b>Custom API surface.</b> The schedule is created, generated, published, re-planned, and read
 * through the dedicated lifecycle methods below (not through the generic by-id CRUD), each gating
 * project access up front with {@link #loadAccessibleProject(Long)} / {@link #resolveInScope(Long)}
 * rather than the inherited {@code assertProjectAccess(id)}. The flat service models and mapper exist
 * only to satisfy the generic CRUD contract.
 *
 * <p><b>Pure collaborators.</b> All invariant arithmetic/ordering/derivation lives in the pure,
 * persistence-free {@link TrancheGenerator} (sum-exact split, R2) and {@link TrancheStatusDeriver}
 * (status derivation, R10); this service is a thin apply-layer over them — it never hand-sets a
 * status (R10.3).
 *
 * <p><b>Localized error codes</b> referenced here (added to the message bundle by task 10.1):
 * {@code error.payment.schedule.noProjectTotal} (422), {@code error.payment.schedule.exists} (409),
 * {@code error.payment.schedule.locked} (409), {@code error.payment.schedule.sumMismatch} (422),
 * {@code error.payment.schedule.cannotUnpublish} (409).
 */
@Service
@Transactional
public class PaymentScheduleService
        implements ProjectScopedService<PaymentScheduleServiceModel,
        PaymentScheduleServiceExtendedModel, PaymentScheduleEntity, Long> {

    /**
     * 404 for a missing <em>or</em> a non-accessible project / schedule. The same code and body are
     * used for both so an out-of-scope entity is indistinguishable from a non-existent one
     * (Requirement 11.3), matching the {@code ProjectScopedService} access-denied convention.
     */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 422 when a schedule create targets a project with no resolvable APPROVED-offer total (R1.3). */
    static final String NO_PROJECT_TOTAL_MESSAGE = "error.payment.schedule.noProjectTotal";

    /** 409 when a second schedule is created for a project that already has one (R1.1, UNIQUE). */
    static final String SCHEDULE_EXISTS_MESSAGE = "error.payment.schedule.exists";

    /** 409 when a structural edit (generate / re-plan on a non-eligible status) hits a locked schedule (R1.5). */
    static final String SCHEDULE_LOCKED_MESSAGE = "error.payment.schedule.locked";

    /** 422 when publish is attempted with {@code Σ amountGross != basisGross} or zero tranches (R3.5, R4.2). */
    static final String SUM_MISMATCH_MESSAGE = "error.payment.schedule.sumMismatch";

    /** 409 when un-publishing a schedule that already has a CONFIRMED allocated transaction (R4.4). */
    static final String CANNOT_UNPUBLISH_MESSAGE = "error.payment.schedule.cannotUnpublish";

    /** The fallback schedule currency when the project has no estimate currency (matches the estimate PLN default). */
    static final String DEFAULT_CURRENCY = "PLN";

    private static final int SCALE = 2;

    private final PaymentScheduleDao paymentScheduleDao;
    private final PaymentTransactionDao paymentTransactionDao;
    private final ProjectDao projectDao;
    private final EstimateDao estimateDao;
    private final OfferService offerService;
    private final TrancheGenerator trancheGenerator;
    private final TrancheStatusDeriver trancheStatusDeriver;
    private final PaymentDtoMapper paymentDtoMapper;
    private final PaymentScheduleServiceMapper serviceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentScheduleService(PaymentScheduleDao paymentScheduleDao,
                                  PaymentTransactionDao paymentTransactionDao,
                                  ProjectDao projectDao,
                                  EstimateDao estimateDao,
                                  OfferService offerService,
                                  TrancheGenerator trancheGenerator,
                                  TrancheStatusDeriver trancheStatusDeriver,
                                  PaymentDtoMapper paymentDtoMapper,
                                  PaymentScheduleServiceMapper serviceMapper,
                                  ProjectAccessCache projectAccessCache,
                                  AuditLogDao auditLogDao,
                                  EntityManager entityManager,
                                  ApplicationEventPublisher eventPublisher) {
        this.paymentScheduleDao = paymentScheduleDao;
        this.paymentTransactionDao = paymentTransactionDao;
        this.projectDao = projectDao;
        this.estimateDao = estimateDao;
        this.offerService = offerService;
        this.trancheGenerator = trancheGenerator;
        this.trancheStatusDeriver = trancheStatusDeriver;
        this.paymentDtoMapper = paymentDtoMapper;
        this.serviceMapper = serviceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<PaymentScheduleEntity, Long> getDao() {
        return paymentScheduleDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<PaymentScheduleEntity, PaymentScheduleServiceModel,
            PaymentScheduleServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<PaymentScheduleEntity> getDaoModelClass() {
        return PaymentScheduleEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override (entity-creation-rules step 4; Requirement 11.1). The
     * schedule resolves its project boundary through its {@code @OneToOne project} association, so the
     * project-id path is the dotted {@code "project.id"}.
     */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Lifecycle: create (R1.1, R1.2, R1.3) ---

    /**
     * Creates a project's payment schedule in {@code DRAFT}, seeding {@code basisGross} from the
     * project's <b>APPROVED offer total</b> ({@code Offer.totalGross} / {@code OFFER_BASE}) and
     * {@code currency} from the project's estimate (R1.2).
     *
     * <ol>
     *   <li>Resolves + access-gates the project (404 for a missing / out-of-scope project, R11.3).</li>
     *   <li>Rejects a second schedule: the UNIQUE {@code project_id} constraint allows at most one, so
     *       an existing schedule is {@code 409 error.payment.schedule.exists} (R1.1).</li>
     *   <li>Resolves the APPROVED offer total; if the project has no resolvable total the create is
     *       rejected {@code 422 error.payment.schedule.noProjectTotal} and nothing is persisted
     *       (R1.3).</li>
     *   <li>Persists {@code PaymentSchedule{status=DRAFT, basisGross=<offer total>, currency=<project
     *       currency>, source=<req.source or MANUAL>, unallocatedCredit=0}} with no tranches.</li>
     * </ol>
     *
     * @param req the create payload (owning {@code projectId}; optional {@code source})
     * @return the created schedule view (DRAFT, no tranches)
     * @throws ForemenApiException 404 when the project is missing / out of scope; 409 when a schedule
     *                             already exists; 422 when no APPROVED-offer total is resolvable
     */
    public PaymentScheduleView create(CreateScheduleRequest req) {
        Long projectId = req == null ? null : req.projectId();
        ProjectEntity project = loadAccessibleProject(projectId);

        // R1.1: at most one schedule per project (UNIQUE project_id).
        if (paymentScheduleDao.findByProjectId(projectId).isPresent()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_EXISTS_MESSAGE);
        }

        // R1.2/R1.3: seed basisGross from the project's APPROVED offer total, else reject with no-total.
        BigDecimal basisGross = resolveApprovedOfferTotal(projectId);

        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setProject(project);
        schedule.setCurrency(resolveCurrency(projectId));
        schedule.setBasisGross(scale(basisGross));
        schedule.setSource(req != null && req.source() != null ? req.source() : ScheduleSource.MANUAL);
        schedule.setStatus(ScheduleStatus.DRAFT);
        schedule.setUnallocatedCredit(BigDecimal.ZERO);

        PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);
        entityManager.flush();
        return toView(saved);
    }

    // --- Lifecycle: generate (R2, R2.6, R1.5) ---

    /**
     * Runs the tranche generator on a {@code DRAFT} schedule, <b>replacing</b> any existing (all
     * unpaid in {@code DRAFT}) tranches with the newly generated set (R2.6). The pure
     * {@link TrancheGenerator} splits {@code basisGross} by the request's strategy/periodicity so the
     * generated set sums to exactly {@code basisGross} with contiguous {@code seqNo} {@code 1..N}
     * (R2.1–2.5). A non-{@code DRAFT} schedule is rejected {@code 409
     * error.payment.schedule.locked} — the generator is a full structural edit, permitted only while
     * {@code DRAFT} (R1.5).
     *
     * @param scheduleId the schedule to (re-)generate
     * @param in         the generator spec (strategy, parts, start date, periodicity, custom dates)
     * @return the regenerated schedule view
     * @throws ForemenApiException 404 when the schedule is missing / out of scope; 409 when the
     *                             schedule is not {@code DRAFT}; 422 on invalid generator input
     */
    public PaymentScheduleView generate(Long scheduleId, GenerateRequest in) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        // R1.5: the generator is a full structural edit — DRAFT only.
        if (schedule.getStatus() != ScheduleStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_LOCKED_MESSAGE);
        }

        List<BigDecimal> parts = resolveParts(in);
        List<GeneratedTranche> generated = trancheGenerator.generate(
                schedule.getBasisGross(),
                in == null ? null : in.strategy(),
                parts,
                in == null ? null : in.startDate(),
                in == null ? null : in.periodicity(),
                in == null ? null : in.customDates());

        // R2.6: replace any existing (all-unpaid) tranches with the generated set. orphanRemoval=true
        // removes the old rows on flush.
        schedule.getTranches().clear();
        for (GeneratedTranche gt : generated) {
            schedule.getTranches().add(newTranche(schedule, gt.seqNo(), gt.amountGross(), gt.dueDate()));
        }

        rederiveStatuses(schedule);
        PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);
        entityManager.flush();
        return toView(saved);
    }

    // --- Lifecycle: publish (R4.1, R4.2, R4.4, R3.5) ---

    /**
     * Publishes a reconciling {@code DRAFT} schedule: asserts {@code Σ amountGross == basisGross} and
     * at least one tranche, then transitions {@code DRAFT → PUBLISHED} (R4.1). A sum mismatch or a
     * zero-tranche schedule is rejected {@code 422 error.payment.schedule.sumMismatch} (R3.5, R4.2).
     * Re-publishing an already-{@code PUBLISHED}/{@code SETTLED} schedule is idempotent (no-op return
     * of the current view); it is never implicitly un-published.
     *
     * <p>The downstream CLIENT {@code schedulePublished} notification (R4.3) is emitted by the
     * best-effort {@code PaymentNotificationEmitter} wired in a later task; this method owns only the
     * state transition + invariant.
     *
     * @param scheduleId the schedule to publish
     * @return the published schedule view
     * @throws ForemenApiException 404 when the schedule is missing / out of scope; 422 when the
     *                             tranche sum does not reconcile or there are no tranches
     */
    public PaymentScheduleView publish(Long scheduleId) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);

        // Idempotent: an already-published (or settled) schedule stays published.
        if (schedule.getStatus() != ScheduleStatus.DRAFT) {
            rederiveStatuses(schedule);
            return toView(schedule);
        }

        assertReconciles(schedule); // R3.5/R4.2: Σ amountGross == basisGross AND >= 1 tranche.

        schedule.setStatus(ScheduleStatus.PUBLISHED);
        rederiveStatuses(schedule); // may roll straight to SETTLED if every tranche is already PAID.
        PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);
        entityManager.flush();

        // R4.3 / R9.2 row 5: emit the CLIENT schedulePublished notification only on the real
        // DRAFT -> PUBLISHED transition (the idempotent re-publish path returned above). Staged for
        // the AFTER_COMMIT PaymentNotificationEmitter (task 5.6), which never rolls back publish (R9.5).
        Long projectId = saved.getProject() == null ? null : saved.getProject().getId();
        eventPublisher.publishEvent(new SchedulePublishedEvent(saved.getId(), projectId));
        return toView(saved);
    }

    // --- Lifecycle: re-plan (R6) ---

    /**
     * Re-plans the unpaid remainder of a {@code PUBLISHED}/{@code SETTLED} schedule after an amendment
     * changes the project total (R6). It recomputes {@code basisGross} to {@code newBasisGross} and
     * re-authors ONLY the still-open (not {@code PAID}) tranches so their amounts re-sum to the unpaid
     * remainder ({@code newBasisGross − Σ paidGross}); every {@code PAID} tranche and its allocations
     * stay immutable (R6.1, R6.2) and the schedule stays {@code PUBLISHED} (R6.4).
     *
     * <p>When the new remainder is negative (the client has already overpaid relative to the reduced
     * total) the open tranches are closed to zero and the excess is surfaced as
     * {@code unallocatedCredit} (R6.3). When {@link RePlanRequest#generate()} carries a fresh
     * generator spec the open tranches are regenerated from the remainder; otherwise the existing open
     * tranches are re-proportioned in place to the remainder (keeping their count/due dates). A
     * {@code DRAFT} schedule is rejected {@code 409 error.payment.schedule.locked} — re-plan operates
     * on a published schedule (a {@code DRAFT} is edited through the generator / manual scheduler).
     *
     * @param scheduleId the schedule to re-plan
     * @param in         the re-plan input (new project total; optional generator spec for the open set)
     * @return the re-planned schedule view (still {@code PUBLISHED})
     * @throws ForemenApiException 404 when the schedule is missing / out of scope; 409 when the
     *                             schedule is {@code DRAFT}; 422 on invalid generator input
     */
    public PaymentScheduleView rePlan(Long scheduleId, RePlanRequest in) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        // Re-plan is a PUBLISHED-stage operation; a DRAFT schedule is structurally edited instead.
        if (schedule.getStatus() == ScheduleStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SCHEDULE_LOCKED_MESSAGE);
        }

        BigDecimal newBasis = scale(in == null || in.newBasisGross() == null
                ? schedule.getBasisGross() : in.newBasisGross());
        schedule.setBasisGross(newBasis);

        BigDecimal paidSum = sumPaid(schedule);
        BigDecimal remainder = newBasis.subtract(paidSum);

        List<PaymentTrancheEntity> paid = new ArrayList<>();
        List<PaymentTrancheEntity> open = new ArrayList<>();
        for (PaymentTrancheEntity tranche : schedule.getTranches()) {
            if (isPaid(tranche)) {
                paid.add(tranche); // R6.2: PAID tranches + their allocations are immutable.
            } else {
                open.add(tranche);
            }
        }

        if (remainder.signum() <= 0) {
            // R6.3: already overpaid relative to the reduced total — close every open tranche to zero
            // and surface the (non-negative) excess as unallocated credit.
            reauthorOpenToZero(schedule, open);
            BigDecimal excess = paidSum.subtract(newBasis).max(BigDecimal.ZERO);
            schedule.setUnallocatedCredit(scale(excess));
        } else if (in != null && in.generate() != null) {
            // Regenerate the open set from the remainder via the pure generator.
            regenerateOpen(schedule, open, remainder, in.generate());
        } else {
            // Re-proportion the existing open tranches in place to the new remainder (keep due dates).
            reproportionOpen(open, remainder);
        }

        resequence(schedule);
        rederiveStatuses(schedule); // stays PUBLISHED (or rolls to SETTLED if all now PAID) — R6.4.
        PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);
        entityManager.flush();
        return toView(saved);
    }

    // --- Read (R1.4) ---

    /**
     * The aggregate {@link PaymentScheduleView} of a schedule — header, ordered tranches, and the
     * derived aggregates (R1.4). Tranche statuses are re-derived on read so overdue state reflects
     * the current clock (R10.2) without a write.
     *
     * @param scheduleId the schedule id
     * @return the schedule view
     * @throws ForemenApiException 404 when the schedule is missing / out of scope
     */
    @Transactional(readOnly = true)
    public PaymentScheduleView get(Long scheduleId) {
        PaymentScheduleEntity schedule = resolveInScope(scheduleId);
        return toViewReadOnly(schedule);
    }

    // --- Un-publish guard (R4.4) ---

    /**
     * Asserts a schedule may be un-published / reverted out of {@code PUBLISHED}: it refuses once any
     * {@code CONFIRMED} transaction has an allocation against the schedule (R4.4), throwing {@code 409
     * error.payment.schedule.cannotUnpublish}. The reusable guard that any revert path (added by a
     * later task) must call before moving a schedule back out of {@code PUBLISHED}; {@link #publish}
     * itself never un-publishes.
     *
     * @param schedule the schedule about to be reverted; must be non-null
     * @throws ForemenApiException 409 {@code error.payment.schedule.cannotUnpublish} when a CONFIRMED
     *                             allocation exists
     */
    public void assertCanUnpublish(PaymentScheduleEntity schedule) {
        boolean hasConfirmedAllocation = paymentTransactionDao
                .findByScheduleIdOrderByIdAsc(schedule.getId()).stream()
                .anyMatch(txn -> txn.getStatus() == TransactionStatus.CONFIRMED
                        && txn.getAllocations() != null && !txn.getAllocations().isEmpty());
        if (hasConfirmedAllocation) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CANNOT_UNPUBLISH_MESSAGE);
        }
    }

    // --- internal: schedule mutation helpers ---

    /** Builds a new managed tranche attached to {@code schedule}; paidGross 0, status derived later. */
    private static PaymentTrancheEntity newTranche(PaymentScheduleEntity schedule, int seqNo,
                                                   BigDecimal amountGross, java.time.LocalDate dueDate) {
        PaymentTrancheEntity tranche = new PaymentTrancheEntity();
        tranche.setSchedule(schedule);
        tranche.setSeqNo(seqNo);
        tranche.setDueDate(dueDate);
        tranche.setAmountGross(scale(amountGross));
        tranche.setPaidGross(BigDecimal.ZERO);
        tranche.setStatus(TrancheStatus.PENDING);
        return tranche;
    }

    /** R6.3: close every open tranche to a zero amount (paid part preserved; already-zero paid on open). */
    private static void reauthorOpenToZero(PaymentScheduleEntity schedule,
                                           List<PaymentTrancheEntity> open) {
        // A positive amount is required by the DB CHECK (> 0); closing means removing the open tranche
        // entirely since it carries no settled amount in the overpaid case.
        schedule.getTranches().removeAll(open);
    }

    /** Regenerate the open set from the unpaid remainder via the pure generator (R6.1). */
    private void regenerateOpen(PaymentScheduleEntity schedule, List<PaymentTrancheEntity> open,
                                BigDecimal remainder, GenerateRequest spec) {
        List<GeneratedTranche> generated = trancheGenerator.generate(
                remainder, spec.strategy(), resolveParts(spec),
                spec.startDate(), spec.periodicity(), spec.customDates());
        schedule.getTranches().removeAll(open);
        for (GeneratedTranche gt : generated) {
            schedule.getTranches().add(newTranche(schedule, gt.seqNo(), gt.amountGross(), gt.dueDate()));
        }
    }

    /**
     * Re-proportion the existing open tranches so they re-sum to {@code remainder} (R6.1): split
     * proportionally to their current amounts (equal split when their current total is zero), 2-decimal
     * rounded with the remainder pushed onto the last open tranche so the open set sums exactly.
     */
    private static void reproportionOpen(List<PaymentTrancheEntity> open, BigDecimal remainder) {
        if (open.isEmpty()) {
            return;
        }
        open.sort(Comparator.comparing(PaymentTrancheEntity::getSeqNo,
                Comparator.nullsLast(Comparator.naturalOrder())));
        BigDecimal target = scale(remainder);
        BigDecimal currentTotal = BigDecimal.ZERO;
        for (PaymentTrancheEntity t : open) {
            currentTotal = currentTotal.add(t.getAmountGross() == null ? BigDecimal.ZERO : t.getAmountGross());
        }

        int n = open.size();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            PaymentTrancheEntity t = open.get(i);
            BigDecimal amount;
            if (i == n - 1) {
                amount = target.subtract(running); // remainder onto the last so the set sums exactly.
            } else if (currentTotal.signum() == 0) {
                amount = target.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.HALF_UP);
            } else {
                amount = target.multiply(t.getAmountGross()).divide(currentTotal, SCALE, RoundingMode.HALF_UP);
            }
            t.setAmountGross(amount);
            running = running.add(amount);
        }
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

    // --- internal: validation + resolution helpers ---

    /** R3.5/R4.2: assert at least one tranche and {@code Σ amountGross == basisGross}, else 422. */
    private void assertReconciles(PaymentScheduleEntity schedule) {
        List<PaymentTrancheEntity> tranches = schedule.getTranches();
        if (tranches == null || tranches.isEmpty()) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, SUM_MISMATCH_MESSAGE);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PaymentTrancheEntity t : tranches) {
            sum = sum.add(t.getAmountGross() == null ? BigDecimal.ZERO : t.getAmountGross());
        }
        if (scale(sum).compareTo(scale(schedule.getBasisGross())) != 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, SUM_MISMATCH_MESSAGE);
        }
    }

    /**
     * Resolves the project's APPROVED offer gross total (R1.2) — the schedule's {@code basisGross}.
     * Delegates to {@link OfferService#agreedOfferViewByProject(Long)}; when the project has no
     * {@code APPROVED} offer (the service throws 404) or the resolved total is null/negative, the
     * create is rejected {@code 422 error.payment.schedule.noProjectTotal} (R1.3).
     */
    private BigDecimal resolveApprovedOfferTotal(Long projectId) {
        BigDecimal total;
        try {
            total = offerService.agreedOfferViewByProject(projectId).totalGross();
        } catch (ForemenApiException e) {
            // No APPROVED offer resolvable -> no project total to schedule against (R1.3).
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, NO_PROJECT_TOTAL_MESSAGE);
        }
        if (total == null || total.signum() < 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, NO_PROJECT_TOTAL_MESSAGE);
        }
        return total;
    }

    /** Resolves the schedule currency from the project's estimate currency code, defaulting to PLN (R1.2). */
    private String resolveCurrency(Long projectId) {
        EstimateEntity estimate = estimateDao.findByProjectId(projectId).orElse(null);
        CurrencyEntity currency = estimate == null ? null : estimate.getCurrency();
        String code = currency == null ? null : currency.getCode();
        return code == null || code.isBlank() ? DEFAULT_CURRENCY : code;
    }

    /**
     * The generator {@code parts} for a {@link GenerateRequest}: for {@code EQUAL} synthesize a
     * {@code count}-sized placeholder list (the generator reads only its size for N); otherwise pass
     * the explicit percentages/amounts through.
     */
    private static List<BigDecimal> resolveParts(GenerateRequest in) {
        if (in == null) {
            return null;
        }
        if (in.strategy() == GeneratorStrategy.EQUAL) {
            int n = in.count() == null ? 0 : in.count();
            List<BigDecimal> placeholder = new ArrayList<>(Math.max(n, 0));
            for (int i = 0; i < n; i++) {
                placeholder.add(BigDecimal.ZERO);
            }
            return placeholder;
        }
        return in.parts();
    }

    private static BigDecimal sumPaid(PaymentScheduleEntity schedule) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            sum = sum.add(t.getPaidGross() == null ? BigDecimal.ZERO : t.getPaidGross());
        }
        return scale(sum);
    }

    private static boolean isPaid(PaymentTrancheEntity tranche) {
        BigDecimal paid = tranche.getPaidGross() == null ? BigDecimal.ZERO : tranche.getPaidGross();
        BigDecimal amount = tranche.getAmountGross() == null ? BigDecimal.ZERO : tranche.getAmountGross();
        return paid.compareTo(amount) >= 0;
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Re-derive statuses (writing the cache) then map to the view. */
    private PaymentScheduleView toView(PaymentScheduleEntity schedule) {
        rederiveStatuses(schedule);
        return paymentDtoMapper.toView(schedule);
    }

    /**
     * Read-only view assembly: derive statuses onto the managed entity (so the view reflects the
     * current clock) within the read transaction; the write-back of the status cache is flushed by the
     * surrounding transaction if dirty.
     */
    private PaymentScheduleView toViewReadOnly(PaymentScheduleEntity schedule) {
        rederiveStatuses(schedule);
        return paymentDtoMapper.toView(schedule);
    }

    // --- Project / schedule access gate (R11.1, R11.3) ---

    /**
     * Loads the managed {@link ProjectEntity} for {@code projectId}, asserting the caller may access
     * it — the entry gate for {@link #create(CreateScheduleRequest)} (whose subject is a
     * {@code projectId}, not a schedule id). Mirrors {@code ProjectScheduleService.loadAccessibleProject}:
     * an ADMIN authority bypasses the gate; a non-ADMIN caller must have {@code projectId} in
     * {@link #allowedProjectIds(Long)}. Both a missing and an out-of-scope project yield an identical
     * {@code 404 error.entity.not.found} so they are indistinguishable (R11.3).
     */
    @Transactional(readOnly = true)
    public ProjectEntity loadAccessibleProject(Long projectId) {
        if (!isCallerAdmin()) {
            Long userId = callerUserId();
            Set<Long> allowed = userId == null ? null : allowedProjectIds(userId);
            if (projectId == null || allowed == null || !allowed.contains(projectId)) {
                throw notFound(projectId);
            }
        }
        return projectDao.findById(projectId).orElseThrow(() -> notFound(projectId));
    }

    /**
     * Loads the managed schedule by id and asserts the caller may access its owning project — the
     * entry gate for {@link #generate}/{@link #publish}/{@link #rePlan}/{@link #get} (whose subject is
     * a schedule id). Reuses the inherited {@link #assertProjectAccess(Object)} decision (ADMIN bypass;
     * otherwise the schedule's {@code project.id} must be in the caller's allowed set), with the same
     * {@code 404 error.entity.not.found} for a missing and an out-of-scope schedule (R11.3).
     */
    private PaymentScheduleEntity resolveInScope(Long scheduleId) {
        assertProjectAccess(scheduleId);
        return paymentScheduleDao.findById(scheduleId).orElseThrow(() -> notFound(scheduleId));
    }

    private static ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id);
    }

    /** True iff the authenticated caller carries the exact ADMIN authority ({@code ROLE_ADMIN}/{@code ADMIN}). */
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
