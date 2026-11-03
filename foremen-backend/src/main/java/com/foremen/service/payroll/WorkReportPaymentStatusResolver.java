package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.workercost.WorkerCostDerivation;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;
import com.foremen.service.workercost.WorkerCostService;
import com.foremen.service.workercost.WorkerProfile;

/**
 * FOR-07-06 (Requirement 12.1, 12.2; design §Work-report payment status) — derives the payment
 * status of work-report room lines and the report aggregate for the work-report read DTOs.
 *
 * <p>Costing matches {@code PayoutSuggestionService.lineCost}: each room line is costed on its own
 * reported {@code quantity} at the report's frozen {@code clientUnitPrice}, with the worker user's
 * effective tier ({@link WorkerProfile#resolve}) and the base tier — so the badge agrees with what
 * payroll would pay. The base type and the worker profiles are loaded <b>once per call</b> (one
 * call per page / response), never per report (no N+1).
 *
 * <p>Only {@code APPROVED} reports carry a status; any other report is absent from the result
 * (⇒ {@code null} status, R12.1). Nothing beyond the line's {@code paidAmount} is read — no payout
 * ids, channels, or balances (R12.2).
 *
 * <p>Kept as a separate component (rather than inlined in {@code WorkReportService}) so the
 * costing is isolated and unit-testable; {@link WorkerCostService} does not depend on
 * {@code WorkReportService}, so there is no bean cycle either way.
 */
@Component
public class WorkReportPaymentStatusResolver {

    private final WorkerCostService workerCostService;

    public WorkReportPaymentStatusResolver(WorkerCostService workerCostService) {
        this.workerCostService = workerCostService;
    }

    /**
     * Payment status of one APPROVED report.
     *
     * @param status the report aggregate ({@link ReportPaymentStatus#of})
     * @param lines  {@code roomLineId → status} for every persisted room line
     */
    public record ReportPayment(LinePaymentStatus status, Map<Long, LinePaymentStatus> lines) {
    }

    /**
     * Resolves the payment status of every APPROVED report in {@code reports}.
     *
     * @param reports the page / response of reports (any status; nulls ignored)
     * @return {@code reportId → ReportPayment} for the APPROVED reports only; never {@code null}
     */
    public Map<Long, ReportPayment> resolve(Collection<WorkReportEntity> reports) {
        if (reports == null || reports.isEmpty()) {
            return Map.of();
        }
        Set<Long> workerIds = new LinkedHashSet<>();
        boolean anyApproved = false;
        for (WorkReportEntity report : reports) {
            if (isApproved(report)) {
                anyApproved = true;
                if (report.getWorker() != null && report.getWorker().getId() != null) {
                    workerIds.add(report.getWorker().getId());
                }
            }
        }
        if (!anyApproved) {
            return Map.of(); // nothing to cost — skip the base-type / profile queries entirely
        }

        WorkerTypeEntity baseType = workerCostService.findBaseType();
        WorkerTypeTier baseTier = baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        Map<Long, WorkerProfile> profiles = workerIds.isEmpty()
                ? Map.of()
                : workerCostService.workerProfilesById(workerIds);

        Map<Long, ReportPayment> result = new HashMap<>();
        for (WorkReportEntity report : reports) {
            if (!isApproved(report) || report.getId() == null) {
                continue;
            }
            Long workerId = report.getWorker() == null ? null : report.getWorker().getId();
            WorkerProfile profile = workerId == null || profiles == null ? null : profiles.get(workerId);
            ResolvedTier resolved = WorkerProfile.resolve(profile, baseType);
            result.put(report.getId(), statusOf(report, resolved.tier(), baseTier));
        }
        return result;
    }

    /** Pure per-report derivation: cost each room line on its quantity, then aggregate. */
    static ReportPayment statusOf(WorkReportEntity report, WorkerTypeTier tier, WorkerTypeTier baseTier) {
        Map<Long, LinePaymentStatus> lines = new LinkedHashMap<>();
        List<LinePaymentStatus> all = new ArrayList<>();
        if (report.getRooms() != null) {
            for (WorkReportRoomEntity line : report.getRooms()) {
                if (line == null) {
                    continue;
                }
                BigDecimal cost = WorkerCostDerivation.costValue(
                        report.getClientUnitPrice(), tier, baseTier, line.getQuantity());
                LinePaymentStatus status = LinePaymentStatus.of(cost, line.getPaidAmount());
                all.add(status);
                if (line.getId() != null) {
                    lines.put(line.getId(), status);
                }
            }
        }
        return new ReportPayment(ReportPaymentStatus.of(all), lines);
    }

    private static boolean isApproved(WorkReportEntity report) {
        return report != null && report.getStatus() == WorkReportStatus.APPROVED;
    }
}
