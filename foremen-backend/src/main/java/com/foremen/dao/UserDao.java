package com.foremen.dao;

import com.foremen.dao.model.UserEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDao extends AdminDao<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    /**
     * Counts users whose role code matches the given value (exact, case-sensitive),
     * traversing the {@code role.code} association. Used by the admin bootstrap to
     * enforce the single-ADMIN invariant.
     */
    long countByRoleCode(String roleCode);

    /**
     * Returns users whose role code matches the given value (exact, case-sensitive),
     * traversing the {@code role.code} association. Used by the admin bootstrap to
     * resolve the existing ADMIN user (if any).
     */
    List<UserEntity> findByRoleCode(String roleCode);
}
