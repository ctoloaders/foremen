package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.StandardWorkSetItemEntity;

@Repository
public interface StandardWorkSetItemDao extends AdminDao<StandardWorkSetItemEntity, Long> {

    boolean existsByRoomTypeIdAndWorkItemId(Long roomTypeId, Long workItemId);

    /** The works of the standard set of a room type, in the order they were added. */
    @Query("SELECT i.workItem FROM StandardWorkSetItemEntity i "
            + "WHERE i.roomType.id = :roomTypeId AND i.workItem.active = true ORDER BY i.id")
    List<com.foremen.dao.model.WorkItemEntity> findActiveWorksByRoomTypeId(@Param("roomTypeId") Long roomTypeId);
}
