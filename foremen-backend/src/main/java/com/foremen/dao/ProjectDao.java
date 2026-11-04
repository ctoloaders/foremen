package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectDao extends AdminDao<ProjectEntity, Long> {

    /** FOR-07-04 (Requirement 15.2) — projects in the given statuses (AI classifier project list), by id. */
    List<ProjectEntity> findByStatusInOrderByIdAsc(Collection<ProjectStatus> statuses);
}
