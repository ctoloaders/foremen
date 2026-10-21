package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportStatus;

/**
 * FOR-06-01 (design §4.1) — DAO for {@link WorkReportEntity}. The generic {@link AdminDao} CRUD +
 * {@code JpaSpecificationExecutor} surface is sufficient for the core service: {@code WorkReportService}
 * is a {@code ProjectScopedService}, so its LIST reads flow through the inherited specification
 * ({@code addRequiredQuery()} project filter, intersected with the WORKER own-records predicate in
 * the service), and its by-id reads/mutations go through the inherited {@code findById}/{@code save}
 * with the per-id project-access guard.
 *
 * <p>Amendment A1 (Requirement 14, design A1.1) adds the one aggregate finder the progress matrix
 * projection needs: the already-reported quantity per {@code (workItem, room)} for a given
 * {@code (project, worker)}.
 */
@Repository
public interface WorkReportDao extends AdminDao<WorkReportEntity, Long> {

    /**
     * FOR-06-01 A1 (R14.2) — the already-reported quantity per {@code (workItem, room)} for one
     * {@code (project, worker)}: {@code Σ WorkReportRoom.quantity} over every report of that
     * {@code (project, worker)} whose status is in {@code statuses} (the caller passes
     * {@code SUBMITTED}, {@code CHANGES_REQUESTED}, {@code APPROVED} — pending and approved both
     * count; {@code REJECTED} is excluded). The result is grouped by {@code (workItem.id, room.id)}
     * so the service can look up a cell's reported total directly.
     *
     * <p>Each row is {@code [workItemId (Long), roomId (Long), reportedQuantity (BigDecimal)]}.
     * Rooms / work items with no matching reports simply do not appear (the service treats a missing
     * pair as a reported quantity of zero).
     *
     * @param projectId the owning project id
     * @param workerId  the worker whose reports are summed
     * @param statuses  the report statuses that count toward the reported quantity (R14.2)
     * @return one row per {@code (workItem, room)} with the summed reported quantity
     */
    @Query("SELECT r.workItem.id, rr.room.id, SUM(rr.quantity) "
            + "FROM WorkReportEntity r JOIN r.rooms rr "
            + "WHERE r.project.id = :projectId "
            + "AND r.worker.id = :workerId "
            + "AND r.status IN :statuses "
            + "GROUP BY r.workItem.id, rr.room.id")
    List<Object[]> sumReportedQuantityByWorkItemAndRoom(@Param("projectId") Long projectId,
                                                        @Param("workerId") Long workerId,
                                                        @Param("statuses") Collection<WorkReportStatus> statuses);

    /**
     * FOR-06-02 (R2.2, R3.1) — the summed reported quantity per {@code (workItem, room, status)}
     * across <strong>all</strong> workers of a project, so the plan/actual overlay can bucket it
     * into confirmed ({@code APPROVED}) / pending ({@code SUBMITTED}+{@code CHANGES_REQUESTED}) /
     * rejected ({@code REJECTED}). Each row is
     * {@code [workItemId (Long), roomId (Long), status (WorkReportStatus), reportedQuantity (BigDecimal)]}.
     * A {@code (workItem, room, status)} triple with no reports simply does not appear (the service
     * treats a missing triple as zero).
     *
     * @param projectId the owning project id
     * @return one row per {@code (workItem, room, status)} with the summed reported quantity
     */
    @Query("SELECT r.workItem.id, rr.room.id, r.status, SUM(rr.quantity) "
            + "FROM WorkReportEntity r JOIN r.rooms rr "
            + "WHERE r.project.id = :projectId "
            + "GROUP BY r.workItem.id, rr.room.id, r.status")
    List<Object[]> sumReportedQuantityByWorkItemRoomAndStatus(@Param("projectId") Long projectId);

    /**
     * FOR-06-02 (R3.3) — the individual report rows contributing reported quantity to the project's
     * cells, for the per-cell drill-down: the parent report with its status, worker, and performed
     * interval, joined to each of its rooms with that room's quantity. The worker's {@code user} is
     * fetch-joined so the display name resolves without an N+1 lazy load. Ordered by
     * {@code (workItem, room, worker, reportId)} for a stable drill-down.
     *
     * <p>Each row is {@code [workItemId (Long), roomId (Long), reportId (Long),
     * status (WorkReportStatus), workerName (String), performedFrom (LocalDate),
     * performedTo (LocalDate), quantity (BigDecimal)]}.
     *
     * @param projectId the owning project id
     * @return one row per {@code (report, room)} with the fields the drill-down needs
     */
    @Query("SELECT r.workItem.id, rr.room.id, r.id, r.status, w.name, "
            + "r.performedFrom, r.performedTo, rr.quantity "
            + "FROM WorkReportEntity r JOIN r.rooms rr JOIN r.worker w "
            + "WHERE r.project.id = :projectId "
            + "ORDER BY r.workItem.id, rr.room.id, w.id, r.id")
    List<Object[]> findReportRefsByProject(@Param("projectId") Long projectId);

    /**
     * FOR-06-02 (R3.4) — the own-records variant of {@link #findReportRefsByProject(Long)} for a
     * WORKER caller: the same drill-down rows restricted to reports whose {@code worker} is the
     * caller, so a WORKER never sees another worker's individual report rows (the aggregate cell
     * totals stay visible). Same row shape and ordering as {@link #findReportRefsByProject(Long)}.
     *
     * @param projectId the owning project id
     * @param workerId  the caller's user id; only reports with this {@code worker} are returned
     * @return one row per {@code (report, room)} for the caller's own reports
     */
    @Query("SELECT r.workItem.id, rr.room.id, r.id, r.status, w.name, "
            + "r.performedFrom, r.performedTo, rr.quantity "
            + "FROM WorkReportEntity r JOIN r.rooms rr JOIN r.worker w "
            + "WHERE r.project.id = :projectId AND w.id = :workerId "
            + "ORDER BY r.workItem.id, rr.room.id, w.id, r.id")
    List<Object[]> findOwnReportRefsByProject(@Param("projectId") Long projectId,
                                              @Param("workerId") Long workerId);

    /**
     * FOR-06-06 (R1.3, design §B2) — the project's APPROVED work reports, the labour-fact side of the
     * margin matrix, with each report's {@link WorkReportEntity#getWorkItem() work item} and
     * {@link WorkReportEntity#getWorker() worker} fetch-joined so the margin-matrix service maps each
     * report (and resolves its worker's tier) without an N+1 over the LAZY associations. Only
     * {@code APPROVED} reports contribute to the fact side (R1.3); pending / rejected / change-requested
     * reports are excluded. Ordered by {@code (workItem, report id)} for a stable per-row detail.
     *
     * @param projectId the owning project id
     * @return the project's APPROVED reports with work item + worker fetched, empty when none
     */
    @Query("SELECT r FROM WorkReportEntity r "
            + "JOIN FETCH r.workItem "
            + "JOIN FETCH r.worker "
            + "WHERE r.project.id = :projectId AND r.status = com.foremen.dao.model.WorkReportStatus.APPROVED "
            + "ORDER BY r.workItem.id, r.id")
    List<WorkReportEntity> findApprovedByProjectWithWorkItemAndWorker(@Param("projectId") Long projectId);

    /**
     * FOR-06-11 (R3.1, R6.2, design §PayoutSuggestionService) — the one worker's {@code APPROVED}
     * work reports on a project whose performed interval {@code [performedFrom, performedTo]}
     * overlaps the payout period {@code [from, to]}, with the report's {@link WorkReportEntity#getWorkItem()
     * work item}, {@link WorkReportEntity#getWorker() worker}, and each of its
     * {@link WorkReportEntity#getRooms() room lines} (plus each line's {@code room} and
     * {@code paidByPayout}) fetch-joined, so the payout-suggestion builder folds the per-line
     * {@code Cost_Value} and reads each line's paid-by FK without an N+1 over the LAZY associations.
     *
     * <p>Only {@code APPROVED} reports are returned (payroll never pays an unapproved line). A line
     * is <b>unpaid</b> iff its {@code paidByPayout} FK is null — the caller filters the fetched room
     * lines on that FK (the fetch keeps the whole report graph, so the filter is in memory and the
     * query stays a single round-trip). The standard half-open overlap predicate
     * {@code performedFrom <= to AND performedTo >= from} selects every report touching the window.
     * {@code DISTINCT} collapses the row multiplication from the {@code rooms} fetch join. Ordered by
     * {@code (workItem, report id)} for a stable breakdown.
     *
     * @param projectId the owning project id
     * @param workerId  the worker whose approved reports are returned
     * @param from      the payout period start (inclusive)
     * @param to        the payout period end (inclusive)
     * @return the worker's APPROVED, in-period reports with the graph fetched, empty when none
     */
    @Query("SELECT DISTINCT r FROM WorkReportEntity r "
            + "JOIN FETCH r.workItem "
            + "JOIN FETCH r.worker "
            + "LEFT JOIN FETCH r.rooms rr "
            + "LEFT JOIN FETCH rr.room "
            + "LEFT JOIN FETCH rr.paidByPayout "
            + "WHERE r.project.id = :projectId "
            + "AND r.worker.id = :workerId "
            + "AND r.status = com.foremen.dao.model.WorkReportStatus.APPROVED "
            + "AND r.performedFrom <= :to AND r.performedTo >= :from "
            + "ORDER BY r.workItem.id, r.id")
    List<WorkReportEntity> findApprovedInPeriodWithRooms(@Param("projectId") Long projectId,
                                                         @Param("workerId") Long workerId,
                                                         @Param("from") java.time.LocalDate from,
                                                         @Param("to") java.time.LocalDate to);
}
