package com.foremen.dao;

import com.foremen.dao.model.EstimateEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * DAO for {@link EstimateEntity} (FOR-05-03, Requirement 1), following the
 * {@code RoomDao}/{@code RoleDao} finder convention: the generic {@link AdminDao} CRUD surface
 * plus a single derived finder used to enforce the single-estimate-per-project invariant
 * (R1.1, R1.5, R1.6) from {@code EstimateService.getOrCreateForProject}.
 */
@Repository
public interface EstimateDao extends AdminDao<EstimateEntity, Long> {

    /**
     * The project's estimate, if one has already been created. Backs the create-or-resolve
     * upsert path ({@code getOrCreateForProject}) and the explicit-second-create rejection
     * (R1.5, R1.6); the DB-level UNIQUE constraint on {@code estimates.project_id} is the
     * ultimate backstop.
     */
    Optional<EstimateEntity> findByProjectId(Long projectId);
}
