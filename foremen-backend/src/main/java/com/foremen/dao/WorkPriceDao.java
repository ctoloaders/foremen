package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkPriceEntity;

@Repository
public interface WorkPriceDao extends AdminDao<WorkPriceEntity, Long> {

    /**
     * Loads the {@link WorkPriceEntity} aggregators for a whole page of work items in ONE {@code IN}
     * query, eagerly fetching each aggregator's {@code packagePrices} (and their {@code offerPackage})
     * so the {@code WorkCatalogAggregationResolver} can surface the FOR-04-12b labour price per package
     * via {@code EffectivePriceResolver} without an N+1 per work item. A work item without a
     * {@code WorkPrice} is simply absent from the result (its labour price is then unpriced).
     *
     * <p>An empty {@code workItemIds} yields an empty list without issuing a query.
     *
     * @param workItemIds the work-item ids of the page being aggregated
     * @return the work-price aggregators whose {@code workItem.id} is in {@code workItemIds}, with
     *         {@code packagePrices} + their {@code offerPackage} initialized
     */
    @EntityGraph(attributePaths = {"packagePrices", "packagePrices.offerPackage"})
    @Query("SELECT DISTINCT wp FROM WorkPriceEntity wp WHERE wp.workItem.id IN :workItemIds")
    List<WorkPriceEntity> findByWorkItemIdIn(@Param("workItemIds") Collection<Long> workItemIds);
}
