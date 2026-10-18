package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-01 Amendment A1 (Requirement 14; design A1.1) — the read projection behind the matrix
 * batch-authoring surface: for a given {@code (project, worker)} it returns the matrix source the
 * client renders as rows (work items grouped by work category) × columns (the project's rooms),
 * each cell carrying the planned and already-reported quantity for that {@code (workItem, room)}.
 *
 * <p>The projection is built server-side by {@code WorkReportService.progress(projectId, workerId)}:
 * it intersects the worker's <b>Covering_Works</b> (R3.1 — work items whose work category is in the
 * worker's {@code project_member_work_categories}) with the project's estimate work items, reads
 * each {@code (workItem, room)} {@link Cell#planQuantity()} from {@code EstimateLineRoomQty}, and
 * sums the {@link Cell#reportedQuantity()} over the worker's reports for that {@code (workItem,
 * room)} whose status is {@code SUBMITTED}, {@code CHANGES_REQUESTED}, or {@code APPROVED} (R14.2;
 * {@code REJECTED} excluded). A work item every one of whose planned rooms is already at
 * {@code ≥ 100 %} is omitted (the "not-yet-fully-reported" filter, R14.4).
 *
 * <p>All display names are localized {@code ru → pl → code/label} per the request locale (R12.1,
 * R14.1); the DTO carries no raw i18n columns.
 *
 * @param projectId  the project the matrix is for
 * @param workerId   the worker the matrix is for
 * @param rooms      the column axis — the rooms referenced by the included work items' plans, each
 *                   with its localized name
 * @param categories the row groups — the Covering_Work categories (in declaration / {@code orderNo}
 *                   order), each holding its not-yet-fully-reported work-item rows
 */
public record WorkReportProgressDto(
        Long projectId,
        Long workerId,
        List<RoomColumn> rooms,
        List<CategoryGroup> categories) {

    /**
     * One column of the matrix — a room referenced by at least one included work item's plan.
     *
     * @param roomId   the room FK
     * @param roomName the room's localized display name (ru → pl → label/code, R12.1)
     */
    public record RoomColumn(Long roomId, String roomName) {
    }

    /**
     * A row group — a work category holding the worker's not-yet-fully-reported Covering_Work rows,
     * emitted in the category's declaration order ({@code WorkCategory.orderNo}, R17.2).
     *
     * @param workCategoryId   the category FK (the grouping key)
     * @param workCategoryName the category's localized display name (ru → pl → code, R12.1)
     * @param workItems        the not-yet-fully-reported work-item rows in this category
     */
    public record CategoryGroup(
            Long workCategoryId,
            String workCategoryName,
            List<WorkItemRow> workItems) {
    }

    /**
     * One matrix row — a Covering_Work work item on the project's estimate, with its localized name,
     * its measurement unit, and the per-room cells (one per column in {@link #rooms()} order).
     *
     * @param workItemId   the work item FK
     * @param workItemName the work item's localized display name (ru → pl → code, R12.1)
     * @param unitCode     the measurement unit code shown as the cell unit label (e.g. {@code m2})
     * @param cells        the per-room cells, aligned to the {@link WorkReportProgressDto#rooms()}
     *                     column order
     */
    public record WorkItemRow(
            Long workItemId,
            String workItemName,
            String unitCode,
            List<Cell> cells) {
    }

    /**
     * One matrix cell — the plan and reported quantity for a single {@code (workItem, room)}.
     *
     * <p>{@code planQuantity} is {@code null} when the estimate has no plan for the pair (an
     * absolute-only cell, R14.3/R2.3); in that case {@code reportedPercent} is also {@code null}.
     * Otherwise {@code reportedPercent = round(reportedQuantity / planQuantity × 100)}, capped at
     * 100 for display (R14.3).
     *
     * @param roomId           the room FK this cell belongs to (matches a {@link RoomColumn})
     * @param planQuantity     the planned quantity from {@code EstimateLineRoomQty}, or {@code null}
     *                         when no plan exists for the {@code (workItem, room)}
     * @param reportedQuantity the sum already reported for the {@code (worker, workItem, room)} over
     *                         non-{@code REJECTED} reports (R14.2); never {@code null} (0 when none)
     * @param reportedPercent  {@code round(reportedQuantity / planQuantity × 100)} capped at 100
     *                         when a plan exists, else {@code null} (R14.3)
     */
    public record Cell(
            Long roomId,
            BigDecimal planQuantity,
            BigDecimal reportedQuantity,
            Integer reportedPercent) {
    }
}
