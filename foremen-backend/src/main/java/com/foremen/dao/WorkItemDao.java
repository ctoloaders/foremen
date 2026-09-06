package com.foremen.dao;

import com.foremen.dao.model.WorkItemEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkItemDao extends AdminDao<WorkItemEntity, Long> {
}
