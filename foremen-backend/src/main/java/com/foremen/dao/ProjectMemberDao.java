package com.foremen.dao;

import com.foremen.dao.model.AssignmentStatus;
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

    // FOR-05-09 Req 3.6: distinct project ids a user belongs to in any Assignment_Status, ascending.
    @Query("SELECT DISTINCT pm.projectId FROM ProjectMemberEntity pm WHERE pm.user.id = :userId ORDER BY pm.projectId ASC")
    List<Long> findDistinctProjectIdsByUserIdOrderByProjectIdAsc(@Param("userId") Long userId);

    Optional<ProjectMemberEntity> findByUserIdAndProjectId(Long userId, Long projectId);

    boolean existsByUserIdAndProjectId(Long userId, Long projectId);

    List<ProjectMemberEntity> findByProjectId(Long projectId);

    /**
     * FOR-05-09 Req 9 (task 8.1): counts the Project_Members of a project whose Project_Role code
     * equals {@code roleCode} (case-sensitive, as stored) and whose Assignment_Status equals
     * {@code status}. Used by the last-ACTIVE-MANAGER / last-ACTIVE-CLIENT guard to decide whether a
     * remove or deactivate would drop the last ACTIVE member of that role to zero. The COUNT runs
     * inside the mutating transaction so, together with the DB, at least one ACTIVE member of the
     * role survives concurrent removes/deactivates (Req 9.8).
     */
    @Query("SELECT COUNT(pm) FROM ProjectMemberEntity pm "
            + "WHERE pm.projectId = :projectId "
            + "AND pm.projectRole.code = :roleCode "
            + "AND pm.assignmentStatus = :status")
    long countByProjectIdAndProjectRoleCodeAndAssignmentStatus(@Param("projectId") Long projectId,
                                                               @Param("roleCode") String roleCode,
                                                               @Param("status") AssignmentStatus status);

    /**
     * FOR-05-09 Req 14.11 (task 9.6): true when any Project_Member references the Worker_Type with
     * id {@code workerTypeId}. The FOR-05-06 worker-type delete path consults this guard before
     * deleting a {@code WorkerTypeEntity}: a referenced type must not be deleted (409
     * {@code error.worker.type.in.use}), leaving the type and every member unchanged. Deactivation
     * (setting {@code active = false}) is unaffected — only hard delete is guarded (Req 14.10).
     */
    @Query("SELECT COUNT(pm) > 0 FROM ProjectMemberEntity pm WHERE pm.workerType.id = :workerTypeId")
    boolean existsByWorkerTypeId(@Param("workerTypeId") Long workerTypeId);
}
