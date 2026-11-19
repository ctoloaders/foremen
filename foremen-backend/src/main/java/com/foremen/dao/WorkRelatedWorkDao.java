package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkRelatedWorkEntity;

@Repository
public interface WorkRelatedWorkDao extends AdminDao<WorkRelatedWorkEntity, Long> {

    /** Ids of the works linked FROM {@code workItemId}, in link-creation order. */
    @Query("SELECT l.relatedWorkItem.id FROM WorkRelatedWorkEntity l "
            + "WHERE l.workItem.id = :workItemId ORDER BY l.id")
    List<Long> findRelatedIds(@Param("workItemId") Long workItemId);

    @Query("SELECT l FROM WorkRelatedWorkEntity l WHERE l.workItem.id = :workItemId ORDER BY l.id")
    List<WorkRelatedWorkEntity> findByWorkItemId(@Param("workItemId") Long workItemId);

    /** Links for a batch of parents (one query for a whole replay). */
    @Query("SELECT l.workItem.id AS workItemId, l.relatedWorkItem.id AS relatedId FROM WorkRelatedWorkEntity l "
            + "WHERE l.workItem.id IN :workItemIds ORDER BY l.id")
    List<RelatedLink> findLinksByWorkItemIdIn(@Param("workItemIds") Collection<Long> workItemIds);

    interface RelatedLink {
        Long getWorkItemId();

        Long getRelatedId();
    }
}
