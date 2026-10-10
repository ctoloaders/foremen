package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ProjectScheduleBarEntity;

/**
 * DAO for {@link ProjectScheduleBarEntity} (FOR-05-10, Requirement 6.2): the planned bars of a
 * project schedule.
 *
 * <p>Bars are an owned child collection of {@link com.foremen.dao.model.ProjectScheduleEntity}
 * ({@code cascade = ALL}, {@code orphanRemoval = true}), so they are normally persisted and removed
 * through the aggregate root rather than this DAO. This repository exists for direct lookups/counts
 * and to keep the entity's CRUD surface available where the service needs it.
 */
@Repository
public interface ProjectScheduleBarDao extends AdminDao<ProjectScheduleBarEntity, Long> {
}
