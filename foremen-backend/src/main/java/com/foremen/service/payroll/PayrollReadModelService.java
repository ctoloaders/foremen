package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.WorkerPayrollLineDao;
import com.foremen.dao.WorkerPayrollProfileDao;
import com.foremen.dao.model.MeasurementUnitEntity;
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
import com.foremen.dao.model.WorkerPayrollLineEntity;
import com.foremen.dao.model.WorkerPayrollProfileEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.payroll.dto.CompanyPayrollDto;
import com.foremen.service.payroll.dto.CompanyPayrollProjectRowDto;
import com.foremen.service.payroll.dto.CompanyPayrollRowDto;
import com.foremen.service.payroll.dto.PayrollEarnedLineDto;
import com.foremen.service.payroll.dto.PayrollEarnedProjectDto;
import com.foremen.service.payroll.dto.PayrollLineDto;
import com.foremen.service.payroll.dto.PayrollMonthDto;
import com.foremen.service.payroll.dto.PayrollSumsDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.WorkerPayrollCardDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;
import com.foremen.service.workercost.WorkerCostDerivation;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;
import com.foremen.service.workercost.WorkerCostService;
import com.foremen.service.workercost.WorkerProfile;

/**
 * FOR-07-06 (Requirements 7.3, 9.3, 9.4, 10, 11; design §PayrollReadModelService, MonthAttribution,
 * BalanceCalculator) — the company payroll table and the worker payroll card read models.
 *
 * <p><b>Loads.</b> One query per data kind for all selected workers (no N+1): APPROVED report lines
 * with {@code performedTo <= to}, payouts with {@code payoutDate <= to} (PLANNED and PAID — the
 * glossary "Paid" counts both), additional lines with {@code month <= to}, payroll profiles (opening
 * balance), worker profiles (type + seniority) and — table only — the allocations of the period's
 * payouts. The whole history up to {@code to} is loaded so the balance chain starts at the opening
 * balance (R9.3); nothing is persisted (R9.4).
 *
 * <p><b>Costing.</b> Every line is costed once through the FOR-06-10 seam:
 * {@link WorkerProfile#resolve} ({@code EffectiveTier}: user type + seniority, base-tier fallback) and
 * {@link WorkerCostDerivation#costValue} with {@code rate = report.clientUnitPrice}; reported cost on
 * {@code quantity}, planned cost on {@code plan_quantity}. Costs are scaled to 2 decimals
 * ({@code HALF_UP}) per line; a {@code null} cost is unavailable and excluded from every sum. One
 * {@code baseType} and one profile map per request.
 *
 * <p><b>Attribution.</b> {@link MonthAttribution}: line → month of {@code performedTo}; payout →
 * month of {@code payoutDate}; additional line → its {@code month}. For the table's period sums a line
 * counts when {@code performedTo ∈ [from, to]}, a payout when {@code payoutDate ∈ [from, to]}, and an
 * additional line when its month overlaps {@code [from, to]}. Card month blocks cover whole calendar
 * months (bounded above by {@code to}).
 *
 * <p><b>Balance.</b> {@link BalanceCalculator} over {@code earned = line costs + additional lines}
 * and {@code paid = payout amounts} per month. The table's {@code balanceAtTo} is the balance at the
 * end of {@code YearMonth.from(to)}, which is exactly the card's last block balance for the same
 * {@code to} (Property 7).
 *
 * <p>Company-wide: not project-filtered (R10.6, R14.2). Access is gated by the {@code PAYROLL} ABAC
 * resource on the controller.
 */
@Service
@Transactional(readOnly = true)
public class PayrollReadModelService {

    static final String PERIOD_INVALID = "error.payroll.period.invalid";
    static final String WORKER_ROLE_CODE = "WORKER";
    /** Lower bound for the additional-lines history load (balance chain from the very beginning). */
    static final LocalDate HISTORY_START = LocalDate.of(1900, 1, 1);

    private static final int SCALE = 2;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private final UserDao userDao;
    private final WorkReportRoomDao workReportRoomDao;
    private final WorkerPayoutDao workerPayoutDao;
    private final WorkerPayoutAllocationDao allocationDao;
    private final WorkerPayrollLineDao payrollLineDao;
    private final WorkerPayrollProfileDao payrollProfileDao;
    private final WorkerCostService workerCostService;
    private final AdvanceCreditCalculator advanceCreditCalculator;
    private final WorkerPayoutDtoMapper payoutDtoMapper;

    private Clock clock = Clock.systemDefaultZone();

    public PayrollReadModelService(UserDao userDao,
                                   WorkReportRoomDao workReportRoomDao,
                                   WorkerPayoutDao workerPayoutDao,
                                   WorkerPayoutAllocationDao allocationDao,
                                   WorkerPayrollLineDao payrollLineDao,
                                   WorkerPayrollProfileDao payrollProfileDao,
                                   WorkerCostService workerCostService,
                                   AdvanceCreditCalculator advanceCreditCalculator,
                                   WorkerPayoutDtoMapper payoutDtoMapper) {
        this.userDao = userDao;
        this.workReportRoomDao = workReportRoomDao;
        this.workerPayoutDao = workerPayoutDao;
        this.allocationDao = allocationDao;
        this.payrollLineDao = payrollLineDao;
        this.payrollProfileDao = payrollProfileDao;
        this.workerCostService = workerCostService;
        this.advanceCreditCalculator = advanceCreditCalculator;
        this.payoutDtoMapper = payoutDtoMapper;
    }

    /** Test seam for "today" (the current-month hints of the card). */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // =============================================================================================
    // Company table (R10)
    // =============================================================================================

    /**
     * The company payroll table for {@code [from, to]} (R10).
     *
     * @param from      period start, required
     * @param to        period end, required, {@code >= from} (else 422 {@code error.payroll.period.invalid})
     * @param workerIds optional worker filter; when non-empty exactly these WORKER users are listed
     *                  (even without activity); when empty every WORKER with activity in the period
     * @return the table with rows ordered by worker name, then id, and the totals row
     */
    public CompanyPayrollDto companyTable(LocalDate from, LocalDate to, Collection<Long> workerIds) {
        assertPeriod(from, to);
        boolean filtered = workerIds != null && workerIds.stream().anyMatch(Objects::nonNull);
        List<UserEntity> workers = filtered ? listedWorkers(workerIds) : userDao.findByRoleCode(WORKER_ROLE_CODE);
        Map<Long, UserEntity> workersById = new LinkedHashMap<>();
        for (UserEntity w : workers) {
            if (w != null && w.getId() != null) {
                workersById.putIfAbsent(w.getId(), w);
            }
        }
        if (workersById.isEmpty()) {
            return new CompanyPayrollDto(from, to, List.of(), sums(ZERO, ZERO, ZERO, ZERO));
        }

        Batch batch = load(workersById.keySet(), to);
        YearMonth fromMonth = YearMonth.from(from);
        YearMonth toMonth = YearMonth.from(to);

        // Allocations of the period's payouts — one query for all workers (R10.3).
        Set<Long> periodPayoutIds = new LinkedHashSet<>();
        for (List<WorkerPayoutEntity> payouts : batch.payouts.values()) {
            for (WorkerPayoutEntity p : payouts) {
                if (inPeriod(p.getPayoutDate(), from, to) && p.getId() != null) {
                    periodPayoutIds.add(p.getId());
                }
            }
        }
        Map<Long, List<WorkerPayoutAllocationEntity>> allocationsByWorker = new HashMap<>();
        if (!periodPayoutIds.isEmpty()) {
            for (WorkerPayoutAllocationEntity a : allocationDao.findByPayoutIds(periodPayoutIds)) {
                Long wid = a.getPayout() == null || a.getPayout().getWorker() == null
                        ? null : a.getPayout().getWorker().getId();
                if (wid != null) {
                    allocationsByWorker.computeIfAbsent(wid, k -> new ArrayList<>()).add(a);
                }
            }
        }

        boolean ru = isRussianLocale();
        List<CompanyPayrollRowDto> rows = new ArrayList<>();
        for (UserEntity worker : workersById.values()) {
            Long wid = worker.getId();
            List<CostedLine> lines = batch.lines.getOrDefault(wid, List.of());
            List<WorkerPayoutEntity> payouts = batch.payouts.getOrDefault(wid, List.of());
            List<WorkerPayrollLineEntity> additional = batch.additional.getOrDefault(wid, List.of());

            BigDecimal reported = ZERO;
            BigDecimal planned = ZERO;
            BigDecimal other = ZERO;
            BigDecimal paid = ZERO;
            boolean activity = false;
            Map<Long, ProjectAcc> projects = new LinkedHashMap<>();

            for (CostedLine cl : lines) {
                if (!inPeriod(cl.performedTo(), from, to)) {
                    continue;
                }
                activity = true;
                ProjectAcc acc = projects.computeIfAbsent(projectId(cl.project()), k -> new ProjectAcc(cl.project()));
                if (cl.cost() != null) {
                    reported = reported.add(cl.cost());
                    acc.reported = acc.reported.add(cl.cost());
                }
                if (cl.plannedCost() != null) {
                    planned = planned.add(cl.plannedCost());
                    acc.planned = acc.planned.add(cl.plannedCost());
                }
            }
            for (WorkerPayrollLineEntity l : additional) {
                YearMonth m = MonthAttribution.ofAdditionalLine(l.getMonth());
                if (!m.isBefore(fromMonth) && !m.isAfter(toMonth)) {
                    activity = true;
                    other = other.add(scaled(l.getAmount()));
                }
            }
            for (WorkerPayoutEntity p : payouts) {
                if (inPeriod(p.getPayoutDate(), from, to)) {
                    activity = true;
                    paid = paid.add(scaled(p.getAmount()));
                }
            }
            // Per-project Paid: allocations of the period's payouts. A project that only received
            // allocations (its lines are outside the period) still gets a sub-row, so no paid amount
            // disappears from the breakdown.
            for (WorkerPayoutAllocationEntity a : allocationsByWorker.getOrDefault(wid, List.of())) {
                ProjectEntity project = a.getWorkReportRoom() == null || a.getWorkReportRoom().getReport() == null
                        ? null : a.getWorkReportRoom().getReport().getProject();
                ProjectAcc acc = projects.computeIfAbsent(projectId(project), k -> new ProjectAcc(project));
                acc.paid = acc.paid.add(scaled(a.getAmount()));
            }

            if (!filtered && !activity) {
                continue; // R10.2 — no activity in the period and not explicitly listed
            }

            WorkerInfo info = workerInfo(wid, batch, ru);
            BigDecimal balanceAtTo = BalanceCalculator.balanceAt(batch.opening(wid),
                    earnedByMonth(lines, additional), paidByMonth(payouts), toMonth);

            List<CompanyPayrollProjectRowDto> projectRows = new ArrayList<>(projects.size());
            for (ProjectAcc acc : projects.values()) {
                projectRows.add(new CompanyPayrollProjectRowDto(projectId(acc.project),
                        acc.project == null ? null : acc.project.getName(), acc.reported, acc.planned, acc.paid));
            }
            rows.add(new CompanyPayrollRowDto(wid, worker.getName(), info.typeName(), info.seniorityPct(),
                    info.missing(), sums(reported, planned, other, paid), balanceAtTo, projectRows));
        }

        rows.sort(Comparator.comparing(CompanyPayrollRowDto::workerName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(CompanyPayrollRowDto::workerId));

        BigDecimal tReported = ZERO;
        BigDecimal tPlanned = ZERO;
        BigDecimal tOther = ZERO;
        BigDecimal tPaid = ZERO;
        for (CompanyPayrollRowDto r : rows) {
            tReported = tReported.add(r.sums().reported());
            tPlanned = tPlanned.add(r.sums().planned());
            tOther = tOther.add(r.sums().other());
            tPaid = tPaid.add(r.sums().paid());
        }
        return new CompanyPayrollDto(from, to, List.copyOf(rows), sums(tReported, tPlanned, tOther, tPaid));
    }

    // =============================================================================================
    // Worker card (R11)
    // =============================================================================================

    /**
     * The worker payroll card for {@code [from, to]} (R11, R7.3).
     *
     * @param workerId the worker (must be a WORKER, else 422 {@code error.workerPayout.worker.invalid})
     * @param from     period start, required
     * @param to       period end, required, {@code >= from} (else 422 {@code error.payroll.period.invalid})
     * @return the card with one block per calendar month overlapping {@code [from, to]}
     */
    public WorkerPayrollCardDto workerCard(Long workerId, LocalDate from, LocalDate to) {
        assertPeriod(from, to);
        UserEntity worker = PayrollWriteSupport.assertWorker(userDao, workerId);

        Batch batch = load(List.of(workerId), to);
        boolean ru = isRussianLocale();
        WorkerInfo info = workerInfo(workerId, batch, ru);

        List<CostedLine> lines = batch.lines.getOrDefault(workerId, List.of());
        List<WorkerPayoutEntity> payouts = batch.payouts.getOrDefault(workerId, List.of());
        List<WorkerPayrollLineEntity> additional = batch.additional.getOrDefault(workerId, List.of());
        BigDecimal opening = batch.opening(workerId);

        List<YearMonth> months = MonthAttribution.monthsBetween(from, to);
        Map<YearMonth, BigDecimal> earnedByMonth = earnedByMonth(lines, additional);
        Map<YearMonth, BigDecimal> paidByMonth = paidByMonth(payouts);
        Map<YearMonth, BigDecimal> balances = BalanceCalculator.chain(opening, earnedByMonth, paidByMonth, months);
        BigDecimal previous = BalanceCalculator.balanceAt(opening, earnedByMonth, paidByMonth,
                months.get(0).minusMonths(1));

        Map<YearMonth, List<CostedLine>> linesByMonth = groupBy(lines, CostedLine::month);
        Map<YearMonth, List<WorkerPayrollLineEntity>> additionalByMonth =
                groupBy(additional, l -> MonthAttribution.ofAdditionalLine(l.getMonth()));
        Map<YearMonth, List<WorkerPayoutEntity>> payoutsByMonth =
                groupBy(payouts, p -> MonthAttribution.ofPayout(p.getPayoutDate()));
        YearMonth currentMonth = YearMonth.now(clock);

        List<PayrollMonthDto> blocks = new ArrayList<>(months.size());
        for (YearMonth month : months) {
            // Earned rows, one per project, in order of first appearance (lines are performedTo-sorted).
            Map<Long, EarnedAcc> earned = new LinkedHashMap<>();
            for (CostedLine cl : linesByMonth.getOrDefault(month, List.of())) {
                EarnedAcc acc = earned.computeIfAbsent(projectId(cl.project()), k -> new EarnedAcc(cl.project()));
                if (cl.cost() != null) {
                    acc.amount = acc.amount.add(cl.cost());
                }
                acc.lines.add(earnedLine(cl, ru));
            }
            List<PayrollEarnedProjectDto> earnedRows = new ArrayList<>(earned.size());
            BigDecimal total = ZERO;
            for (EarnedAcc acc : earned.values()) {
                earnedRows.add(new PayrollEarnedProjectDto(projectId(acc.project),
                        acc.project == null ? null : acc.project.getName(), acc.amount, List.copyOf(acc.lines)));
                total = total.add(acc.amount);
            }

            List<WorkerPayrollLineEntity> monthAdditional = additionalByMonth.getOrDefault(month, List.of());
            List<PayrollLineDto> additionalDtos = new ArrayList<>(monthAdditional.size());
            for (WorkerPayrollLineEntity l : monthAdditional) {
                additionalDtos.add(PayrollLineDto.from(l));
                total = total.add(scaled(l.getAmount()));
            }

            List<WorkerPayoutDto> payoutDtos = new ArrayList<>();
            BigDecimal paid = ZERO;
            for (WorkerPayoutEntity p : payoutsByMonth.getOrDefault(month, List.of())) {
                payoutDtos.add(payoutDtoMapper.toDto(p));
                paid = paid.add(scaled(p.getAmount()));
            }

            List<PayrollLineDto> hints = List.of();
            if (month.equals(currentMonth)) {
                hints = PayrollHintSelector.hints(additionalByMonth.getOrDefault(month.minusMonths(1), List.of()),
                                monthAdditional, WorkerPayrollLineEntity::getName, PayrollLineService.LATEST_LAST)
                        .stream().map(PayrollLineDto::from).toList();
            }

            BigDecimal balance = balances.get(month);
            blocks.add(new PayrollMonthDto(month, List.copyOf(earnedRows), List.copyOf(additionalDtos), total,
                    List.copyOf(payoutDtos), paid, previous, balance, hints));
            previous = balance;
        }

        BigDecimal credit = advanceCreditCalculator.remaining(workerPayoutDao.findByWorker(workerId));
        return new WorkerPayrollCardDto(workerId, worker.getName(), info.typeName(), info.seniorityPct(),
                info.missing(), opening, credit, List.copyOf(blocks));
    }

    // =============================================================================================
    // Loading + costing
    // =============================================================================================

    /** A report line costed once through the seam. */
    private record CostedLine(WorkReportRoomEntity line, ProjectEntity project, LocalDate performedTo,
                              YearMonth month, BigDecimal unitCost, BigDecimal cost, BigDecimal plannedCost) {
    }

    /** Everything loaded for the selected workers, grouped by worker id. */
    private record Batch(WorkerTypeEntity baseType,
                         Map<Long, WorkerProfile> profiles,
                         Map<Long, List<CostedLine>> lines,
                         Map<Long, List<WorkerPayoutEntity>> payouts,
                         Map<Long, List<WorkerPayrollLineEntity>> additional,
                         Map<Long, BigDecimal> openings) {
        BigDecimal opening(Long workerId) {
            BigDecimal value = openings.get(workerId);
            return value == null ? ZERO : value.setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    private record WorkerInfo(String typeName, BigDecimal seniorityPct, boolean missing) {
    }

    /** One query per data kind for all {@code workerIds}, history up to {@code to}. */
    private Batch load(Collection<Long> workerIds, LocalDate to) {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(workerIds));
        WorkerTypeEntity baseType = workerCostService.findBaseType();
        WorkerTypeTier baseTier = baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        Map<Long, WorkerProfile> profiles = workerCostService.workerProfilesById(ids);
        if (profiles == null) {
            profiles = Map.of();
        }

        Map<Long, List<CostedLine>> lines = new HashMap<>();
        Map<Long, ResolvedTier> tiers = new HashMap<>();
        for (WorkReportRoomEntity line : workReportRoomDao.findApprovedLinesForWorkers(ids, to)) {
            WorkReportEntity report = line == null ? null : line.getReport();
            if (report == null || report.getWorker() == null || report.getPerformedTo() == null) {
                continue;
            }
            Long wid = report.getWorker().getId();
            Map<Long, WorkerProfile> p = profiles;
            ResolvedTier tier = tiers.computeIfAbsent(wid, k -> WorkerProfile.resolve(p.get(k), baseType));
            BigDecimal rate = report.getClientUnitPrice();
            BigDecimal unitCost = WorkerCostDerivation.unitServiceCost(rate, tier.tier(), baseTier);
            BigDecimal cost = scaledOrNull(WorkerCostDerivation.costValue(rate, tier.tier(), baseTier,
                    line.getQuantity()));
            BigDecimal plannedCost = scaledOrNull(WorkerCostDerivation.costValue(rate, tier.tier(), baseTier,
                    line.getPlanQuantity()));
            lines.computeIfAbsent(wid, k -> new ArrayList<>()).add(new CostedLine(line, report.getProject(),
                    report.getPerformedTo(), MonthAttribution.ofLine(report.getPerformedTo()),
                    scaledOrNull(unitCost), cost, plannedCost));
        }

        Map<Long, List<WorkerPayoutEntity>> payouts = new HashMap<>();
        for (WorkerPayoutEntity p : workerPayoutDao.findByWorkersAndPayoutDateLte(ids, to)) {
            if (p != null && p.getWorker() != null && p.getPayoutDate() != null) {
                payouts.computeIfAbsent(p.getWorker().getId(), k -> new ArrayList<>()).add(p);
            }
        }

        Map<Long, List<WorkerPayrollLineEntity>> additional = new HashMap<>();
        for (WorkerPayrollLineEntity l : payrollLineDao.findByWorkerIdsAndMonthBetween(ids, HISTORY_START, to)) {
            if (l != null && l.getWorker() != null && l.getMonth() != null) {
                additional.computeIfAbsent(l.getWorker().getId(), k -> new ArrayList<>()).add(l);
            }
        }

        Map<Long, BigDecimal> openings = new HashMap<>();
        for (WorkerPayrollProfileEntity profile : payrollProfileDao.findByWorkerIds(ids)) {
            if (profile != null && profile.getWorker() != null) {
                openings.put(profile.getWorker().getId(), profile.getOpeningBalance());
            }
        }
        return new Batch(baseType, profiles, lines, payouts, additional, openings);
    }

    private static Map<YearMonth, BigDecimal> earnedByMonth(List<CostedLine> lines,
                                                            List<WorkerPayrollLineEntity> additional) {
        Map<YearMonth, BigDecimal> earned = new TreeMap<>(
                MonthAttribution.sumByMonth(lines, CostedLine::month, CostedLine::cost));
        MonthAttribution.sumByMonth(additional, l -> MonthAttribution.ofAdditionalLine(l.getMonth()),
                        WorkerPayrollLineEntity::getAmount)
                .forEach((m, v) -> earned.merge(m, v, BigDecimal::add));
        return earned;
    }

    private static Map<YearMonth, BigDecimal> paidByMonth(List<WorkerPayoutEntity> payouts) {
        return MonthAttribution.sumByMonth(payouts, p -> MonthAttribution.ofPayout(p.getPayoutDate()),
                WorkerPayoutEntity::getAmount);
    }

    private WorkerInfo workerInfo(Long workerId, Batch batch, boolean ru) {
        WorkerProfile profile = batch.profiles.get(workerId);
        ResolvedTier resolved = WorkerProfile.resolve(profile, batch.baseType);
        WorkerTypeEntity type = resolved.baseTierFallback()
                ? batch.baseType
                : profile == null ? null : profile.workerType();
        BigDecimal seniority = profile == null ? BigDecimal.ZERO : profile.seniorityPct();
        return new WorkerInfo(type == null ? null : workerTypeName(type, ru), seniority, resolved.baseTierFallback());
    }

    private static PayrollEarnedLineDto earnedLine(CostedLine cl, boolean ru) {
        WorkReportRoomEntity line = cl.line();
        WorkItemEntity workItem = line.getReport().getWorkItem();
        BigDecimal paid = line.getPaidAmount() == null ? ZERO : line.getPaidAmount();
        return new PayrollEarnedLineDto(line.getId(), workItemName(workItem, ru), roomName(line.getRoom(), ru),
                workItem == null ? null : unitName(workItem.getUnit(), ru), cl.unitCost(), line.getQuantity(),
                cl.cost(), paid, LinePaymentStatus.of(cl.cost(), paid));
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private List<UserEntity> listedWorkers(Collection<Long> workerIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : workerIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        List<UserEntity> result = new ArrayList<>();
        for (UserEntity user : userDao.findWorkerProfiles(ids)) {
            if (user != null && isWorker(user)) {
                result.add(user);
            }
        }
        return result;
    }

    private static boolean isWorker(UserEntity user) {
        RoleEntity role = user.getRole();
        return role != null && role.getCode() != null && WORKER_ROLE_CODE.equalsIgnoreCase(role.getCode().trim());
    }

    private static void assertPeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PERIOD_INVALID);
        }
    }

    private static boolean inPeriod(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    private static PayrollSumsDto sums(BigDecimal reported, BigDecimal planned, BigDecimal other, BigDecimal paid) {
        return new PayrollSumsDto(reported.add(other), paid, reported, planned, other);
    }

    private static <T, K> Map<K, List<T>> groupBy(List<T> items, Function<T, K> keyOf) {
        Map<K, List<T>> grouped = new HashMap<>();
        for (T item : items) {
            grouped.computeIfAbsent(keyOf.apply(item), k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private static Long projectId(ProjectEntity project) {
        return project == null ? null : project.getId();
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaledOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static final class ProjectAcc {
        private final ProjectEntity project;
        private BigDecimal reported = ZERO;
        private BigDecimal planned = ZERO;
        private BigDecimal paid = ZERO;

        private ProjectAcc(ProjectEntity project) {
            this.project = project;
        }
    }

    private static final class EarnedAcc {
        private final ProjectEntity project;
        private BigDecimal amount = ZERO;
        private final List<PayrollEarnedLineDto> lines = new ArrayList<>();

        private EarnedAcc(ProjectEntity project) {
            this.project = project;
        }
    }

    // --- localized-name helpers (ru → pl → code) -----------------------------------------------

    private static String workItemName(WorkItemEntity workItem, boolean ru) {
        if (workItem == null) {
            return null;
        }
        return firstNonBlank(
                ru ? workItem.getNameRU() : workItem.getNamePL(),
                ru ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    private static String unitName(MeasurementUnitEntity unit, boolean ru) {
        if (unit == null) {
            return null;
        }
        return firstNonBlank(
                ru ? unit.getNameRU() : unit.getNamePL(),
                ru ? unit.getNamePL() : unit.getNameRU(),
                unit.getCode());
    }

    private static String workerTypeName(WorkerTypeEntity type, boolean ru) {
        return firstNonBlank(
                ru ? type.getNameRU() : type.getNamePL(),
                ru ? type.getNamePL() : type.getNameRU(),
                type.getCode());
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
