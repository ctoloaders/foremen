package com.foremen.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.PlanActualProperties;
import com.foremen.controller.model.schedule.PlanActualBar;
import com.foremen.controller.model.schedule.PlanActualCategory;
import com.foremen.controller.model.schedule.PlanActualCell;
import com.foremen.controller.model.schedule.PlanActualMoney;
import com.foremen.controller.model.schedule.PlanActualReportRef;
import com.foremen.controller.model.schedule.PlanActualTotals;
import com.foremen.controller.model.schedule.PlanActualView;
import com.foremen.controller.model.schedule.ScheduleBarView;
import com.foremen.controller.model.schedule.ScheduleRowView;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.service.schedule.TrajectoryCalculator;
import com.foremen.service.schedule.TrajectoryCalculator.TrajectoryStatus;

/**
 * FOR-06-02 (Requirement 1–4; design §4.1) — assembles the read-only plan/actual overlay of a
 * project's execution-stage Gantt. It is a pure query assembler: it <strong>writes no domain
 * data</strong>, creates no {@code project_schedules} / {@code project_schedule_bars} row, and bumps
 * no schedule version (R1.6). It joins three worlds it does not own:
 *
 * <ul>
 *   <li><strong>Plan (dates + framing)</strong> — {@link ProjectScheduleService#getView(Long)}
 *       supplies the schedule anchor, finish day, the per-category rows, the per-worker bars, the
 *       money-viewer gate (the view's {@code currency} is non-null only for a Money_Viewer), and the
 *       localized category / work-item / room names. Reusing {@code getView} means the overlay
 *       inherits the schedule's project-access gate (R6.4) and all its localization.</li>
 *   <li><strong>Plan (quantity)</strong> — {@link EstimateLineRoomQtyEntity#getQuantity()} per
 *       {@code (workItem, room)} (R2.1), read through the estimate lines.</li>
 *   <li><strong>Actual</strong> — the reported quantity per {@code (workItem, room)} bucketed by
 *       {@link WorkReportStatus} into confirmed (APPROVED) / pending (SUBMITTED + CHANGES_REQUESTED)
 *       / rejected (REJECTED) (R2.2, R3), plus the per-report drill-down refs (R3.3), WORKER-filtered
 *       to own records (R3.4).</li>
 * </ul>
 *
 * <p>Completion is a quantity ratio (R2); trajectory is a time/pace projection derived from the
 * <em>confirmed</em> bucket only via the pure {@link TrajectoryCalculator} (R4.5). All figures are
 * derived on read and never persisted (R2.6).
 */
@Service
@Transactional(readOnly = true)
public class PlanActualService {

    /** The {@code ROLE_<code>} authority prefix used to read the caller's role code. */
    private static final String ROLE_PREFIX = "ROLE_";

    /** The project role code whose drill-down is restricted to own records (R3.4). */
    private static final String WORKER_ROLE_CODE = "WORKER";

    /** Report statuses whose reported quantity counts as confirmed fact (R2.2). */
    private static final java.util.Set<WorkReportStatus> CONFIRMED =
            java.util.Set.of(WorkReportStatus.APPROVED);

    /** Report statuses whose reported quantity is pending review (R2.2). */
    private static final java.util.Set<WorkReportStatus> PENDING =
            java.util.Set.of(WorkReportStatus.SUBMITTED, WorkReportStatus.CHANGES_REQUESTED);

    private final ProjectScheduleService projectScheduleService;
    private final EstimateLineDao estimateLineDao;
    private final WorkReportDao workReportDao;
    private final PlanActualProperties planActualProperties;

    public PlanActualService(ProjectScheduleService projectScheduleService,
                             EstimateLineDao estimateLineDao,
                             WorkReportDao workReportDao,
                             PlanActualProperties planActualProperties) {
        this.projectScheduleService = projectScheduleService;
        this.estimateLineDao = estimateLineDao;
        this.workReportDao = workReportDao;
        this.planActualProperties = planActualProperties;
    }

    /**
     * Builds the {@link PlanActualView} of a project (R1). The {@code WORK_SCHEDULE} READ grant is
     * enforced upstream by the {@code PermissionInterceptor}; the project-access gate (404 for a
     * missing / out-of-scope project) is inherited from {@link ProjectScheduleService#getView(Long)},
     * which this method calls first. Strictly read-only (R1.6).
     *
     * @param projectId the project whose overlay is read
     * @return the plan/actual view (HTTP 200 payload); never {@code null}
     */
    public PlanActualView getPlanActual(Long projectId) {
        // 1 — plan framing (anchor, finish, categories, bars, money gate, localized names) + access
        //     gate, all inherited from the planning-Gantt read. No write happens here.
        ScheduleView schedule = projectScheduleService.getView(projectId);
        ProjectEntity project = projectScheduleService.loadAccessibleProject(projectId);
        LocalDate anchor = schedule.anchorDate();
        boolean moneyViewer = schedule.currency() != null;
        boolean russian = isRussianLocale();

        // 2 — planned quantity per (workItem, room) + the (workItem → category) map + unit/room names,
        //     from the estimate lines (fetch-joined: work item → category, unit).
        List<EstimateLineEntity> lines =
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId);
        PlanIndex plan = indexPlan(lines, russian);

        // 3 — reported quantity per (workItem, room) bucketed by status, across all workers (R2.2).
        Map<CellKey, Buckets> actual = indexActual(projectId);

        // 4 — drill-down report refs per (workItem, room); WORKER-filtered to own records (R3.4).
        Map<CellKey, List<PlanActualReportRef>> refs = indexReportRefs(projectId);

        // 5 — unit prices per work item (for the money-viewer revenue roll-up, R6.5).
        Map<Long, BigDecimal> unitPriceByWorkItem = indexUnitPrices(lines);

        LocalDate today = LocalDate.now();
        double riskMargin = planActualProperties.riskMargin();

        List<PlanActualCategory> categories = new ArrayList<>(schedule.rows().size());
        BigDecimal projPlanned = BigDecimal.ZERO;
        BigDecimal projConfirmed = BigDecimal.ZERO;
        BigDecimal projPending = BigDecimal.ZERO;
        BigDecimal projPlannedValue = BigDecimal.ZERO;
        BigDecimal projConfirmedValue = BigDecimal.ZERO;
        double weightedCompletionSum = 0.0;
        int onTrack = 0;
        int atRisk = 0;
        int overdue = 0;
        Double overallProjectedFinish = null;

        for (ScheduleRowView row : schedule.rows()) {
            Long categoryId = row.workCategoryId();
            CategoryPlan catPlan = plan.byCategory.getOrDefault(categoryId, CategoryPlan.EMPTY);

            // --- build the drill-down cells for this category's (workItem, room) pairs ---
            List<PlanActualCell> cells = new ArrayList<>();
            BigDecimal catPlanned = BigDecimal.ZERO;
            BigDecimal catConfirmed = BigDecimal.ZERO;
            BigDecimal catPending = BigDecimal.ZERO;
            BigDecimal catRejected = BigDecimal.ZERO;
            BigDecimal catPlannedValue = BigDecimal.ZERO;
            BigDecimal catConfirmedValue = BigDecimal.ZERO;
            double weightedCellSum = 0.0; // Σ (cellCompletion × plannedWeight) within the category
            boolean catOverReported = false;

            for (CellPlan cell : catPlan.cells) {
                CellKey key = new CellKey(cell.workItemId, cell.roomId);
                Buckets b = actual.getOrDefault(key, Buckets.EMPTY);
                BigDecimal planned = cell.plannedQuantity; // may be null
                BigDecimal confirmed = b.confirmed;
                BigDecimal pending = b.pending;
                BigDecimal rejected = b.rejected;
                boolean noPlan = planned == null || planned.signum() <= 0;
                boolean overReported = !noPlan && confirmed.compareTo(planned) > 0;
                double completion = cellCompletion(planned, confirmed, noPlan);

                catPlanned = catPlanned.add(nz(planned));
                catConfirmed = catConfirmed.add(confirmed);
                catPending = catPending.add(pending);
                catRejected = catRejected.add(rejected);
                if (!noPlan) {
                    weightedCellSum += completion * planned.doubleValue();
                }
                catOverReported = catOverReported || overReported;

                BigDecimal unitPrice = unitPriceByWorkItem.getOrDefault(cell.workItemId, BigDecimal.ZERO);
                catPlannedValue = catPlannedValue.add(unitPrice.multiply(nz(planned)));
                catConfirmedValue = catConfirmedValue.add(unitPrice.multiply(confirmed));

                cells.add(new PlanActualCell(
                        cell.workItemId,
                        cell.workItemName,
                        cell.unitCode,
                        cell.roomId,
                        cell.roomName,
                        planned,
                        confirmed,
                        pending,
                        rejected,
                        completion,
                        overReported,
                        noPlan,
                        refs.getOrDefault(key, List.of())));
            }

            // --- category completion: planned-quantity-weighted, else 1 when any confirmed on a
            //     plan-less category, else 0 (R2.5) ---
            boolean catNoPlan = catPlanned.signum() <= 0;
            double catCompletion;
            if (!catNoPlan) {
                catCompletion = Math.min(1.0, weightedCellSum / catPlanned.doubleValue());
            } else {
                catCompletion = catConfirmed.signum() > 0 ? 1.0 : 0.0;
            }

            // --- planned window from the category's bars (R1.3, design §3) ---
            Window window = windowOf(row.bars());

            // --- trajectory from the confirmed bucket only (R4) ---
            double elapsedShare = elapsedShare(anchor, today, window);
            TrajectoryStatus status =
                    TrajectoryCalculator.classify(elapsedShare, catCompletion, riskMargin);
            String note = (anchor == null || window.empty) ? "noAnchor" : null;
            Double projectedFinish = null;
            if (!window.empty) {
                OptionalDouble pf = TrajectoryCalculator.projectFinishDay(
                        window.minStartDay, window.plannedDurationDays, elapsedShare, catCompletion);
                if (pf.isPresent()) {
                    projectedFinish = round2(pf.getAsDouble());
                    overallProjectedFinish = overallProjectedFinish == null
                            ? projectedFinish
                            : Math.max(overallProjectedFinish, projectedFinish);
                }
            }

            switch (status) {
                case ON_TRACK -> onTrack++;
                case AT_RISK -> atRisk++;
                case OVERDUE -> overdue++;
            }
            weightedCompletionSum += catCompletion * catPlanned.doubleValue();
            projPlanned = projPlanned.add(catPlanned);
            projConfirmed = projConfirmed.add(catConfirmed);
            projPending = projPending.add(catPending);
            projPlannedValue = projPlannedValue.add(catPlannedValue);
            projConfirmedValue = projConfirmedValue.add(catConfirmedValue);

            categories.add(new PlanActualCategory(
                    categoryId,
                    row.name(),
                    window.startDay,
                    window.finishDay,
                    window.plannedDurationDays,
                    scale2(catPlanned),
                    scale2(catConfirmed),
                    scale2(catPending),
                    scale2(catRejected),
                    catCompletion,
                    catOverReported,
                    catNoPlan,
                    status,
                    projectedFinish,
                    note,
                    toPlanActualBars(row.bars()),
                    cells));
        }

        double projectCompletion = projPlanned.signum() > 0
                ? Math.min(1.0, weightedCompletionSum / projPlanned.doubleValue())
                : (projConfirmed.signum() > 0 ? 1.0 : 0.0);

        PlanActualTotals totals = new PlanActualTotals(
                scale2(projPlanned),
                scale2(projConfirmed),
                scale2(projPending),
                projectCompletion,
                onTrack,
                atRisk,
                overdue,
                overallProjectedFinish);

        PlanActualMoney money = moneyViewer
                ? new PlanActualMoney(schedule.currency(), scale2(projPlannedValue), scale2(projConfirmedValue))
                : null;

        return new PlanActualView(
                projectId,
                anchor,
                project.getEndDate(),
                schedule.finishDay(),
                schedule.version(),
                OffsetDateTime.now(),
                totals,
                categories,
                money);
    }

    // ------------------------------------------------------------------------------------------
    // Plan index: planned quantity per (workItem, room), grouped by work category.
    // ------------------------------------------------------------------------------------------

    private PlanIndex indexPlan(List<EstimateLineEntity> lines, boolean russian) {
        Map<Long, List<CellPlan>> cellsByCategory = new LinkedHashMap<>();
        for (EstimateLineEntity line : lines) {
            WorkItemEntity workItem = line.getWorkItem();
            if (workItem == null || workItem.getWorkCategory() == null) {
                continue;
            }
            Long categoryId = workItem.getWorkCategory().getId();
            String workItemName = localizedWorkItemName(workItem, russian);
            String unitCode = localizedUnitLabel(line, russian);
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                RoomEntity room = roomQty.getRoom();
                if (room == null) {
                    continue;
                }
                cellsByCategory
                        .computeIfAbsent(categoryId, c -> new ArrayList<>())
                        .add(new CellPlan(
                                workItem.getId(),
                                workItemName,
                                unitCode,
                                room.getId(),
                                roomLabel(room),
                                roomQty.getQuantity()));
            }
        }
        Map<Long, CategoryPlan> byCategory = new LinkedHashMap<>();
        for (Map.Entry<Long, List<CellPlan>> e : cellsByCategory.entrySet()) {
            byCategory.put(e.getKey(), new CategoryPlan(e.getValue()));
        }
        return new PlanIndex(byCategory);
    }

    private Map<Long, BigDecimal> indexUnitPrices(List<EstimateLineEntity> lines) {
        Map<Long, BigDecimal> byWorkItem = new LinkedHashMap<>();
        for (EstimateLineEntity line : lines) {
            if (line.getWorkItem() != null && line.getWorkItem().getId() != null) {
                byWorkItem.putIfAbsent(line.getWorkItem().getId(), nz(line.getUnitPrice()));
            }
        }
        return byWorkItem;
    }

    // ------------------------------------------------------------------------------------------
    // Actual index: reported quantity per (workItem, room) bucketed by status.
    // ------------------------------------------------------------------------------------------

    private Map<CellKey, Buckets> indexActual(Long projectId) {
        Map<CellKey, Buckets> byCell = new LinkedHashMap<>();
        for (Object[] r : workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(projectId)) {
            Long workItemId = (Long) r[0];
            Long roomId = (Long) r[1];
            WorkReportStatus status = (WorkReportStatus) r[2];
            BigDecimal qty = nz((BigDecimal) r[3]);
            Buckets b = byCell.computeIfAbsent(new CellKey(workItemId, roomId), k -> new Buckets());
            if (CONFIRMED.contains(status)) {
                b.confirmed = b.confirmed.add(qty);
            } else if (PENDING.contains(status)) {
                b.pending = b.pending.add(qty);
            } else { // REJECTED — context only, contributes nothing to completion/trajectory
                b.rejected = b.rejected.add(qty);
            }
        }
        return byCell;
    }

    private Map<CellKey, List<PlanActualReportRef>> indexReportRefs(Long projectId) {
        List<Object[]> rows;
        Long workerScope = workerOwnRecordsScope();
        if (workerScope != null) {
            rows = workReportDao.findOwnReportRefsByProject(projectId, workerScope);
        } else {
            rows = workReportDao.findReportRefsByProject(projectId);
        }
        Map<CellKey, List<PlanActualReportRef>> byCell = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Long workItemId = (Long) r[0];
            Long roomId = (Long) r[1];
            PlanActualReportRef ref = new PlanActualReportRef(
                    (Long) r[2],
                    (WorkReportStatus) r[3],
                    (String) r[4],
                    (LocalDate) r[5],
                    (LocalDate) r[6],
                    nz((BigDecimal) r[7]));
            byCell.computeIfAbsent(new CellKey(workItemId, roomId), k -> new ArrayList<>()).add(ref);
        }
        return byCell;
    }

    // ------------------------------------------------------------------------------------------
    // Geometry + math helpers.
    // ------------------------------------------------------------------------------------------

    /** The planned window {@code [minStartDay, maxFinishDay]} across a category's bars (design §3). */
    private static Window windowOf(List<ScheduleBarView> bars) {
        if (bars == null || bars.isEmpty()) {
            return Window.EMPTY;
        }
        double minStart = Double.MAX_VALUE;
        double maxFinish = -Double.MAX_VALUE;
        boolean any = false;
        for (ScheduleBarView bar : bars) {
            if (bar.startDay() == null || bar.finishDay() == null) {
                continue;
            }
            minStart = Math.min(minStart, bar.startDay());
            maxFinish = Math.max(maxFinish, bar.finishDay());
            any = true;
        }
        if (!any) {
            return Window.EMPTY;
        }
        // Day values are 0.5-multiples; the planned window length in whole days is the span rounded up.
        int minStartDay = (int) Math.ceil(minStart);
        int plannedDurationDays = Math.max(1, (int) Math.ceil(maxFinish - minStart + 0.5));
        return new Window(false, minStart, maxFinish, minStartDay, plannedDurationDays);
    }

    /**
     * The elapsed share of a category's planned window: {@code (today − startDate + 1) ÷ duration}
     * clamped to {@code [0, 1]} when a calendar anchor + a non-empty window exist, else
     * {@link TrajectoryCalculator#NO_ANCHOR} (R4.1).
     */
    private static double elapsedShare(LocalDate anchor, LocalDate today, Window window) {
        if (anchor == null || window.empty) {
            return TrajectoryCalculator.NO_ANCHOR;
        }
        // Day 1 of the schedule is the anchor itself; the window starts at day `minStartDay`.
        LocalDate windowStart = anchor.plusDays(window.minStartDay - 1L);
        long elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(windowStart, today) + 1;
        double share = (double) elapsedDays / window.plannedDurationDays;
        if (share < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, share);
    }

    /** Cell completion {@code min(1, confirmed/planned)}; a plan-less confirmed cell is complete (R2.3). */
    private static double cellCompletion(BigDecimal planned, BigDecimal confirmed, boolean noPlan) {
        if (noPlan) {
            return confirmed.signum() > 0 ? 1.0 : 0.0;
        }
        double ratio = confirmed.doubleValue() / planned.doubleValue();
        return Math.min(1.0, Math.max(0.0, ratio));
    }

    private static List<PlanActualBar> toPlanActualBars(List<ScheduleBarView> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        List<PlanActualBar> out = new ArrayList<>(bars.size());
        for (ScheduleBarView bar : bars) {
            out.add(new PlanActualBar(
                    bar.projectMemberId(),
                    bar.workerName(),
                    bar.startDay(),
                    bar.durationDays(),
                    bar.finishDay()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Caller / locale helpers.
    // ------------------------------------------------------------------------------------------

    /**
     * The caller's user id when the caller is a WORKER (so the drill-down is own-records only, R3.4),
     * else {@code null} (full drill-down). ADMIN / MANAGER / FOREMAN / etc. see every report ref.
     */
    private static Long workerOwnRecordsScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        boolean isWorker = false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith(ROLE_PREFIX) && WORKER_ROLE_CODE.equals(a.substring(ROLE_PREFIX.length()))) {
                isWorker = true;
                break;
            }
        }
        if (!isWorker) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName().trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static boolean isRussianLocale() {
        java.util.Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    private static String localizedWorkItemName(WorkItemEntity workItem, boolean russian) {
        return localizedName(
                russian ? workItem.getNameRU() : workItem.getNamePL(),
                russian ? workItem.getNamePL() : workItem.getNameRU(),
                workItem.getCode());
    }

    private static String localizedUnitLabel(EstimateLineEntity line, boolean russian) {
        var unit = line.getUnit();
        if (unit == null) {
            return null;
        }
        return localizedName(
                russian ? unit.getNameRU() : unit.getNamePL(),
                russian ? unit.getNamePL() : unit.getNameRU(),
                unit.getCode());
    }

    /** A room's display label — its free-text label, or {@code "#<id>"} when unlabeled. */
    private static String roomLabel(RoomEntity room) {
        if (room.getLabel() != null && !room.getLabel().isBlank()) {
            return room.getLabel();
        }
        return "#" + room.getId();
    }

    private static String localizedName(String preferred, String fallback, String code) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return code;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static Double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------------------------------
    // Internal index records.
    // ------------------------------------------------------------------------------------------

    private record PlanIndex(Map<Long, CategoryPlan> byCategory) {}

    private record CategoryPlan(List<CellPlan> cells) {
        static final CategoryPlan EMPTY = new CategoryPlan(List.of());
    }

    private record CellPlan(
            Long workItemId, String workItemName, String unitCode,
            Long roomId, String roomName, BigDecimal plannedQuantity) {}

    private record CellKey(Long workItemId, Long roomId) {}

    private static final class Buckets {
        static final Buckets EMPTY = new Buckets();
        BigDecimal confirmed = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal rejected = BigDecimal.ZERO;
    }

    private record Window(
            boolean empty, double startDay, double finishDay, int minStartDay, int plannedDurationDays) {
        static final Window EMPTY = new Window(true, 0, 0, 0, 0);
    }
}
