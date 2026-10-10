package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ProjectScheduleEntity;

/**
 * DAO for {@link ProjectScheduleEntity} (FOR-05-10, Requirements 6.1, 6.3): the per-project Planning
 * Gantt (Harmonogram) aggregate root.
 *
 * <p>At most one schedule row exists per project (the {@code project_id} FK is UNIQUE), so
 * {@link #findByProjectId(Long)} returns an {@link Optional}. A missing row means the project has no
 * schedule yet (version 0, no persisted bars) — the service builds an unscheduled view on read and
 * creates the row lazily on the first write (R6.1, R6.4).
 */
@Repository
public interface ProjectScheduleDao extends AdminDao<ProjectScheduleEntity, Long> {

    /**
     * The schedule aggregate for {@code projectId}, or empty when the project has none yet.
     *
     * @param projectId the owning project id
     * @return the project's schedule, if any
     */
    Optional<ProjectScheduleEntity> findByProjectId(Long projectId);
}
