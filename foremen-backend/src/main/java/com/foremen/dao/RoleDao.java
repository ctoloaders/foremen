package com.foremen.dao;

import com.foremen.dao.model.RoleEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleDao extends AdminDao<RoleEntity, Long> {

    Optional<RoleEntity> findByCode(String code);
}
