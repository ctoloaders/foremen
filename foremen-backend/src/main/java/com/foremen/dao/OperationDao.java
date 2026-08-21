package com.foremen.dao;

import com.foremen.dao.model.OperationEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface OperationDao extends ReadOnlyAdminDao<OperationEntity, Long> {
}
