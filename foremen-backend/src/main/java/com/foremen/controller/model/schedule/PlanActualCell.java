package com.foremen.controller.model.schedule;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-02 (Requirement 1.3, 2, 3) — one {@code (workItem, room)} cell in a
 * {@link PlanActualCategory}'s drill-down: the planned quantity from the estimate against the
 * reported quantity, partitioned by report status into confirmed / pending / rejected buckets, plus
 * the contributing report references.
 *
 * <p>Quantity buckets (R2.2, R3.1): {@link #confirmedQuantity()} sums only {@code APPROVED} reports,
 * {@link #pendingQuantity()} sums {@code SUBMITTED} + {@code CHANGES_REQUESTED}, and
 * {@link #rejectedQuantity()} sums {@code REJECTED} (shown for context only — it contributes nothing
 * to completion or trajectory).
 *
 * <p>{@link #confirmedCompletion()} is {@code min(1, confirmedQuantity / plannedQuantity)} when
 * planned {@code > 0}; a cell the estimate does not plan but that has a confirmed actual is flagged
 * {@link #noPlan()} and treated as a fully complete unplanned actual (R2.3); an over-report
 * (confirmed {@code >} planned) sets {@link #overReported()} and clamps the displayed completion at
 * 1 (R2.4).
 *
 * @param workItemId          the work item id
 * @param workItemName        the localized work item name (ru → pl → code)
 * @param unitCode            the work item's measurement unit label (localized)
 * @param roomId              the room id
 * @param roomName            the room label (falls back to the room id when unlabeled)
 * @param plannedQuantity     the estimate's planned quantity for the cell; {@code null} when the
 *                            estimate plans nothing for it ({@link #noPlan()} is then {@code true})
 * @param confirmedQuantity   the summed APPROVED reported quantity (R2.2)
 * @param pendingQuantity     the summed SUBMITTED + CHANGES_REQUESTED reported quantity (R2.2)
 * @param rejectedQuantity    the summed REJECTED reported quantity — context only (R3.2)
 * @param confirmedCompletion the confirmed completion ratio in {@code [0, 1]} (R2.3)
 * @param overReported        whether confirmed quantity exceeds the planned quantity (R2.4)
 * @param noPlan              whether the estimate plans nothing for this cell (R2.3)
 * @param reports             the contributing report references for the drill-down (R3.3)
 */
public record PlanActualCell(
        Long workItemId,
        String workItemName,
        String unitCode,
        Long roomId,
        String roomName,
        BigDecimal plannedQuantity,
        BigDecimal confirmedQuantity,
        BigDecimal pendingQuantity,
        BigDecimal rejectedQuantity,
        double confirmedCompletion,
        boolean overReported,
        boolean noPlan,
        List<PlanActualReportRef> reports
) {}
