package com.foremen.dao;

import com.foremen.dao.model.RoomEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomDao extends AdminDao<RoomEntity, Long> {
}
