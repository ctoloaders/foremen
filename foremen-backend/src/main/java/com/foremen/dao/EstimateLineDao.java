package com.foremen.dao;

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
}
