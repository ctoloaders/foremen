package com.foremen.dao;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkPriceEntity;

@Repository
public interface WorkPriceDao extends AdminDao<WorkPriceEntity, Long> {

    /**
     * Loads the single {@link WorkPriceEntity} for one work item (FOR-05-04, Requirement 1: at
     * most one price row per work item). Used by {@code EstimateLineService} to copy the work's
     * single catalog price onto a newly created {@code EstimateLine.unitPrice} snapshot (task
     * 17.1, R5.1). Empty when the work item is unpriced.
     *
     * @param workItemId the work item's id
     * @return the work item's single price row, or empty if unpriced
     */
    @Query("SELECT wp FROM WorkPriceEntity wp WHERE wp.workItem.id = :workItemId")
    Optional<WorkPriceEntity> findByWorkItemId(@Param("workItemId") Long workItemId);

    /**
     * Loads the {@link WorkPriceEntity} single-price rows for a whole page of work items in ONE
     * {@code IN} query, so {@code WorkCatalogAggregationResolver} can surface the work's labour price
     * without an N+1 per work item. A work item without a {@code WorkPrice} is simply absent from the
     * result (its labour price is then unpriced).
     *
     * <p>An empty {@code workItemIds} yields an empty list without issuing a query.
     *
     * @param workItemIds the work-item ids of the page being aggregated
     * @return the work-price rows whose {@code workItem.id} is in {@code workItemIds}
     */
    @Query("SELECT wp FROM WorkPriceEntity wp WHERE wp.workItem.id IN :workItemIds")
    List<WorkPriceEntity> findByWorkItemIdIn(@Param("workItemIds") Collection<Long> workItemIds);
}
