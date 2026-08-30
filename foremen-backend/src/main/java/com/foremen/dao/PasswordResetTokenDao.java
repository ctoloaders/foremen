package com.foremen.dao;

import com.foremen.dao.model.PasswordResetTokenEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenDao extends AdminDao<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByToken(String token);
}
