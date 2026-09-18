package com.foremen.dao;

import com.foremen.dao.model.MaterialTypeEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialTypeDao extends AdminDao<MaterialTypeEntity, Long> {
}
