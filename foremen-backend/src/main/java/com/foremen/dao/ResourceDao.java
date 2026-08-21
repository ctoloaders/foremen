package com.foremen.dao;

import com.foremen.dao.model.ResourceEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface ResourceDao extends ReadOnlyAdminDao<ResourceEntity, Long> {
}
