package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkMaterialConsumptionEntity;

/**
 * DAO for {@link WorkMaterialConsumptionEntity} (FOR-04-19). A GLOBAL admin resource — not
 * project-scoped — so it rides the generic {@link AdminDao} CRUD contract for the standard 10-op
 * surface; the analog-batch price reads used by the computed money range live in the dedicated
 * {@code MaterialBatchLookup}.
 *
 * <p>The one extra query below feeds the work-catalog pivot aggregation
 * ({@code WorkCatalogAggregationResolver}): it loads, for a whole page of work items, all their
 * consumption rows in a SINGLE {@code IN} query so the per-package money range can be built without
 * an N+1 per work item.
 */
@Repository
public interface WorkMaterialConsumptionDao extends AdminDao<WorkMaterialConsumptionEntity, Long> {

    /**
     * Loads ALL consumption rows for the given set of {@code workItem.id}s in ONE {@code IN} query,
     * eagerly fetching the associations the {@code WorkCatalogAggregationResolver} needs to build the
     * per-package money range without a lazy-load per row (no N+1): the owning {@code workItem}, and
     * BOTH nullable material-type references ({@code constructionMaterialType} /
     * {@code finishingMaterialType}) whose non-null one supplies the batch's {@code materialTypeId}.
     * The {@code offerPackage} FK was dropped from the entity (FOR-05-04 R7.2) — a consumption row now
     * applies uniformly across every offer package.
     *
     * <p>An empty {@code workItemIds} yields an empty list without issuing a query.
     *
     * @param workItemIds the work-item ids of the page being aggregated
     * @return every consumption row whose {@code workItem.id} is in {@code workItemIds}, with
     *         {@code workItem} and the two type references initialized
     */
    @EntityGraph(attributePaths = {"workItem", "constructionMaterialType", "finishingMaterialType"})
    @Query("SELECT c FROM WorkMaterialConsumptionEntity c WHERE c.workItem.id IN :workItemIds")
    List<WorkMaterialConsumptionEntity> findByWorkItemIdIn(@Param("workItemIds") Collection<Long> workItemIds);
}
