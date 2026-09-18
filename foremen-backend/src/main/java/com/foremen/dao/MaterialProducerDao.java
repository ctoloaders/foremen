package com.foremen.dao;

import com.foremen.dao.model.MaterialProducerEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialProducerDao extends AdminDao<MaterialProducerEntity, Long> {
}
