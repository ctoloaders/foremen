package com.foremen.dao;

import com.foremen.dao.model.WorkPriceEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkPriceDao extends AdminDao<WorkPriceEntity, Long> {
}
