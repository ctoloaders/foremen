package com.foremen.dao;

import com.foremen.dao.model.UserEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDao extends AdminDao<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);
}
