package com.foremen.dao;

import com.foremen.dao.model.InviteTokenEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InviteTokenDao extends AdminDao<InviteTokenEntity, Long> {

    Optional<InviteTokenEntity> findByToken(String token);

    List<InviteTokenEntity> findByUserIdAndUsedFalse(Long userId);
}
