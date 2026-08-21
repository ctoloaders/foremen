package com.foremen.dao;

import com.foremen.dao.model.RoleResourceEntity;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleResourceDao extends AdminDao<RoleResourceEntity, Long> {

    List<RoleResourceEntity> findAllByRoleId(Long roleId);

    void deleteAllByRoleId(Long roleId);
}
