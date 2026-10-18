package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.service.schedule.TrajectoryCalculator.TrajectoryStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-02 (Requirement 1.3, 2, 3, 4) — the plan/actual overlay row for one work category: its
 * planned time window (rolled up from the planning-Gantt bars), its planned vs. reported quantity
 * (bucketed confirmed / pending / rejected), its confirmed completion, its trajectory classification
 * with a projected finish, the per-worker planned bars (carried from the schedule), and the
 * per-cell drill-down.
 *
 * <p>The planned window ({@link #startDay()}, {@link #finishDay()}, {@link #plannedDurationDays()})
 * is {@code [min bar start, max bar finish]} across the category's bars (design §3); a category with
 * no scheduled bar carries an empty window (zeros) and {@code ON_TRACK} (R1.5).
 * {@link #confirmedCompletion()} is the planned-quantity-weighted roll-up of the cells (R2.5);
 * {@link #trajectoryStatus()} is derived from the confirmed bucket only (R4.5). {@link #note()}
 * carries a stable key for a special case — currently {@code "noAnchor"} when the schedule has no
 * calendar anchor (R4.1) — or {@code null}.
 *
 * @param workCategoryId      the work category id
 * @param name                the localized work category name (ru → pl → code)
 * @param startDay            the planned window start day (0.5-multiple); 0 when nothing is scheduled
 * @param finishDay           the planned window finish day (0.5-multiple); 0 when nothing is scheduled
 * @param plannedDurationDays the planned window length in days; 0 when nothing is scheduled
 * @param plannedQuantity     the category's rolled-up planned quantity
 * @param confirmedQuantity   the rolled-up APPROVED reported quantity
 * @param pendingQuantity     the rolled-up SUBMITTED + CHANGES_REQUESTED reported quantity
 * @param rejectedQuantity    the rolled-up REJECTED reported quantity (context only)
 * @param confirmedCompletion the planned-quantity-weighted confirmed completion in {@code [0, 1]}
 * @param overReported        whether any cell is over-reported (R2.4)
 * @param noPlan              whether the category has zero total planned quantity (R2.5)
 * @param trajectoryStatus    the {@code ON_TRACK}/{@code AT_RISK}/{@code OVERDUE} classification (R4)
 * @param projectedFinishDay  the pace-extrapolated projected finish day; {@code null} when it cannot
 *                            be projected (no confirmed progress, or no calendar anchor)
 * @param note                a stable special-case key ({@code "noAnchor"}) or {@code null}
 * @param bars                the per-worker planned bars carried from the schedule (R1.3)
 * @param cells               the {@code (workItem, room)} drill-down cells
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanActualCategory(
        Long workCategoryId,
        String name,
        double startDay,
        double finishDay,
        int plannedDurationDays,
        BigDecimal plannedQuantity,
        BigDecimal confirmedQuantity,
        BigDecimal pendingQuantity,
        BigDecimal rejectedQuantity,
        double confirmedCompletion,
        boolean overReported,
        boolean noPlan,
        TrajectoryStatus trajectoryStatus,
        Double projectedFinishDay,
        String note,
        List<PlanActualBar> bars,
        List<PlanActualCell> cells
) {}
