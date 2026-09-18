package com.foremen.dao;

import com.foremen.dao.model.ResourceEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourceDao extends ReadOnlyAdminDao<ResourceEntity, Long> {

    /**
     * Whether a seeded ABAC resource with the given {@code code} exists. Used by
     * {@code ImageController} to validate the dynamic {@code resource} request parameter against
     * the set of seeded resources before enforcing the CREATE/UPDATE permission (FOR-04-17,
     * Requirement 9.5/9.6).
     */
    boolean existsByCode(String code);

    /**
     * The seeded ABAC resource with the given {@code code}, if any.
     */
    Optional<ResourceEntity> findByCode(String code);
}
