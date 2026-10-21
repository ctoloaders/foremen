package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.workercost.WorkerCostDerivation;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;
import com.foremen.service.workercost.WorkerCostService;

import java.time.LocalDate;

/**
 * FOR-06-11 (Requirement 3, design §PayoutSuggestionService) — the read-only create-dialog preview
 * builder: it folds the FOR-06-10 {@code WorkerCostService} per-line {@code Cost_Value}
 * <strong>verbatim</strong> over the worker's unpaid {@code APPROVED} report lines in the chosen
 * period and layers the pure {@link AdvanceCreditCalculator} credit arithmetic on top. It adds
 * <strong>no</strong> cost formula of its own — the unit {@code Worker_Service_Cost} and the per-line
 * {@code Cost_Value} come from exactly one derivation ({@link WorkerCostDerivation}, the same seam
 * {@code WorkerCostService} uses), scaled by each report line's own quantity (Non-Goal / R3.1).
 *
 * <p>Two branches:
 * <ul>
 *   <li><b>Non-advance</b> period (R3.1–R3.4): read the worker's unpaid ({@code paidByPayout} null)
 *       {@code APPROVED} report lines whose report overlaps {@code [from, to]}, cost each line
 *       through the single seam, sum the <b>available</b> {@code Cost_Value} into {@code gross},
 *       compute {@code appliedCredit = min(remainingCredit, gross)} and
 *       {@code suggested = max(0, gross − remainingCredit)}, and build the per-line breakdown. An
 *       <b>unavailable</b> (null/zero-rate) line is <b>marked</b> unavailable in the breakdown and
 *       <b>excluded</b> from the gross — never a fabricated zero — and is never linked (R3.4);</li>
 *   <li><b>Advance</b> period (R3.5): the basis is the worker's <b>planned</b> work (costed through
 *       the same seam by the planned quantity) and <b>no</b> advance-credit deduction applies
 *       ({@code appliedCredit = 0}) — an advance creates credit, it does not consume it.</li>
 * </ul>
 *
 * <p>This service persists nothing and emits no notifications. The set of lines a regular payout
 * actually links is resolved by {@link #linkableLines(Long, Long, LocalDate, LocalDate)} and handed
 * to {@link ReportLineLinker} by {@code WorkerPayoutService} inside the create transaction — the two
 * use the identical selection path, so the breakdown's gross and the linked set are consistent.
 */
@Service
public class PayoutSuggestionService {

    private final WorkReportDao workReportDao;
    private final WorkerPayoutDao workerPayoutDao;
    private final WorkerCostService workerCostService;
    private final AdvanceCreditCalculator advanceCreditCalculator;

    public PayoutSuggestionService(WorkReportDao workReportDao,
                                   WorkerPayoutDao workerPayoutDao,
                                   WorkerCostService workerCostService,
                                   AdvanceCreditCalculator advanceCreditCalculator) {
        this.workReportDao = workReportDao;
        this.workerPayoutDao = workerPayoutDao;
        this.workerCostService = workerCostService;
        this.advanceCreditCalculator = advanceCreditCalculator;
    }

    /**
     * Build the create-dialog preview for a worker + period, deciding the branch from {@code advance}
     * (the caller derives it via {@code AdvanceDeriver}: {@code periodTo} after the creation date,
     * R5.3). For a non-advance period the gross is {@code Σ Cost_Value} over the unpaid in-period
     * available lines and the credit arithmetic applies (R3.1–R3.4); for an advance the basis is the
     * worker's planned work and no credit is deducted (R3.5).
     *
     * @param projectId the owning project id (project scope is enforced by the caller /
     *                  {@code WorkerPayoutService} before this read)
     * @param workerId  the worker the payout preview is for
     * @param from      the period start (inclusive)
     * @param to        the period end (inclusive)
     * @param advance   {@code true} for an advance period (planned basis, no credit), else the
     *                  reports-based gross
     * @return the {@link PayoutSuggestionDto} preview; never {@code null}
     */
    @Transactional(readOnly = true)
    public PayoutSuggestionDto suggest(Long projectId, Long workerId,
                                       LocalDate from, LocalDate to, boolean advance) {
        boolean ru = isRussianLocale();
        CostContext ctx = loadCostContext(projectId);
        List<WorkReportEntity> reports =
                workReportDao.findApprovedInPeriodWithRooms(projectId, workerId, from, to);

        List<PayoutLineDto> breakdown = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;

        for (WorkReportEntity report : reports) {
            if (report == null) {
                continue;
            }
            ResolvedTier resolved = resolveTier(report, ctx);
            String tierName = tierName(resolved, ctx, ru);
            String workItemName = workItemName(report.getWorkItem(), ru);

            for (WorkReportRoomEntity line : report.getRooms()) {
                if (line == null) {
                    continue;
                }
                // Advance basis uses the planned quantity snapshot; the regular basis uses the
                // reported (actual) quantity. Both are costed through the single FOR-06-10 formula.
                BigDecimal quantity = advance ? line.getPlanQuantity() : line.getQuantity();
                // A non-advance payout only ever offers an UNPAID line (paidByPayout null, R6.2); an
                // already-linked line is filtered out so it is never paid twice.
                if (!advance && line.getPaidByPayout() != null) {
                    continue;
                }

                BigDecimal unitServiceCost = WorkerCostDerivation.unitServiceCost(
                        report.getClientUnitPrice(), resolved.tier(), ctx.baseTier());
                BigDecimal costValue = WorkerCostDerivation.costValue(
                        report.getClientUnitPrice(), resolved.tier(), ctx.baseTier(), quantity);
                boolean available = costValue != null;

                breakdown.add(new PayoutLineDto(
                        line.getId(),
                        workItemName,
                        roomName(line.getRoom(), ru),
                        report.getPerformedFrom(),
                        report.getPerformedTo(),
                        tierName,
                        resolved.baseTierFallback(),
                        quantity,
                        unitServiceCost,
                        costValue,
                        available));

                // Only an available line contributes to the gross — an unavailable (null/zero-rate)
                // line is marked and excluded, never a fabricated zero (R3.4).
                if (available) {
                    gross = gross.add(costValue);
                }
            }
        }

        if (advance) {
            // An advance creates credit, it does not consume it: no deduction (R3.5). The suggested
            // amount is the planned-basis gross; appliedCredit is 0.
            BigDecimal suggested = gross.max(BigDecimal.ZERO);
            BigDecimal remaining = advanceCreditCalculator.remaining(
                    workerPayoutDao.findByProjectAndWorker(projectId, workerId));
            return new PayoutSuggestionDto(
                    gross, remaining, BigDecimal.ZERO, suggested, true, breakdown);
        }

        BigDecimal remainingCredit = advanceCreditCalculator.remaining(
                workerPayoutDao.findByProjectAndWorker(projectId, workerId));
        BigDecimal appliedCredit = advanceCreditCalculator.applied(remainingCredit, gross);
        BigDecimal suggested = advanceCreditCalculator.suggested(gross, remainingCredit);
        return new PayoutSuggestionDto(
                gross, remainingCredit, appliedCredit, suggested, false, breakdown);
    }

    /**
     * Resolve the exact unpaid, in-period, <b>available</b> report lines a <b>regular</b> payout
     * covers — the lines {@code WorkerPayoutService} hands to {@link ReportLineLinker} to link
     * inside the create transaction (R4.3, R6.1). This is the same selection path {@link #suggest}
     * folds into the gross, so the linked set equals the gross-contributing set (an unavailable line
     * is excluded and never linked, R3.4). Never call this for an advance — an advance links no line
     * (R6.4).
     *
     * @param projectId the owning project id
     * @param workerId  the worker the payout is for
     * @param from      the period start (inclusive)
     * @param to        the period end (inclusive)
     * @return the covered unpaid available report lines, empty when none
     */
    @Transactional(readOnly = true)
    public List<WorkReportRoomEntity> linkableLines(Long projectId, Long workerId,
                                                    LocalDate from, LocalDate to) {
        CostContext ctx = loadCostContext(projectId);
        List<WorkReportEntity> reports =
                workReportDao.findApprovedInPeriodWithRooms(projectId, workerId, from, to);
        List<WorkReportRoomEntity> lines = new ArrayList<>();
        for (WorkReportEntity report : reports) {
            if (report == null) {
                continue;
            }
            ResolvedTier resolved = resolveTier(report, ctx);
            for (WorkReportRoomEntity line : report.getRooms()) {
                if (line == null || line.getPaidByPayout() != null) {
                    continue; // only unpaid lines are linkable (R6.2)
                }
                BigDecimal costValue = WorkerCostDerivation.costValue(
                        report.getClientUnitPrice(), resolved.tier(), ctx.baseTier(), line.getQuantity());
                if (costValue != null) {
                    lines.add(line); // an unavailable line is never linked (R3.4)
                }
            }
        }
        return lines;
    }

    // --- cost-seam plumbing (reused verbatim from FOR-06-10, no second formula) -----------------

    /** The per-project cost inputs resolved once: the active base worker type + its tier, and the
     *  {@code userId → active workerType} member map — all from the FOR-06-10 seam (R3.1). */
    private CostContext loadCostContext(Long projectId) {
        WorkerTypeEntity baseType = workerCostService.findBaseType();
        WorkerTypeTier baseTier = baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        Map<Long, WorkerTypeEntity> activeTypeByWorkerId =
                workerCostService.activeWorkerTypesByWorkerId(projectId);
        return new CostContext(baseType, baseTier, activeTypeByWorkerId);
    }

    private static ResolvedTier resolveTier(WorkReportEntity report, CostContext ctx) {
        UserEntity worker = report.getWorker();
        Long workerId = worker == null ? null : worker.getId();
        return WorkerCostDerivation.tierFor(workerId, ctx.activeTypeByWorkerId(), ctx.baseType());
    }

    private String tierName(ResolvedTier resolved, CostContext ctx, boolean ru) {
        if (resolved.baseTierFallback()) {
            return ctx.baseType() == null ? null : workerTypeName(ctx.baseType(), ru);
        }
        WorkerTypeEntity type = ctx.activeTypeByWorkerId().get(resolved.workerTypeId());
        return type == null ? null : workerTypeName(type, ru);
    }

    /** The per-project cost inputs resolved once (the FOR-06-10 base tier + member-type map). */
    private record CostContext(WorkerTypeEntity baseType,
                               WorkerTypeTier baseTier,
                               Map<Long, WorkerTypeEntity> activeTypeByWorkerId) {
    }

    // --- localized-name helpers (ru → pl → code), mirroring WorkerCostService (R8.1) -----------

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

    /**
     * The room's localized display name: its free-form {@code label} when set, else the room type's
     * localized name ({@code ru → pl → code}) — the room carries no own i18n name columns (R8.1).
     */
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
