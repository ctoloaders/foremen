package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.EstimateLineEntity;

/**
 * DAO for {@link EstimateLineEntity} (FOR-05-03, Requirement 2). The generic {@link AdminDao} CRUD
 * surface is sufficient here: line traversal for recompute goes through the owning
 * {@code EstimateEntity.lines} association (see {@code EstimateLineService}/
 * {@code EstimateLineRoomQtyService}'s {@code recomputeAndPersist}/{@code recomputeOwningEstimate}).
 */
@Repository
public interface EstimateLineDao extends AdminDao<EstimateLineEntity, Long> {

    /**
     * FOR-05-10 Req 4/5 (Schedule_Row derivation): the estimate lines of a project's estimate with
     * the full graph the planning Gantt needs eagerly loaded in one query — the line's
     * {@link EstimateLineEntity#getWorkItem() work item}, that item's
     * {@link com.foremen.dao.model.WorkItemEntity#getWorkCategory() work category} (the row key),
     * and the line's {@link EstimateLineEntity#getUnit() unit} (the localized unit label). The join
     * avoids N+1 lazy loads while {@link com.foremen.service.schedule.ScheduleRowDerivation} groups
     * the lines by category and the service maps each line onto a {@code ScheduleLineView}. Returns
     * an empty list when the project has no estimate or its estimate has no lines (R4.3).
     *
     * @param projectId the owning project id
     * @return the project's estimate lines with work item, work category, and unit fetched
     */
    @Query("SELECT l FROM EstimateLineEntity l "
            + "JOIN FETCH l.workItem wi "
            + "JOIN FETCH wi.workCategory "
            + "JOIN FETCH l.unit "
            + "WHERE l.estimate.project.id = :projectId")
    List<EstimateLineEntity> findByProjectIdWithWorkItemCategoryAndUnit(@Param("projectId") Long projectId);
}
