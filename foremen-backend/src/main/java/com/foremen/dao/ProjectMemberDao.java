package com.foremen.dao;

import com.foremen.dao.model.ProjectMemberEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ProjectMemberDao extends AdminDao<ProjectMemberEntity, Long> {

    // Requirement 2.6 / 4.1: distinct project ids for a user, used by ProjectAccessCache loader.
    @Query("SELECT DISTINCT pm.projectId FROM ProjectMemberEntity pm WHERE pm.user.id = :userId")
    Set<Long> findDistinctProjectIdsByUserId(@Param("userId") Long userId);

    Optional<ProjectMemberEntity> findByUserIdAndProjectId(Long userId, Long projectId);

    boolean existsByUserIdAndProjectId(Long userId, Long projectId);

    List<ProjectMemberEntity> findByProjectId(Long projectId);
}
