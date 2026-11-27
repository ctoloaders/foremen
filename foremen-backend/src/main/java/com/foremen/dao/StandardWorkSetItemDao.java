package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.StandardWorkSetItemEntity;

@Repository
public interface StandardWorkSetItemDao extends AdminDao<StandardWorkSetItemEntity, Long> {

    boolean existsByRoomTypeIdAndWorkItemId(Long roomTypeId, Long workItemId);

    Optional<StandardWorkSetItemEntity> findByRoomTypeIdAndWorkItemId(Long roomTypeId, Long workItemId);

    /** All {@code (roomTypeId, workItemId)} pairs of the standard set, in insertion order. */
    @Query("SELECT i.roomType.id, i.workItem.id FROM StandardWorkSetItemEntity i ORDER BY i.id")
    List<Object[]> findAllPairs();

    /** The works of the standard set of a room type, in the order they were added. */
    @Query("SELECT i.workItem FROM StandardWorkSetItemEntity i "
            + "WHERE i.roomType.id = :roomTypeId AND i.workItem.active = true ORDER BY i.id")
    List<com.foremen.dao.model.WorkItemEntity> findActiveWorksByRoomTypeId(@Param("roomTypeId") Long roomTypeId);
}
