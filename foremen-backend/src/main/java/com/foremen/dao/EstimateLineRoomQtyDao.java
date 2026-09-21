package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.EstimateLineRoomQtyEntity;

/**
 * DAO for {@link EstimateLineRoomQtyEntity} (FOR-05-03, Requirement 3). The generic
 * {@link AdminDao} CRUD surface is sufficient here: {@code EstimateLineRoomQtyService} resolves a
 * line's room quantities via the owning {@code EstimateLineEntity.roomQtys} association during
 * recompute.
 */
@Repository
public interface EstimateLineRoomQtyDao extends AdminDao<EstimateLineRoomQtyEntity, Long> {
}
