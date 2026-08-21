package com.foremen.dao;

import com.foremen.dao.model.RoleEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleDao extends AdminDao<RoleEntity, Long> {
}
