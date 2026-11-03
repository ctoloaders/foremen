package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-06 (Requirement 4.6) — one line of the worker-wide payout-suggestion breakdown: a single
 * {@code APPROVED} report line (its backing {@code WorkReportRoom}) on any of the worker's projects.
 *
 * <p>Costs come from the single cost seam ({@code WorkerCostDerivation}) — payroll adds no cost
 * arithmetic of its own. An <b>unavailable</b> (null/zero-rate) line has {@link #available}
 * {@code false}, a {@code null} {@link #unitServiceCost} / {@link #costValue} / {@link #remainder},
 * is <b>excluded</b> from the gross, and is never allocated; consumers render it as a localized
 * {@code —}, never a fabricated zero (R4.4). All names use the {@code ru → pl → code} fallback.
 *
 * @param workReportRoomId   the backing {@code WorkReportRoom} line id
 * @param projectId          the owning project id
 * @param projectName        the owning project name
 * @param workItemName       the reported work item's localized name
 * @param roomName           the room's localized name
 * @param periodFrom         the reported work's start date
 * @param periodTo           the reported work's end date
 * @param workerTypeName     the resolved {@code WorkerType}'s localized name
 * @param baseTierFallback   {@code true} when the base tier was used via fallback (R2.2)
 * @param seniorityPct       the worker's seniority uplift fraction (R2.3)
 * @param quantity           the reported quantity (the planned quantity for an advance)
 * @param unitServiceCost    the unit {@code Worker_Service_Cost}; {@code null} ⇒ unavailable
 * @param costValue          {@code unitServiceCost × quantity}; {@code null} ⇒ unavailable
 * @param paidAmount         the amount already allocated to the line by earlier payouts
 * @param remainder          {@code max(0, costValue − paidAmount)}; {@code null} when unavailable or
 *                           for an advance basis
 * @param allocationPreview  what the suggested payout (plus applied credit) would allocate to this
 *                           line; {@code 0} for an unavailable line and for an advance (R4.7)
 * @param available          {@code false} when the cost is unavailable (excluded from the gross)
 */
public record PayoutLineDto(
        Long workReportRoomId,
        Long projectId,
        String projectName,
        String workItemName,
        String roomName,
        LocalDate periodFrom,
        LocalDate periodTo,
        String workerTypeName,
        boolean baseTierFallback,
        BigDecimal seniorityPct,
        BigDecimal quantity,
        BigDecimal unitServiceCost,
        BigDecimal costValue,
        BigDecimal paidAmount,
        BigDecimal remainder,
        BigDecimal allocationPreview,
        boolean available) {
}
