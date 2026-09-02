package com.foremen.service;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectMemberService} covering each service branch with stubbed DAOs and a
 * mocked {@link ProjectAccessCache}: successful assign/remove persist/delete and invalidate the
 * cache, duplicate/unknown-role/missing-membership branches raise the correct
 * {@link ForemenApiException} without mutating, and the list operations delegate to the DAO.
 *
 * Verifies exception status + message codes and that {@code invalidate} runs on the success/mutation
 * paths and is NOT invoked when the service throws before mutating (Requirements 3.1-3.6, 8.1, 8.2).
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private UserDao userDao;
    @Mock
    private RoleDao roleDao;
    @Mock
    private ProjectAccessCache projectAccessCache;

    @InjectMocks
    private ProjectMemberService service;

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 42L;
    private static final Long ROLE_ID = 3L;

    // --- assign: success (Req 3.1, 8.1) ---

    @Test
    @DisplayName("assign persists the membership and invalidates the cache on success")
    void assignPersistsAndInvalidatesOnSuccess() {
        UserEntity user = new UserEntity();
        RoleEntity role = new RoleEntity();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(roleDao.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberEntity saved = service.assign(USER_ID, PROJECT_ID, ROLE_ID);

        // The persisted row carries the resolved user, the plain project id, and the resolved role.
        ArgumentCaptor<ProjectMemberEntity> captor = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(projectMemberDao).save(captor.capture());
        ProjectMemberEntity persisted = captor.getValue();
        assertThat(persisted.getUser()).isSameAs(user);
        assertThat(persisted.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(persisted.getProjectRole()).isSameAs(role);
        assertThat(saved).isSameAs(persisted);

        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- assign: duplicate (Req 3.2) ---

    @Test
    @DisplayName("assign raises 409 error.project.member.duplicate without a second insert or invalidation")
    void assignDuplicateRaisesConflictWithoutSaving() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, ROLE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.duplicate");
                });

        // No insert on the duplicate branch, no user/role lookup, and the cache is left untouched.
        verify(projectMemberDao, never()).save(any());
        verifyNoInteractions(userDao, roleDao, projectAccessCache);
    }

    // --- assign: unknown role (Req 3.3) ---

    @Test
    @DisplayName("assign raises 404 error.project.role.not.found for an unknown projectRoleId without saving or invalidating")
    void assignUnknownRoleRaisesNotFound() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(new UserEntity()));
        when(roleDao.findById(ROLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, ROLE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.role.not.found");
                });

        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    // --- assign: unknown user (error.entity.not.found) ---

    @Test
    @DisplayName("assign raises 404 error.entity.not.found for an unknown user without saving or invalidating")
    void assignUnknownUserRaisesEntityNotFound() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, ROLE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                });

        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    // --- remove: success (Req 3.4, 8.2) ---

    @Test
    @DisplayName("remove deletes the membership and invalidates the cache")
    void removeDeletesAndInvalidates() {
        ProjectMemberEntity member = new ProjectMemberEntity();
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));

        service.remove(USER_ID, PROJECT_ID);

        verify(projectMemberDao).delete(member);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- remove: missing membership (Req 3.4) ---

    @Test
    @DisplayName("remove raises 404 error.project.member.not.found without deleting or invalidating")
    void removeMissingMembershipRaisesNotFound() {
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(USER_ID, PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.not.found");
                });

        verify(projectMemberDao, never()).delete(any(ProjectMemberEntity.class));
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    // --- listMembers (Req 3.5) ---

    @Test
    @DisplayName("listMembers returns the DAO rows for the given project id")
    void listMembersReturnsRowsForProject() {
        ProjectMemberEntity m1 = new ProjectMemberEntity();
        ProjectMemberEntity m2 = new ProjectMemberEntity();
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(m1, m2));

        List<ProjectMemberEntity> result = service.listMembers(PROJECT_ID);

        assertThat(result).containsExactly(m1, m2);
        verify(projectMemberDao).findByProjectId(PROJECT_ID);
    }

    // --- listProjects (Req 3.6) ---

    @Test
    @DisplayName("listProjects returns the distinct project-id set for the given user id")
    void listProjectsReturnsDistinctProjectIds() {
        Set<Long> ids = Set.of(1L, 2L, 3L);
        when(projectMemberDao.findDistinctProjectIdsByUserId(USER_ID)).thenReturn(ids);

        Set<Long> result = service.listProjects(USER_ID);

        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
        verify(projectMemberDao).findDistinctProjectIdsByUserId(USER_ID);
    }
}
