package com.foremen.dao;

import com.foremen.dao.model.ProjectEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectDao extends AdminDao<ProjectEntity, Long> {
}
