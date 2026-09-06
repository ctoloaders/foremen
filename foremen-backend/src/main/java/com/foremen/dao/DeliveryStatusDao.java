package com.foremen.dao;

import com.foremen.dao.model.DeliveryStatusEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface DeliveryStatusDao extends AdminDao<DeliveryStatusEntity, Long> {
}
