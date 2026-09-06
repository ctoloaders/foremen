package com.foremen.dao;

import com.foremen.dao.model.WorkCategoryEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkCategoryDao extends AdminDao<WorkCategoryEntity, Long> {
}
