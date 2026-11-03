package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.payroll.PayoutDistributor.Allocation;
import com.foremen.service.payroll.PayoutDistributor.PayableLine;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.workercost.WorkerCostDerivation;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;
import com.foremen.service.workercost.WorkerCostService;
import com.foremen.service.workercost.WorkerProfile;

/**
 * FOR-07-06 (Requirement 4, 7.1; design §PayoutSuggestionService) — the <b>worker-wide</b>
 * create-dialog preview builder. It folds the single cost seam ({@link WorkerCostDerivation}, rate =
 * the report's {@code clientUnitPrice}, tier = the worker user's {@link WorkerProfile} resolved via
 * {@code EffectiveTier}) over the worker's report lines on <b>all</b> projects and layers the pure
 * {@link AdvanceCreditCalculator} credit arithmetic on top. No project is taken (R4.1).
 *
 * <p>Two branches:
 * <ul>
 *   <li><b>Regular</b> period (R4.2–R4.6): candidates are
 *       {@code WorkReportRoomDao.findPayableLines(workerId, periodTo)} — every {@code APPROVED} line
 *       with report {@code performedFrom <= periodTo}, in distribution order ({@code periodFrom}
 *       never excludes older unpaid lines). Per line {@code cost = costValue(rate, tier, baseTier,
 *       quantity)} and {@code remainder = max(0, cost − paidAmount)}; lines with remainder 0 are
 *       dropped; a {@code null} cost marks the line <b>unavailable</b> (listed, excluded from the
 *       gross). {@code gross = Σ remainder}; the credit is worker-wide (all the worker's payouts,
 *       R7.1); {@code applied = min(credit, gross)}; {@code suggested = max(0, gross − credit)}; each
 *       available line carries the {@link PayoutDistributor} allocation preview of
 *       {@code suggested + applied}.</li>
 *   <li><b>Advance</b> period (R4.7): the FOR-06-11 planned basis without the project filter —
 *       {@code plan_quantity} of the worker's {@code APPROVED} lines whose report overlaps
 *       {@code [from, to]} on any project; no credit applied, nothing allocated.</li>
 * </ul>
 *
 * <p>Persists nothing. {@link #payableLines(Long, LocalDate)} exposes the identical candidate
 * selection (locked) for payout create, so the distributed set matches the previewed one.
 */
@Service
public class PayoutSuggestionService {

    private static final int SCALE = 2;

    private final WorkReportDao workReportDao;
    private final WorkReportRoomDao workReportRoomDao;
    private final WorkerPayoutDao workerPayoutDao;
    private final WorkerCostService workerCostService;
    private final AdvanceCreditCalculator advanceCreditCalculator;

    public PayoutSuggestionService(WorkReportDao workReportDao,
                                   WorkReportRoomDao workReportRoomDao,
                                   WorkerPayoutDao workerPayoutDao,
                                   WorkerCostService workerCostService,
                                   AdvanceCreditCalculator advanceCreditCalculator) {
        this.workReportDao = workReportDao;
        this.workReportRoomDao = workReportRoomDao;
        this.workerPayoutDao = workerPayoutDao;
        this.workerCostService = workerCostService;
        this.advanceCreditCalculator = advanceCreditCalculator;
    }

    /**
     * One payable candidate line: the report line and its positive unpaid remainder
     * ({@code max(0, cost − paidAmount)}, scale 2). Only lines with an available cost and a
     * remainder {@code > 0} are candidates.
     */
    public record PayableCandidate(WorkReportRoomEntity line, BigDecimal remainder) {

        /** The {@link PayoutDistributor} input for this candidate. */
        public PayableLine toPayableLine() {
            return new PayableLine(line.getId(), remainder);
        }
    }

    /**
     * Build the create-dialog preview for a worker + period. The caller derives {@code advance} via
     * {@code AdvanceDeriver} ({@code periodTo} after today).
     *
     * @param workerId the worker the preview is for
     * @param from     the period start (inclusive; only the advance basis uses it)
     * @param to       the period end (inclusive)
     * @param advance  {@code true} for an advance period (planned basis, no credit, no preview)
     * @return the {@link PayoutSuggestionDto} preview; never {@code null}
     */
    @Transactional(readOnly = true)
    public PayoutSuggestionDto suggest(Long workerId, LocalDate from, LocalDate to, boolean advance) {
        CostContext ctx = loadCostContext(workerId);
        BigDecimal remainingCredit = advanceCreditCalculator.remaining(
                workerPayoutDao.findByWorker(workerId));
        return advance
                ? advanceSuggestion(workerId, from, to, ctx, remainingCredit)
                : regularSuggestion(workerId, to, ctx, remainingCredit);
    }

    /**
     * The <b>locked</b> ({@code PESSIMISTIC_WRITE}) payable candidates of a regular payout in
     * distribution order — the same selection {@link #suggest} folds into the gross (available
     * cost, remainder {@code > 0}). Must run inside the payout-create transaction (R5.8).
     *
     * @param workerId the worker the payout is for
     * @param periodTo the payout period end (inclusive)
     * @return the candidates in distribution order, empty when none
     */
    @Transactional
    public List<PayableCandidate> payableLines(Long workerId, LocalDate periodTo) {
        CostContext ctx = loadCostContext(workerId);
        List<PayableCandidate> candidates = new ArrayList<>();
        for (WorkReportRoomEntity line : workReportRoomDao.findPayableLinesForUpdate(workerId, periodTo)) {
            if (line == null || line.getReport() == null) {
                continue;
            }
            BigDecimal remainder = remainder(lineCost(line, ctx), line.getPaidAmount());
            if (remainder != null && remainder.signum() > 0) {
                candidates.add(new PayableCandidate(line, remainder));
            }
        }
        return candidates;
    }

    // --- branches -------------------------------------------------------------------------------

    private PayoutSuggestionDto regularSuggestion(Long workerId, LocalDate to, CostContext ctx,
                                                  BigDecimal remainingCredit) {
        boolean ru = isRussianLocale();
        List<LineRow> rows = new ArrayList<>();
        List<PayableLine> payable = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO.setScale(SCALE);

        for (WorkReportRoomEntity line : workReportRoomDao.findPayableLines(workerId, to)) {
            if (line == null || line.getReport() == null) {
                continue;
            }
            BigDecimal cost = lineCost(line, ctx);
            BigDecimal remainder = remainder(cost, line.getPaidAmount());
            if (remainder != null && remainder.signum() <= 0) {
                continue; // fully paid (R4.2)
            }
            rows.add(new LineRow(line, line.getQuantity(), cost, remainder));
            if (remainder != null) {
                gross = gross.add(remainder); // only available lines contribute (R4.4)
                payable.add(new PayableLine(line.getId(), remainder));
            }
        }

        BigDecimal applied = advanceCreditCalculator.applied(remainingCredit, gross);
        BigDecimal suggested = advanceCreditCalculator.suggested(gross, remainingCredit);

        Map<Long, BigDecimal> preview = new HashMap<>();
        for (Allocation a : PayoutDistributor.distribute(suggested.add(applied), payable).allocations()) {
            preview.put(a.lineId(), a.amount());
        }

        List<PayoutLineDto> breakdown = new ArrayList<>(rows.size());
        for (LineRow row : rows) {
            BigDecimal allocation = row.remainder() == null
                    ? BigDecimal.ZERO.setScale(SCALE)
                    : preview.getOrDefault(row.line().getId(), BigDecimal.ZERO.setScale(SCALE));
            breakdown.add(toDto(row, allocation, ctx, ru));
        }
        return new PayoutSuggestionDto(gross, remainingCredit, applied, suggested, false, breakdown);
    }

    private PayoutSuggestionDto advanceSuggestion(Long workerId, LocalDate from, LocalDate to,
                                                  CostContext ctx, BigDecimal remainingCredit) {
        boolean ru = isRussianLocale();
        List<PayoutLineDto> breakdown = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO.setScale(SCALE);

        for (WorkReportEntity report
                : workReportDao.findApprovedForWorkerInPeriodWithRooms(workerId, from, to)) {
            if (report == null || report.getRooms() == null) {
                continue;
            }
            for (WorkReportRoomEntity line : report.getRooms()) {
                if (line == null) {
                    continue;
                }
                // The advance basis is the planned quantity (FOR-06-11 R3.5, FOR-07-06 R4.7).
                BigDecimal cost = WorkerCostDerivation.costValue(report.getClientUnitPrice(),
                        ctx.resolved().tier(), ctx.baseTier(), line.getPlanQuantity());
                LineRow row = new LineRow(line, line.getPlanQuantity(), cost, null);
                breakdown.add(toDto(row, BigDecimal.ZERO.setScale(SCALE), ctx, ru));
                if (cost != null) {
                    gross = gross.add(cost);
                }
            }
        }
        // An advance creates credit, it does not consume it: no deduction, nothing allocated.
        return new PayoutSuggestionDto(gross, remainingCredit, BigDecimal.ZERO.setScale(SCALE),
                gross.max(BigDecimal.ZERO), true, breakdown);
    }

    // --- cost seam (single derivation, no second formula) --------------------------------------

    /** The line's {@code Cost_Value} on its reported quantity; {@code null} ⇒ unavailable. */
    private static BigDecimal lineCost(WorkReportRoomEntity line, CostContext ctx) {
        return WorkerCostDerivation.costValue(line.getReport().getClientUnitPrice(),
                ctx.resolved().tier(), ctx.baseTier(), line.getQuantity());
    }

    /** {@code max(0, cost − paid)} at scale 2; {@code null} when the cost is unavailable. */
    private static BigDecimal remainder(BigDecimal cost, BigDecimal paid) {
        if (cost == null) {
            return null;
        }
        BigDecimal p = paid == null ? BigDecimal.ZERO : paid;
        return cost.subtract(p).max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** All cost inputs for one worker, resolved once from the worker user's profile (R2.1). */
    private CostContext loadCostContext(Long workerId) {
        WorkerTypeEntity baseType = workerCostService.findBaseType();
        WorkerTypeTier baseTier = baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        Map<Long, WorkerProfile> profiles = workerId == null
                ? Map.of()
                : workerCostService.workerProfilesById(List.of(workerId));
        WorkerProfile profile = profiles == null ? null : profiles.get(workerId);
        ResolvedTier resolved = WorkerProfile.resolve(profile, baseType);
        return new CostContext(baseType, baseTier, profile, resolved);
    }

    private record CostContext(WorkerTypeEntity baseType,
                               WorkerTypeTier baseTier,
                               WorkerProfile profile,
                               ResolvedTier resolved) {

        BigDecimal seniorityPct() {
            return profile == null ? BigDecimal.ZERO : profile.seniorityPct();
        }
    }

    /** A breakdown row before DTO mapping. {@code remainder} is {@code null} when unavailable / advance. */
    private record LineRow(WorkReportRoomEntity line, BigDecimal quantity, BigDecimal cost,
                           BigDecimal remainder) {
    }

    private PayoutLineDto toDto(LineRow row, BigDecimal allocation, CostContext ctx, boolean ru) {
        WorkReportRoomEntity line = row.line();
        WorkReportEntity report = line.getReport();
        ProjectEntity project = report.getProject();
        BigDecimal unitServiceCost = WorkerCostDerivation.unitServiceCost(
                report.getClientUnitPrice(), ctx.resolved().tier(), ctx.baseTier());
        BigDecimal paid = line.getPaidAmount() == null ? BigDecimal.ZERO : line.getPaidAmount();
        return new PayoutLineDto(
                line.getId(),
                project == null ? null : project.getId(),
                project == null ? null : project.getName(),
                workItemName(report.getWorkItem(), ru),
                roomName(line.getRoom(), ru),
                report.getPerformedFrom(),
                report.getPerformedTo(),
                tierName(ctx, ru),
                ctx.resolved().baseTierFallback(),
                ctx.seniorityPct(),
                row.quantity(),
                unitServiceCost,
                row.cost(),
                paid,
                row.remainder(),
                allocation,
                row.cost() != null);
    }

    private static String tierName(CostContext ctx, boolean ru) {
        if (ctx.resolved().baseTierFallback()) {
            return ctx.baseType() == null ? null : workerTypeName(ctx.baseType(), ru);
        }
        WorkerTypeEntity type = ctx.profile() == null ? null : ctx.profile().workerType();
        return type == null ? null : workerTypeName(type, ru);
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

    private static String workerTypeName(WorkerTypeEntity type, boolean ru) {
        return firstNonBlank(
                ru ? type.getNameRU() : type.getNamePL(),
                ru ? type.getNamePL() : type.getNameRU(),
                type.getCode());
    }

    /** The room's free-form {@code label} when set, else the room type's localized name. */
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
