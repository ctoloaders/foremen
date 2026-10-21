package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-06-11 (Requirements 3.3, 3.4) — one line of the payout-suggestion breakdown: a single unpaid
 * {@code APPROVED} report line (its backing {@code WorkReportRoom}) contributing to the gross, as
 * shown in the create dialog's breakdown table.
 *
 * <p>Populated from FOR-06-10's {@code WorkerCostLineDto} (the single cost seam) — payroll adds no
 * cost arithmetic of its own (Non-Goal / R3.1). An <b>unavailable</b> (null/zero-rate) line has
 * {@link #available} {@code false}, a {@code null} {@link #unitServiceCost} / {@link #costValue}, is
 * <b>excluded</b> from the gross, and is never linked; consumers render it as a localized {@code —},
 * never a fabricated zero (Requirement 3.4). All names use the {@code ru → pl → code} fallback
 * (Requirement 8.1).
 *
 * @param workReportRoomId the backing {@code WorkReportRoom} line id
 * @param workItemName     the reported work item's localized name
 * @param roomName         the room's localized name
 * @param periodFrom       the reported work's start date
 * @param periodTo         the reported work's end date
 * @param workerTypeName   the resolved {@code WorkerType}'s localized name
 * @param baseTierFallback {@code true} when the base tier was used via fallback (R2.4)
 * @param quantity         the reported quantity
 * @param unitServiceCost  the unit {@code Worker_Service_Cost}; {@code null} ⇒ unavailable (R3.4)
 * @param costValue        {@code unitServiceCost × quantity}; {@code null} ⇒ unavailable (R3.4)
 * @param available        {@code false} when the cost is unavailable (excluded from the gross, R3.4)
 */
public record PayoutLineDto(
        Long workReportRoomId,
        String workItemName,
        String roomName,
        LocalDate periodFrom,
        LocalDate periodTo,
        String workerTypeName,
        boolean baseTierFallback,
        BigDecimal quantity,
        BigDecimal unitServiceCost,
        BigDecimal costValue,
        boolean available) {
}
