package com.foremen.dao;

import com.foremen.dao.model.MaterialCategoryEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialCategoryDao extends AdminDao<MaterialCategoryEntity, Long> {
}
