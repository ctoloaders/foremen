package com.foremen.dao;

import com.foremen.dao.model.RoomTypeEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomTypeDao extends AdminDao<RoomTypeEntity, Long> {
}
