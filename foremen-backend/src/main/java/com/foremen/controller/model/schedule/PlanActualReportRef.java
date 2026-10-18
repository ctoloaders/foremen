package com.foremen.controller.model.schedule;

import com.foremen.dao.model.WorkReportStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-06-02 (Requirement 3.3) — a reference to one FOR-06-01 work report that contributed reported
 * quantity to a {@link PlanActualCell}, shown in the per-cell drill-down so a reviewer can jump
 * from the overlay to the report in the Work Reporting tab.
 *
 * <p>All statuses appear here for context — {@code APPROVED} (confirmed fact), {@code SUBMITTED} /
 * {@code CHANGES_REQUESTED} (pending), and {@code REJECTED} (which contributes zero quantity
 * everywhere else but is still listed so a reviewer sees it). For a WORKER caller the owning service
 * filters this list to the caller's own reports (R3.4) while keeping the cell's aggregate totals.
 *
 * @param reportId      the work report id (the drill-down deep-links to it, R3.3)
 * @param status        the report status ({@code SUBMITTED}/{@code APPROVED}/{@code REJECTED}/
 *                      {@code CHANGES_REQUESTED})
 * @param workerName    the worker whose work the report records (localized/display name)
 * @param performedFrom the start of the performed-work interval
 * @param performedTo   the end of the performed-work interval
 * @param quantity      the report's reported quantity for this cell's {@code (workItem, room)}
 */
public record PlanActualReportRef(
        Long reportId,
        WorkReportStatus status,
        String workerName,
        LocalDate performedFrom,
        LocalDate performedTo,
        BigDecimal quantity
) {}
