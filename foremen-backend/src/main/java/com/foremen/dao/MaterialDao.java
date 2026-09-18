package com.foremen.dao;

import com.foremen.dao.model.MaterialEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialDao extends AdminDao<MaterialEntity, Long> {
}
