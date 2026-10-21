package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkerPayoutServiceExtendedModel;
import com.foremen.service.model.WorkerPayoutServiceModel;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.PayrollFilter;
import com.foremen.service.payroll.dto.PayrollView;
import com.foremen.service.payroll.dto.WorkerCreditDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-11 (Requirements 1.4, 4.x, 5.x, 6.3, 7.x, 10.2, 11.1; design §WorkerPayoutService) — the
 * lifecycle service behind payroll. It is the money-OUT counterpart of the FOR-06-07 client payment:
 * a FINANCIER / MANAGER / ADMIN issues a {@code WorkerPayout} to a worker for the work performed (or,
 * for an advance, to be performed) on an {@code ACTIVE} / {@code COMPLETED} project, then marks it
 * paid.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@link com.foremen.service.WorkReportService}),
 * it implements exactly one CRUD contract — {@link ProjectScopedService} — supplies the standard
 * CRUD plumbing ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()},
 * {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single mandatory per-entity override
 * {@link #getProjectIdPath()} &rarr; {@code "project.id"} (entity-creation-rules step 4; R10.2), and
 * wires {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache} (R10.5). Every inherited LIST
 * read is project-filtered and every by-id read/mutation runs the per-id project-access guard.
 *
 * <p>Beyond the generic CRUD it owns the payout lifecycle, delegating the input-varying arithmetic to
 * pure collaborators (the cost is <b>never</b> re-derived — the gross folds FOR-06-10's
 * {@code Cost_Value} verbatim via {@link PayoutSuggestionService}, Non-Goal / R3.1):
 * <ul>
 *   <li>{@link #suggest} — the create-dialog preview; derives {@code isAdvance} via
 *       {@link AdvanceDeriver} and delegates to {@link PayoutSuggestionService} (R3);</li>
 *   <li>{@link #create} — validates (period R4.5, channel R2.2, amount R4.4, ACTIVE membership
 *       R4.6), derives {@code isAdvance} (R5.3), snapshots {@code suggestedAmount}, defaults
 *       {@code amount} to it (R1.4), and — in <b>one transaction</b> — for a <b>regular</b> payout
 *       records {@code advanceApplied = min(remaining, gross)} and links every covered unpaid
 *       {@code APPROVED} line via {@link ReportLineLinker} (R4.2, R4.3, R6.1); for an <b>advance</b>
 *       records {@code advanceApplied = 0}, links no line, and lets its amount increase the running
 *       credit (R5.5);</li>
 *   <li>{@link #markPaid} — {@code PLANNED → PAID}, sets {@code paidAt}, freezes the record (R7.1,
 *       R7.2);</li>
 *   <li>{@link #delete} — hard-deletes a {@code PLANNED} payout, releasing its linked lines via
 *       {@link ReportLineLinker} and reversing its credit effect; a {@code PAID} delete and an
 *       advance whose credit a later regular payout already consumed are rejected (R5.7, R7.2,
 *       R7.3);</li>
 *   <li>{@link #list} — the {@code PayrollView} (payouts + per-worker remaining advance credit,
 *       R11.1, R5.8).</li>
 * </ul>
 *
 * <p>The remaining advance credit is never stored: it is folded on read by the pure
 * {@link AdvanceCreditCalculator} over the worker's payouts, so an advance create (amount added), a
 * regular create ({@code advanceApplied} subtracted), and a delete (either reversed) all move the
 * balance implicitly through the persisted {@code isAdvance} / {@code amount} / {@code advanceApplied}
 * columns — there is no separate balance row to drift (R5.6). This service emits <b>no</b>
 * notifications (R9.1).
 */
@Service
@Transactional
public class WorkerPayoutService
        implements ProjectScopedService<WorkerPayoutServiceModel, WorkerPayoutServiceExtendedModel,
        WorkerPayoutEntity, Long> {

    // --- Message codes (bundle wiring is task 10.1) ---
    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String PERIOD_INVALID = "error.workerPayout.period.invalid";
    static final String CHANNEL_REQUIRED = "error.workerPayout.channel.required";
    static final String AMOUNT_INVALID = "error.workerPayout.amount.invalid";
    static final String WORKER_NOT_MEMBER = "error.workerPayout.worker.notMember";
    static final String STATUS_ILLEGAL_TRANSITION = "error.workerPayout.status.illegalTransition";
    static final String ADVANCE_ALREADY_APPLIED = "error.workerPayout.advance.alreadyApplied";

    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final WorkerPayoutDao workerPayoutDao;
    private final WorkReportRoomDao workReportRoomDao;
    private final ProjectMemberDao projectMemberDao;
    private final ProjectAccessCache projectAccessCache;
    private final PayoutSuggestionService suggestionService;
    private final ReportLineLinker reportLineLinker;
    private final AdvanceCreditCalculator advanceCreditCalculator;
    private final AdvanceDeriver advanceDeriver;
    private final WorkerPayoutServiceMapper serviceMapper;
    private final WorkerPayoutDtoMapper dtoMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    public WorkerPayoutService(WorkerPayoutDao workerPayoutDao,
                               WorkReportRoomDao workReportRoomDao,
                               ProjectMemberDao projectMemberDao,
                               ProjectAccessCache projectAccessCache,
                               PayoutSuggestionService suggestionService,
                               ReportLineLinker reportLineLinker,
                               AdvanceCreditCalculator advanceCreditCalculator,
                               AdvanceDeriver advanceDeriver,
                               WorkerPayoutServiceMapper serviceMapper,
                               WorkerPayoutDtoMapper dtoMapper,
                               AuditLogDao auditLogDao,
                               EntityManager entityManager) {
        this.workerPayoutDao = workerPayoutDao;
        this.workReportRoomDao = workReportRoomDao;
        this.projectMemberDao = projectMemberDao;
        this.projectAccessCache = projectAccessCache;
        this.suggestionService = suggestionService;
        this.reportLineLinker = reportLineLinker;
        this.advanceCreditCalculator = advanceCreditCalculator;
        this.advanceDeriver = advanceDeriver;
        this.serviceMapper = serviceMapper;
        this.dtoMapper = dtoMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

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

    // --- ProjectScopedService overrides ---

    /** The single mandatory per-entity override (entity-creation-rules step 4; R10.2). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache (R10.5). */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Suggest (R3) --------------------------------------------------------------------------

    /**
     * The create-dialog preview for a worker + period (R3). Derives {@code isAdvance} from
     * {@code to} vs. {@code today} via {@link AdvanceDeriver} (R5.3) and delegates to
     * {@link PayoutSuggestionService#suggest(Long, Long, LocalDate, LocalDate, boolean)} — a
     * non-advance period folds {@code Σ Cost_Value} over the worker's unpaid {@code APPROVED} lines
     * and applies the credit arithmetic (R3.1–R3.4); an advance period uses the planned basis with no
     * credit deduction (R3.5). Project scope is enforced before the read (R10.5).
     *
     * @param projectId the owning project id (must be in the caller's accessible set unless ADMIN)
     * @param workerId  the worker the preview is for
     * @param from      the period start (inclusive)
     * @param to        the period end (inclusive)
     * @return the {@link PayoutSuggestionDto} preview; never {@code null}
     */
    @Transactional(readOnly = true)
    public PayoutSuggestionDto suggest(Long projectId, Long workerId, LocalDate from, LocalDate to) {
        assertProjectRead(projectId);
        boolean advance = advanceDeriver.isAdvance(to, LocalDate.now());
        return suggestionService.suggest(projectId, workerId, from, to, advance);
    }

    // --- Create (R4, R5) -----------------------------------------------------------------------

    /**
     * Creates a {@code PLANNED} payout for a worker over a period, validating and persisting the
     * whole effect (the payout row, the report-line FK links, and the advance-credit change) in one
     * transaction (R4.7):
     *
     * <ol>
     *   <li>enforce project scope ({@link #assertProjectRead(Long)}, R10.5) and validate the inputs:
     *       the period ({@code periodTo >= periodFrom}, else {@link #PERIOD_INVALID} / 422, R4.5), the
     *       channel (required, else {@link #CHANNEL_REQUIRED} / 422, R2.2), the amount (when supplied,
     *       {@code >= 0}, else {@link #AMOUNT_INVALID} / 422, R4.4), and ACTIVE membership of the
     *       worker (else {@link #WORKER_NOT_MEMBER} / 422, R4.6);</li>
     *   <li>derive {@code isAdvance = periodTo > createdDate} via {@link AdvanceDeriver} (R5.3) and
     *       build the preview for the resolved branch to obtain the gross + credit figures;</li>
     *   <li>snapshot {@code suggestedAmount} to the computed suggestion (R1.4) and default
     *       {@code amount} to it when the request amount is {@code null} (an overridable cash figure,
     *       independent of linkage/credit, R4.4);</li>
     *   <li>for a <b>regular</b> payout set {@code advanceApplied = min(remaining, gross)} and, after
     *       persisting, link every covered unpaid {@code APPROVED} line to this payout via
     *       {@link ReportLineLinker} (R4.2, R4.3, R6.1); for an <b>advance</b> set
     *       {@code advanceApplied = 0} and link no line (R5.5, R6.4).</li>
     * </ol>
     *
     * <p>The remaining advance credit is not stored; it is folded on read from the persisted
     * {@code isAdvance} / {@code amount} / {@code advanceApplied} columns, so this create moves the
     * worker's balance implicitly (an advance by {@code +amount}, a regular by
     * {@code −advanceApplied}, R5.6). No notification is emitted (R9.1).
     *
     * @param projectId the owning project id (project scope is enforced here)
     * @param req       the create payload (worker, period, channel, amount, note)
     * @return the persisted payout as a {@link WorkerPayoutDto}
     */
    @Transactional
    public WorkerPayoutDto create(Long projectId, CreatePayoutRequest req) {
        assertProjectRead(projectId);

        LocalDate from = req.periodFrom();
        LocalDate to = req.periodTo();
        if (from == null || to == null || to.isBefore(from)) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PERIOD_INVALID);
        }
        if (req.paymentChannel() == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CHANNEL_REQUIRED);
        }
        if (req.amount() != null && req.amount().signum() < 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, AMOUNT_INVALID);
        }
        Long workerId = req.workerId();
        assertActiveMember(projectId, workerId);

        // R5.3 — classify from the dates alone (the sole rule); never a caller-supplied flag.
        boolean advance = advanceDeriver.isAdvance(to, LocalDate.now());

        // The preview folds FOR-06-10 Cost_Value verbatim (R3.1) and the credit arithmetic (R3.2);
        // the SAME selection path resolves the linkable lines below, so gross == linked-set cost.
        PayoutSuggestionDto preview = suggestionService.suggest(projectId, workerId, from, to, advance);

        BigDecimal suggested = preview.suggested();
        BigDecimal amount = req.amount() != null ? req.amount() : suggested; // R1.4 default
        // R5.5 — an advance applies no credit; a regular payout applies min(remaining, gross).
        BigDecimal advanceApplied = advance ? BigDecimal.ZERO : preview.appliedCredit();

        WorkerPayoutEntity payout = new WorkerPayoutEntity();
        payout.setProject(entityManager.getReference(ProjectEntity.class, projectId));
        payout.setWorker(entityManager.getReference(UserEntity.class, workerId));
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

        // R4.3, R6.1 — a regular payout links every covered unpaid APPROVED line in the SAME
        // transaction (an advance links none, R6.4). The linkable set is resolved via the identical
        // path the gross used, so the linked lines exactly back the gross (R3.4 excludes unavailable).
        if (!advance) {
            List<WorkReportRoomEntity> lines =
                    suggestionService.linkableLines(projectId, workerId, from, to);
            reportLineLinker.link(saved, lines);
        }

        saveAudit(null, saved, "CREATE");
        return dtoMapper.toDto(saved);
    }

    // --- Mark paid (R7.1, R7.2) ----------------------------------------------------------------

    /**
     * Transitions a {@code PLANNED} payout to {@code PAID}, sets {@code paidAt}, and freezes the
     * record (R7.1). A non-{@code PLANNED} source (i.e. already {@code PAID}) is rejected with
     * {@link #STATUS_ILLEGAL_TRANSITION} / 409, changing nothing — {@code PAID} is terminal (R7.2).
     * Project scope is enforced first via the inherited by-id access guard.
     *
     * @param id the payout to mark paid
     * @return the updated payout as a {@link WorkerPayoutDto}
     */
    @Transactional
    public WorkerPayoutDto markPaid(Long id) {
        assertProjectAccess(id);
        WorkerPayoutEntity payout = workerPayoutDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        if (payout.getStatus() != PayoutStatus.PLANNED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
        }
        String before = serializeEntity(payout);
        payout.setStatus(PayoutStatus.PAID);
        payout.setPaidAt(OffsetDateTime.now());
        WorkerPayoutEntity saved = workerPayoutDao.save(payout);
        entityManager.flush();
        saveAuditWithSnapshot(before, saved, "UPDATE");
        return dtoMapper.toDto(saved);
    }

    // --- Delete (R7.3, R5.7) -------------------------------------------------------------------

    /**
     * Hard-deletes a {@code PLANNED} payout, reversing its whole effect in one transaction (R7.3):
     *
     * <ul>
     *   <li>a {@code PAID} payout cannot be deleted ({@link #STATUS_ILLEGAL_TRANSITION} / 409, R7.2);</li>
     *   <li>a <b>regular</b> payout's linked report lines are released (their {@code paidByPayout} FK
     *       cleared) via {@link ReportLineLinker}, becoming payable again (R6.3); removing the row
     *       removes its {@code advanceApplied} from the running credit implicitly (R5.6);</li>
     *   <li>an <b>advance</b> whose added credit a <b>later regular payout already consumed</b> cannot
     *       be deleted ({@link #ADVANCE_ALREADY_APPLIED} / 409, R5.7) — removing it would make an
     *       already-applied balance inconsistent; otherwise the advance's credit is simply removed by
     *       deleting the row.</li>
     * </ul>
     *
     * Project scope is enforced first via the inherited by-id access guard.
     *
     * @param id the {@code PLANNED} payout to delete
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        assertProjectAccess(id);
        WorkerPayoutEntity payout = workerPayoutDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        if (payout.getStatus() != PayoutStatus.PLANNED) {
            // R7.2 — a PAID payout is terminal: it cannot be deleted.
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_ILLEGAL_TRANSITION);
        }

        if (payout.isAdvance()) {
            // R5.7 — reversing this advance's credit must not make an already-drawn balance
            // inconsistent: refuse when the worker's OTHER payouts have consumed more credit than
            // would remain without this advance.
            assertAdvanceNotConsumed(payout);
        } else {
            // R6.3 — release every line this regular payout paid, making them payable again.
            reportLineLinker.release(id);
        }

        saveAudit(payout, null, "DELETE");
        workerPayoutDao.deleteById(id);
        entityManager.flush();
    }

    /** Public alias for the lifecycle {@code delete} (mirrors the design §WorkerPayoutService signature). */
    @Transactional
    public void delete(Long id) {
        deleteById(id);
    }

    // --- List (R11.1, R5.8) --------------------------------------------------------------------

    /**
     * The project's payroll read model (R11.1): the payouts (optionally narrowed by the
     * {@link PayrollFilter}) plus, per worker, their remaining advance credit as a
     * {@link WorkerCreditDto} (R5.8). The remaining credit is folded on read by the pure
     * {@link AdvanceCreditCalculator} over each worker's payouts — it is never stored. Project scope
     * is enforced before the read (R10.5).
     *
     * @param projectId the owning project id (must be in the caller's accessible set unless ADMIN)
     * @param filter    the optional worker / status narrowing ({@code null} fields impose no constraint)
     * @return the {@link PayrollView}; never {@code null}
     */
    @Transactional(readOnly = true)
    public PayrollView list(Long projectId, PayrollFilter filter) {
        assertProjectRead(projectId);

        List<WorkerPayoutEntity> all = workerPayoutDao.findByProjectWithWorker(projectId);

        Long workerFilter = filter == null ? null : filter.workerId();
        PayoutStatus statusFilter = filter == null ? null : filter.status();

        // Per-worker remaining credit folds EVERY payout of the worker (unfiltered), so the running
        // balance is correct regardless of the list narrowing (R5.8). The payout rows honor the filter.
        Map<Long, List<WorkerPayoutEntity>> byWorker = new LinkedHashMap<>();
        List<WorkerPayoutDto> payouts = new ArrayList<>();
        for (WorkerPayoutEntity payout : all) {
            UserEntity worker = payout.getWorker();
            Long workerId = worker == null ? null : worker.getId();
            byWorker.computeIfAbsent(workerId, k -> new ArrayList<>()).add(payout);

            if (workerFilter != null && !workerFilter.equals(workerId)) {
                continue;
            }
            if (statusFilter != null && payout.getStatus() != statusFilter) {
                continue;
            }
            payouts.add(dtoMapper.toDto(payout));
        }

        List<WorkerCreditDto> workers = new ArrayList<>();
        for (Map.Entry<Long, List<WorkerPayoutEntity>> entry : byWorker.entrySet()) {
            List<WorkerPayoutEntity> workerPayouts = entry.getValue();
            String workerName = workerPayouts.isEmpty() || workerPayouts.get(0).getWorker() == null
                    ? null : workerPayouts.get(0).getWorker().getName();
            BigDecimal remaining = advanceCreditCalculator.remaining(workerPayouts);
            workers.add(new WorkerCreditDto(entry.getKey(), workerName, remaining));
        }

        return new PayrollView(projectId, payouts, workers);
    }

    // --- Validation helpers --------------------------------------------------------------------

    /**
     * R4.6 — the chosen worker must be an ACTIVE member of the project, else
     * {@link #WORKER_NOT_MEMBER} / 422, changing nothing.
     */
    private void assertActiveMember(Long projectId, Long workerId) {
        ProjectMemberEntity member = workerId == null ? null
                : projectMemberDao.findByUserIdAndProjectId(workerId, projectId).orElse(null);
        if (member == null || member.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, WORKER_NOT_MEMBER);
        }
    }

    /**
     * R5.7 — refuse deleting an advance whose added credit a later regular payout already consumed.
     * The worker's remaining credit WITHOUT this advance is the folded balance over their other
     * payouts; if that balance is negative (the regular payouts' {@code advanceApplied} already
     * exceeds the other advances), removing this advance would make the balance inconsistent, so the
     * delete is rejected with {@link #ADVANCE_ALREADY_APPLIED} / 409, changing nothing.
     */
    private void assertAdvanceNotConsumed(WorkerPayoutEntity advance) {
        Long projectId = advance.getProject() == null ? null : advance.getProject().getId();
        Long workerId = advance.getWorker() == null ? null : advance.getWorker().getId();
        List<WorkerPayoutEntity> others = new ArrayList<>(
                workerPayoutDao.findByProjectAndWorker(projectId, workerId));
        others.removeIf(p -> p.getId() != null && p.getId().equals(advance.getId()));

        // The folded balance over the OTHER payouts: Σ(other advances) − Σ(advanceApplied of
        // regulars). If removing THIS advance would drop the real balance below what regulars have
        // already drawn, the credit was already consumed. The calculator floors at 0, so compute the
        // unfloored balance of the others' advances minus their applied offsets.
        BigDecimal otherAdvances = BigDecimal.ZERO;
        BigDecimal appliedByRegulars = BigDecimal.ZERO;
        for (WorkerPayoutEntity p : others) {
            if (p.isAdvance()) {
                otherAdvances = otherAdvances.add(nz(p.getAmount()));
            } else {
                appliedByRegulars = appliedByRegulars.add(nz(p.getAdvanceApplied()));
            }
        }
        // Without this advance the available credit is `otherAdvances`; the regulars already consumed
        // `appliedByRegulars`. If that exceeds what is left without this advance, this advance's
        // credit was (partly) already drawn down — refuse the delete (R5.7).
        if (appliedByRegulars.compareTo(otherAdvances) > 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ADVANCE_ALREADY_APPLIED);
        }
    }

    /**
     * Project-scope READ guard for the project-id-level reads ({@link #list} / {@link #suggest} /
     * {@link #create}): ADMIN bypasses; otherwise the {@code projectId} must be in the caller's
     * accessible project set (the same source the LIST filter and {@code assertProjectAccess} use).
     * Denied uniformly with a 404 so an out-of-scope project is not revealed, consistent with
     * {@link ProjectScopedService#assertProjectAccess} (R10.5).
     */
    private void assertProjectRead(Long projectId) {
        if (isCallerAdmin()) {
            return;
        }
        Long callerId = callerUserId();
        if (callerId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
        Set<Long> allowed = allowedProjectIds(callerId);
        if (allowed == null || projectId == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // --- Caller / role resolution (mirrors WorkReportService) ----------------------------------

    /** True iff the authenticated caller carries the exact ADMIN authority. */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (("ROLE_" + ADMIN_ROLE_CODE).equals(a) || ADMIN_ROLE_CODE.equals(a)) {
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
