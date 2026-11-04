package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutAllocationEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkerPayoutServiceExtendedModel;
import com.foremen.service.model.WorkerPayoutServiceModel;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.PayoutDistributor.Distribution;
import com.foremen.service.payroll.PayoutSuggestionService.PayableCandidate;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutAllocationDto;
import com.foremen.service.payroll.dto.PayoutFilter;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;

/**
 * FOR-07-06 (Requirements 5, 6, 7; design §WorkerPayoutService) — the <b>company-wide</b> worker
 * payout lifecycle service. A payout belongs to a worker, not a project: its amount is distributed
 * oldest-first over the worker's unpaid {@code APPROVED} report lines on <b>all</b> projects, the
 * last line touched possibly ending partially paid.
 *
 * <p>Implements the plain {@link AdminService} CRUD plumbing (the FOR-06-11
 * {@code ProjectScopedService} scoping is gone, R14.2): reads are company-wide for every caller the
 * {@code PAYROLL} ABAC grant lets through. The lifecycle:
 * <ul>
 *   <li>{@link #suggest} — validates period + worker, derives {@code isAdvance} via
 *       {@link AdvanceDeriver}, delegates to {@link PayoutSuggestionService} (R4);</li>
 *   <li>{@link #create} — validates (period, channel, amount, worker — in that order, R5.7), derives
 *       {@code isAdvance}, and in <b>one transaction</b> (R5.8): for a regular payout locks the
 *       candidate lines, computes gross / worker-wide credit / applied / suggested on them, saves the
 *       payout ({@code PLANNED}, {@code payoutDate}), distributes {@code amount + advanceApplied} via
 *       {@link PayoutDistributor} and persists the allocations + {@code paid_amount +=} via
 *       {@link AllocationWriter} (R5.2–R5.5, R5.9); an advance uses the planned-basis suggestion,
 *       applies no credit, and allocates nothing (R5.6); audit;</li>
 *   <li>{@link #markPaid} — {@code PLANNED → PAID}, sets {@code paidAt} (R6.1); FOR-07-04
 *       {@link #markPaidFromLedger} does the same from a ledger link. Both publish
 *       {@link WorkerPayoutPaidEvent} (a domain event for the cash ledger, not a notification);</li>
 *   <li>{@link #delete} — {@code PLANNED} only; an advance passes the worker-wide consumed-credit
 *       check (R6.3); a regular payout's allocations are released ({@code paid_amount -=}, R6.2);
 *       audit; hard delete (R6.4);</li>
 *   <li>{@link #list(PayoutFilter)} and {@link #allocations(Long)} — company-wide reads.</li>
 * </ul>
 *
 * <p>The remaining advance credit is never stored: it is folded on read by the pure
 * {@link AdvanceCreditCalculator} over <b>all</b> the worker's payouts (R7.1, R7.2). No notification
 * is emitted (R13).
 */
@Service
@Transactional
public class WorkerPayoutService
        implements AdminService<WorkerPayoutServiceModel, WorkerPayoutServiceExtendedModel,
        WorkerPayoutEntity, Long> {

    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String PERIOD_INVALID = "error.workerPayout.period.invalid";
    static final String CHANNEL_REQUIRED = "error.workerPayout.channel.required";
    static final String AMOUNT_INVALID = "error.workerPayout.amount.invalid";
    static final String WORKER_INVALID = "error.workerPayout.worker.invalid";
    static final String STATUS_ILLEGAL_TRANSITION = "error.workerPayout.status.illegalTransition";
    static final String ADVANCE_ALREADY_APPLIED = "error.workerPayout.advance.alreadyApplied";

    /** Payroll is single-currency; the currency carried by {@link WorkerPayoutPaidEvent} (FOR-07-04 R27.4). */
    public static final String PAYOUT_CURRENCY = "PLN";

    private static final String WORKER_ROLE_CODE = "WORKER";
    private static final int SCALE = 2;

    private final WorkerPayoutDao workerPayoutDao;
    private final WorkerPayoutAllocationDao allocationDao;
    private final UserDao userDao;
    private final PayoutSuggestionService suggestionService;
    private final AllocationWriter allocationWriter;
    private final AdvanceCreditCalculator advanceCreditCalculator;
    private final AdvanceDeriver advanceDeriver;
    private final WorkerPayoutServiceMapper serviceMapper;
    private final WorkerPayoutDtoMapper dtoMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public WorkerPayoutService(WorkerPayoutDao workerPayoutDao,
                               WorkerPayoutAllocationDao allocationDao,
                               UserDao userDao,
                               PayoutSuggestionService suggestionService,
                               AllocationWriter allocationWriter,
                               AdvanceCreditCalculator advanceCreditCalculator,
                               AdvanceDeriver advanceDeriver,
                               WorkerPayoutServiceMapper serviceMapper,
                               WorkerPayoutDtoMapper dtoMapper,
                               AuditLogDao auditLogDao,
                               EntityManager entityManager,
                               ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        this.workerPayoutDao = workerPayoutDao;
        this.allocationDao = allocationDao;
        this.userDao = userDao;
        this.suggestionService = suggestionService;
        this.allocationWriter = allocationWriter;
        this.advanceCreditCalculator = advanceCreditCalculator;
        this.advanceDeriver = advanceDeriver;
        this.serviceMapper = serviceMapper;
        this.dtoMapper = dtoMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
    }

    // --- CRUD plumbing (AdminService) -----------------------------------------------------------

    @Override
    public AdminDao<WorkerPayoutEntity, Long> getDao() {
        return workerPayoutDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<WorkerPayoutEntity, WorkerPayoutServiceModel,
            WorkerPayoutServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<WorkerPayoutEntity> getDaoModelClass() {
        return WorkerPayoutEntity.class;
    }

    // --- Suggest (R4) ---------------------------------------------------------------------------

    /**
     * The worker-wide create-dialog preview (R4). Rejects an invalid period
     * ({@link #PERIOD_INVALID}) and an unknown / non-WORKER worker ({@link #WORKER_INVALID}), both
     * 422 (R4.8), then delegates to {@link PayoutSuggestionService}.
     */
    @Transactional(readOnly = true)
    public PayoutSuggestionDto suggest(Long workerId, LocalDate from, LocalDate to) {
        assertPeriod(from, to);
        assertWorker(workerId);
        boolean advance = advanceDeriver.isAdvance(to, LocalDate.now());
        return suggestionService.suggest(workerId, from, to, advance);
    }

    // --- Create (R5) ----------------------------------------------------------------------------

    /**
     * Creates a {@code PLANNED} worker-wide payout, writing the payout, its allocations, and the
     * {@code paid_amount} updates in one transaction (R5.8).
     *
     * @param req the create payload
     * @return the persisted payout
     */
    @Transactional
    public WorkerPayoutDto create(CreatePayoutRequest req) {
        // R5.7 — validation order: period, channel, amount, worker.
        LocalDate from = req.periodFrom();
        LocalDate to = req.periodTo();
        assertPeriod(from, to);
        if (req.paymentChannel() == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CHANNEL_REQUIRED);
        }
        if (req.amount() != null && req.amount().signum() < 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, AMOUNT_INVALID);
        }
        Long workerId = req.workerId();
        UserEntity worker = assertWorker(workerId);

        LocalDate today = LocalDate.now();
        boolean advance = advanceDeriver.isAdvance(to, today);

        BigDecimal suggested;
        BigDecimal advanceApplied;
        List<PayableCandidate> candidates;
        if (advance) {
            // R5.6 — the FOR-06-11 planned basis; no credit applied, nothing allocated.
            PayoutSuggestionDto preview = suggestionService.suggest(workerId, from, to, true);
            suggested = scale(preview.suggested());
            advanceApplied = scale(BigDecimal.ZERO);
            candidates = List.of();
        } else {
            // R5.8 — lock the candidate lines first; gross / credit are computed on the locked set so
            // the distributed set is exactly the costed one and concurrent creates serialize.
            candidates = suggestionService.payableLines(workerId, to);
            BigDecimal gross = BigDecimal.ZERO;
            for (PayableCandidate c : candidates) {
                gross = gross.add(c.remainder());
            }
            BigDecimal remaining = advanceCreditCalculator.remaining(workerPayoutDao.findByWorker(workerId));
            advanceApplied = advanceCreditCalculator.applied(remaining, gross);       // R5.2
            suggested = advanceCreditCalculator.suggested(gross, remaining);
        }
        BigDecimal amount = req.amount() != null ? scale(req.amount()) : suggested;

        WorkerPayoutEntity payout = new WorkerPayoutEntity();
        payout.setWorker(worker);
        payout.setPayoutDate(req.payoutDate() != null ? req.payoutDate() : today);   // R5.1
        payout.setPeriodFrom(from);
        payout.setPeriodTo(to);
        payout.setPaymentChannel(req.paymentChannel());
        payout.setStatus(PayoutStatus.PLANNED);
        payout.setAdvance(advance);
        payout.setSuggestedAmount(suggested);
        payout.setAmount(amount);
        payout.setAdvanceApplied(advanceApplied);
        payout.setNote(req.note());

        WorkerPayoutEntity saved = workerPayoutDao.save(payout);
        entityManager.flush();

        if (!advance && !candidates.isEmpty()) {
            // R5.3–R5.5 — distribute D = amount + advanceApplied oldest-first; surplus stays unallocated.
            Distribution distribution = PayoutDistributor.distribute(
                    amount.add(advanceApplied),
                    candidates.stream().map(PayableCandidate::toPayableLine).toList());
            Map<Long, WorkReportRoomEntity> linesById = new LinkedHashMap<>();
            for (PayableCandidate c : candidates) {
                linesById.put(c.line().getId(), c.line());
            }
            allocationWriter.write(saved, distribution.allocations(), linesById);
            entityManager.flush();
        }

        saveAudit(null, saved, "CREATE");
        return dtoMapper.toDto(saved);
    }

    // --- Mark paid (R6.1) -----------------------------------------------------------------------

    /**
     * {@code PLANNED → PAID}, sets {@code paidAt}. Any action on a {@code PAID} payout is rejected
     * with {@link #STATUS_ILLEGAL_TRANSITION} / 409 (R6.1).
     */
    @Transactional
    public WorkerPayoutDto markPaid(Long id) {
        WorkerPayoutEntity payout = findPayout(id);
        if (payout.getStatus() != PayoutStatus.PLANNED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
        }
        return transitionToPaid(payout, OffsetDateTime.now(), payout.getPaymentChannel(), false);
    }

    /**
     * FOR-07-04 seam (R16.1, R16.2) — {@code PLANNED → PAID} driven by a confirmed ledger link: sets
     * {@code paidAt} to the ledger line's booking date and {@code paymentChannel} to the line's channel
     * ({@code BANK_TRANSFER} for a bank line, {@code CASH} for a cash line), then publishes
     * {@link WorkerPayoutPaidEvent} with {@code fromLedger = true} so the cash hook creates no duplicate
     * SYSTEM entry (R27.4). Same FOR-07-06 rules as {@link #markPaid}: unknown id → 404, non-PLANNED →
     * {@link #STATUS_ILLEGAL_TRANSITION} / 409; a missing channel → {@link #CHANNEL_REQUIRED} / 422.
     *
     * @param id      the payout id
     * @param paidAt  the ledger line's booking date (start of day, system zone); {@code null} → now
     * @param channel the ledger line's channel mapped to the payout channel
     * @return the paid payout
     */
    @Transactional
    public WorkerPayoutDto markPaidFromLedger(Long id, LocalDate paidAt, PaymentChannel channel) {
        WorkerPayoutEntity payout = findPayout(id);
        if (payout.getStatus() != PayoutStatus.PLANNED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
        }
        if (channel == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CHANNEL_REQUIRED);
        }
        OffsetDateTime at = paidAt == null
                ? OffsetDateTime.now()
                : paidAt.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        return transitionToPaid(payout, at, channel, true);
    }

    /** The shared {@code PLANNED → PAID} write: entity update, audit, and the paid event (R27.4). */
    private WorkerPayoutDto transitionToPaid(WorkerPayoutEntity payout, OffsetDateTime paidAt,
                                             PaymentChannel channel, boolean fromLedger) {
        String before = serializeEntity(payout);
        payout.setStatus(PayoutStatus.PAID);
        payout.setPaidAt(paidAt);
        payout.setPaymentChannel(channel);
        WorkerPayoutEntity saved = workerPayoutDao.save(payout);
        entityManager.flush();
        saveAuditWithSnapshot(before, saved, "UPDATE");
        eventPublisher.publishEvent(new WorkerPayoutPaidEvent(
                saved.getId(), saved.getPaymentChannel(), saved.getPaidAt(), saved.getAmount(),
                PAYOUT_CURRENCY, saved.getWorker() == null ? null : saved.getWorker().getName(),
                saved.getNote(), fromLedger));
        return dtoMapper.toDto(saved);
    }

    // --- Delete (R6.2–R6.4) ---------------------------------------------------------------------

    /**
     * Hard-deletes a {@code PLANNED} payout, reversing its whole effect in one transaction:
     * <ul>
     *   <li>{@code PAID} → {@link #STATUS_ILLEGAL_TRANSITION} / 409 (R6.1);</li>
     *   <li>advance → rejected with {@link #ADVANCE_ALREADY_APPLIED} / 409 when the worker's regular
     *       payouts already consumed more credit than the remaining advances cover (R6.3);</li>
     *   <li>regular → every allocation is released, lowering each line's {@code paid_amount} (R6.2).</li>
     * </ul>
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        WorkerPayoutEntity payout = findPayout(id);
        if (payout.getStatus() != PayoutStatus.PLANNED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
        }
        if (payout.isAdvance()) {
            assertAdvanceNotConsumed(payout);
        } else {
            allocationWriter.release(id);
        }
        saveAudit(payout, null, "DELETE");
        workerPayoutDao.deleteById(id);
        entityManager.flush();
    }

    /** The lifecycle {@code delete} (design §WorkerPayoutService signature). */
    @Transactional
    public void delete(Long id) {
        deleteById(id);
    }

    // --- Reads ----------------------------------------------------------------------------------

    /**
     * The company-wide payout list, optionally narrowed by worker, {@code payoutDate} range, and
     * status; newest first. Not project-filtered (R14.2).
     */
    @Transactional(readOnly = true)
    public List<WorkerPayoutDto> list(PayoutFilter filter) {
        Specification<WorkerPayoutEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter != null) {
                if (filter.workerId() != null) {
                    predicates.add(cb.equal(root.get("worker").get("id"), filter.workerId()));
                }
                if (filter.from() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("payoutDate"), filter.from()));
                }
                if (filter.to() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("payoutDate"), filter.to()));
                }
                if (filter.status() != null) {
                    predicates.add(cb.equal(root.get("status"), filter.status()));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<WorkerPayoutEntity> payouts = workerPayoutDao.findAll(spec,
                Sort.by(Sort.Order.desc("payoutDate"), Sort.Order.desc("id")));
        return payouts == null ? List.of() : dtoMapper.toDtos(payouts);
    }

    /**
     * The allocations of a payout onto report lines, in distribution order (an advance has none).
     * Unknown id → 404.
     */
    @Transactional(readOnly = true)
    public List<PayoutAllocationDto> allocations(Long id) {
        findPayout(id);
        boolean ru = isRussianLocale();
        List<PayoutAllocationDto> result = new ArrayList<>();
        for (WorkerPayoutAllocationEntity a : allocationDao.findByPayoutId(id)) {
            WorkReportRoomEntity line = a.getWorkReportRoom();
            WorkReportEntity report = line == null ? null : line.getReport();
            ProjectEntity project = report == null ? null : report.getProject();
            result.add(new PayoutAllocationDto(
                    line == null ? null : line.getId(),
                    project == null ? null : project.getId(),
                    project == null ? null : project.getName(),
                    report == null ? null : workItemName(report.getWorkItem(), ru),
                    line == null ? null : roomName(line.getRoom(), ru),
                    a.getAmount()));
        }
        return result;
    }

    // --- Validation helpers ---------------------------------------------------------------------

    private static void assertPeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PERIOD_INVALID);
        }
    }

    /** R4.8 / R5.7 — the worker must be an existing user with the {@code WORKER} role. */
    private UserEntity assertWorker(Long workerId) {
        UserEntity user = workerId == null ? null : userDao.findById(workerId).orElse(null);
        RoleEntity role = user == null ? null : user.getRole();
        if (role == null || role.getCode() == null
                || !WORKER_ROLE_CODE.equalsIgnoreCase(role.getCode().trim())) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, WORKER_INVALID);
        }
        return user;
    }

    private WorkerPayoutEntity findPayout(Long id) {
        return workerPayoutDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
    }

    /**
     * R6.3 — worker-wide: without this advance, the worker's regular payouts' Σ {@code advanceApplied}
     * must not exceed Σ amounts of the remaining advances; otherwise its credit was already consumed.
     */
    private void assertAdvanceNotConsumed(WorkerPayoutEntity advance) {
        Long workerId = advance.getWorker() == null ? null : advance.getWorker().getId();
        BigDecimal otherAdvances = BigDecimal.ZERO;
        BigDecimal appliedByRegulars = BigDecimal.ZERO;
        for (WorkerPayoutEntity p : workerPayoutDao.findByWorker(workerId)) {
            if (p == null || (p.getId() != null && p.getId().equals(advance.getId()))) {
                continue;
            }
            if (p.isAdvance()) {
                otherAdvances = otherAdvances.add(nz(p.getAmount()));
            } else {
                appliedByRegulars = appliedByRegulars.add(nz(p.getAdvanceApplied()));
            }
        }
        if (appliedByRegulars.compareTo(otherAdvances) > 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ADVANCE_ALREADY_APPLIED);
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // --- localized-name helpers (ru → pl → code) ------------------------------------------------

    private static String workItemName(WorkItemEntity workItem, boolean ru) {
        if (workItem == null) {
            return null;
        }
        return firstNonBlank(
                ru ? workItem.getNameRU() : workItem.getNamePL(),
                ru ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    private static String roomName(RoomEntity room, boolean ru) {
        if (room == null) {
            return null;
        }
        if (room.getLabel() != null && !room.getLabel().isBlank()) {
            return room.getLabel();
        }
        RoomTypeEntity type = room.getRoomType();
        if (type == null) {
            return null;
        }
        return firstNonBlank(
                ru ? type.getNameRU() : type.getNamePL(),
                ru ? type.getNamePL() : type.getNameRU(),
                type.getCode());
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
