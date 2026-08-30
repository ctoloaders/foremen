package com.foremen.dao;

import com.foremen.dao.model.RefreshTokenEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenDao extends AdminDao<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByToken(String token);

    List<RefreshTokenEntity> findByUserIdAndRevokedFalse(Long userId);
}
