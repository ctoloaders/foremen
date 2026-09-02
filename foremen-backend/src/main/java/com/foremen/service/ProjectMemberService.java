package com.foremen.service;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Manages project memberships: assign, remove, and list operations over
 * {@link ProjectMemberEntity} rows. Every mutation invalidates the affected user's
 * {@link ProjectAccessCache} entry so revoked or granted project access is honored promptly
 * (Requirements 3.1-3.6, 8.1, 8.2).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProjectMemberService {

    private final ProjectMemberDao projectMemberDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final ProjectAccessCache projectAccessCache;

    /**
     * Persists a membership associating {@code userId} with {@code projectId} under the role
     * identified by {@code projectRoleId}, then invalidates the user's project-access cache entry.
     *
     * @throws ForemenApiException 409 {@code error.project.member.duplicate} when a membership for
     *         the {@code (userId, projectId)} pair already exists (Req 3.2);
     *         404 {@code error.entity.not.found} when the user does not exist;
     *         404 {@code error.project.role.not.found} when the role does not exist (Req 3.3).
     */
    public ProjectMemberEntity assign(Long userId, Long projectId, Long projectRoleId) {
        if (projectMemberDao.existsByUserIdAndProjectId(userId, projectId)) {          // Req 3.2 -> 409
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.project.member.duplicate", userId, projectId);
        }
        UserEntity user = userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", userId));
        RoleEntity role = roleDao.findById(projectRoleId)                               // Req 3.3 -> 404
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.project.role.not.found", projectRoleId));

        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        ProjectMemberEntity saved = projectMemberDao.save(member);                     // Req 3.1

        projectAccessCache.invalidate(userId);                                         // Req 8.1
        return saved;
    }

    /**
     * Deletes the membership of {@code userId} on {@code projectId}, then invalidates the user's
     * project-access cache entry.
     *
     * @throws ForemenApiException 404 {@code error.project.member.not.found} when no membership
     *         exists for the {@code (userId, projectId)} pair (Req 3.4).
     */
    public void remove(Long userId, Long projectId) {
        ProjectMemberEntity member = projectMemberDao.findByUserIdAndProjectId(userId, projectId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.project.member.not.found", userId, projectId)); // Req 3.4 -> 404
        projectMemberDao.delete(member);
        projectAccessCache.invalidate(userId);                                         // Req 8.2
    }

    /** Returns every membership row whose {@code project_id} equals {@code projectId} (Req 3.5). */
    @Transactional(readOnly = true)
    public List<ProjectMemberEntity> listMembers(Long projectId) {
        return projectMemberDao.findByProjectId(projectId);
    }

    /** Returns the distinct set of project ids drawn from the user's membership rows (Req 3.6). */
    @Transactional(readOnly = true)
    public Set<Long> listProjects(Long userId) {
        return projectMemberDao.findDistinctProjectIdsByUserId(userId);
    }
}
