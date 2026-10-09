package com.foremen.service;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
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
    private ProjectDao projectDao;
    @Mock
    private WorkerTypeDao workerTypeDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private com.foremen.service.model.mapper.ProjectMemberServiceMapper projectMemberServiceMapper;
    @Mock
    private com.foremen.service.audit.AuditLogDao auditLogDao;
    @Mock
    private jakarta.persistence.EntityManager entityManager;
    @Mock
    private com.foremen.service.offer.NotificationService notificationService;

    @InjectMocks
    private ProjectMemberService service;

    /**
     * Builds a user with an Assignable_Project_Role Company_Role so the FOR-05-09 team-composition
     * check (task 6.3, Requirement 6 criteria 1/3) passes and the flow reaches the branch under test.
     * A user with no role, a non-assignable role, or {@code active = false} would be rejected at the
     * composition step before any later persist/role lookup.
     */
    private static UserEntity activeAssignableUser() {
        RoleEntity companyRole = new RoleEntity();
        companyRole.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setRole(companyRole);
        user.setActive(true);
        return user;
    }

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 42L;
    private static final Long ROLE_ID = 3L;
    private static final Long WORKER_TYPE_ID = 11L;

    /** A role with the given code, for the role.mismatch and worker-type tests. */
    private static RoleEntity roleWithCode(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        return role;
    }

    /** An active worker type with the given id. */
    private static WorkerTypeEntity activeWorkerType(Long id) {
        WorkerTypeEntity type = new WorkerTypeEntity();
        type.setId(id);
        type.setActive(true);
        return type;
    }

    /**
     * Authenticate an ADMIN caller by default so the canonical rejection checklist's step-4
     * project existence / access check ({@code projectAccessCheck}) is bypassed via
     * {@code isCallerAdmin()} and each mutation test reaches the member / user / role / duplicate /
     * success logic it asserts (FOR-05-09 task 6.1 canonical rejection order, Requirement 3
     * criterion 7). The {@code listProjects} tests re-authenticate with their own principal and
     * authorities, which replaces this default for the duration of that test; the {@code @AfterEach}
     * clears the context afterwards.
     */
    @BeforeEach
    void authenticateAdmin() {
        authenticate("1", "ROLE_ADMIN");
    }

    // --- assign: success (Req 3.1, 8.1) ---

    @Test
    @DisplayName("assign persists the Company_Role (D2), ACTIVE status, and no tags on success, and invalidates the cache")
    void assignPersistsCompanyRoleAndInvalidatesOnSuccess() {
        // D2: the persisted Project_Role is the user's Company_Role, NOT the supplied projectRoleId.
        UserEntity user = activeAssignableUser();            // Company_Role = MANAGER
        RoleEntity companyRole = user.getRole();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        // The supplied projectRoleId resolves to the SAME code as the Company_Role -> no mismatch.
        when(roleDao.findById(ROLE_ID)).thenReturn(Optional.of(roleWithCode("MANAGER")));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberEntity saved = service.assign(USER_ID, PROJECT_ID, ROLE_ID);

        ArgumentCaptor<ProjectMemberEntity> captor = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(projectMemberDao).save(captor.capture());
        ProjectMemberEntity persisted = captor.getValue();
        assertThat(persisted.getUser()).isSameAs(user);
        assertThat(persisted.getProjectId()).isEqualTo(PROJECT_ID);
        // The persisted role is the Company_Role instance, never the submitted-role instance (D2, Req 5.1/5.10).
        assertThat(persisted.getProjectRole()).isSameAs(companyRole);
        assertThat(persisted.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE); // Req 5.2
        assertThat(persisted.getWorkerType()).isNull();      // no workerType supplied
        assertThat(persisted.getTags()).isEmpty();           // no tags supplied (Req 5.3)
        assertThat(saved).isSameAs(persisted);

        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("assign with a null projectRoleId derives the role from the Company_Role and never resolves a role")
    void assignNullProjectRoleIdDerivesCompanyRole() {
        UserEntity user = activeAssignableUser();            // Company_Role = MANAGER
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberEntity saved = service.assign(USER_ID, PROJECT_ID, null);

        assertThat(saved.getProjectRole()).isSameAs(user.getRole());
        assertThat(saved.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        // A null projectRoleId needs no role.mismatch comparison, so no role is ever resolved.
        verify(roleDao, never()).findById(anyLong());
        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- assign: role.mismatch (Req 5.11, Property 2) ---

    @Test
    @DisplayName("assign raises 400 error.project.member.role.mismatch when the supplied projectRoleId resolves to a different code than the Company_Role")
    void assignRoleMismatchRaisesBadRequestWithoutSaving() {
        UserEntity user = activeAssignableUser();            // Company_Role = MANAGER
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        // The supplied projectRoleId resolves to a DIFFERENT code (FOREMAN) -> mismatch (D2, Req 5.11).
        when(roleDao.findById(ROLE_ID)).thenReturn(Optional.of(roleWithCode("FOREMAN")));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, ROLE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.role.mismatch");
                });

        // role.mismatch is a step-3 check: it fires before the duplicate check and before any persist.
        verify(projectMemberDao, never()).existsByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("assign role.mismatch wins over a would-be duplicate (step 3 before step 6, Property 11)")
    void assignRoleMismatchWinsOverDuplicate() {
        UserEntity user = activeAssignableUser();            // Company_Role = MANAGER
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(roleDao.findById(ROLE_ID)).thenReturn(Optional.of(roleWithCode("FOREMAN")));
        // Even though the pair is a duplicate, the earlier role.mismatch wins; the duplicate check never runs.

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, ROLE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.mismatch"));

        verify(projectMemberDao, never()).existsByUserIdAndProjectId(anyLong(), anyLong());
    }

    // --- assign: duplicate (Req 5.5) ---

    @Test
    @DisplayName("assign raises 409 error.project.member.duplicate without a second insert or invalidation")
    void assignDuplicateRaisesConflictWithoutSaving() {
        // A null projectRoleId skips the step-3 role.mismatch lookup, so the duplicate check (step 6)
        // is reached without any user/role resolution.
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
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

    @Test
    @DisplayName("assign translates a unique-constraint violation on save into 409 error.project.member.duplicate (concurrency, Req 5.9)")
    void assignConstraintViolationTranslatesToConflict() {
        UserEntity user = activeAssignableUser();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        // The application-level duplicate check passes (racing caller), but the DB unique constraint
        // uk_project_members_user_project rejects the second INSERT.
        when(projectMemberDao.save(any(ProjectMemberEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates uk_project_members_user_project"));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.duplicate");
                });

        // The loser of the race never invalidates the cache (its row was not committed).
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    // --- assign: worker type (Req 5.3, Property 3) ---

    @Test
    @DisplayName("assign attaches an Active_Worker_Type to a WORKER member")
    void assignAttachesWorkerTypeToWorker() {
        UserEntity worker = userWithRole("WORKER", true);
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(type));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberEntity saved = service.assign(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        assertThat(saved.getProjectRole().getCode()).isEqualTo("WORKER");
        assertThat(saved.getWorkerType()).isSameAs(type);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("assign raises 400 error.project.member.worker.type.not.allowed for a worker type on a non-WORKER member")
    void assignWorkerTypeOnNonWorkerRaisesNotAllowed() {
        UserEntity manager = activeAssignableUser();         // Company_Role = MANAGER
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.worker.type.not.allowed");
                });

        verify(projectMemberDao, never()).save(any());
        verify(workerTypeDao, never()).findById(anyLong());
    }

    @Test
    @DisplayName("assign raises 400 error.project.member.worker.type.invalid for a missing or inactive worker type on a WORKER member")
    void assignInactiveWorkerTypeRaisesInvalid() {
        UserEntity worker = userWithRole("WORKER", true);
        WorkerTypeEntity inactive = activeWorkerType(WORKER_TYPE_ID);
        inactive.setActive(false);
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.worker.type.invalid");
                });

        verify(projectMemberDao, never()).save(any());
    }

    // --- assign: tags (Req 5.3) ---

    @Test
    @DisplayName("assign stores the normalized Assignment_Tags on the new member")
    void assignStoresNormalizedTags() {
        UserEntity user = activeAssignableUser();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // "  spec  " trims to "spec"; the case-insensitive duplicate "SPEC" is dropped, first spelling kept.
        ProjectMemberEntity saved = service.assign(
                USER_ID, PROJECT_ID, null, null, List.of("  spec  ", "SPEC", "lead"));

        assertThat(saved.getTags()).containsExactly("spec", "lead");
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("assign raises 400 error.project.member.tag.invalid for an invalid tag before any lookup")
    void assignInvalidTagRaisesBadRequestBeforeLookup() {
        // A 51-code-point tag exceeds the limit; the tag check is step 3, before any DAO lookup.
        String tooLong = "x".repeat(51);

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null, null, List.of(tooLong)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.tag.invalid");
                });

        verify(projectMemberDao, never()).save(any());
        verifyNoInteractions(userDao, roleDao, workerTypeDao, projectAccessCache);
    }

    // --- assign: unknown user (error.entity.not.found) ---

    @Test
    @DisplayName("assign raises 404 error.entity.not.found for an unknown user without saving or invalidating")
    void assignUnknownUserRaisesEntityNotFound() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
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

    // --- remove: single-row isolation (Req 8.3) ---

    @Test
    @DisplayName("remove deletes exactly the resolved member row and no other row, user, or project (Req 8.3)")
    void removeDeletesExactlyTheResolvedRow() {
        ProjectMemberEntity target = new ProjectMemberEntity();
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(target));

        service.remove(USER_ID, PROJECT_ID);

        // Exactly one delete, against the resolved row — no bulk/other-row deletion.
        verify(projectMemberDao, times(1)).delete(target);
        verify(projectMemberDao, never()).deleteAll();
        verify(projectMemberDao).delete(target);
        // The service touches no user or role DAO on remove (no user/project row deletion).
        verifyNoInteractions(userDao, roleDao, workerTypeDao);
    }

    // --- remove: INACTIVE / INVITED / inactive-user member (Req 8.4) ---

    @Test
    @DisplayName("remove hard-deletes an already-INACTIVE member without any extra user-state check (Req 8.4)")
    void removeInactiveMemberDeletesAndInvalidates() {
        ProjectMemberEntity inactive = new ProjectMemberEntity();
        inactive.setAssignmentStatus(AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(inactive));

        service.remove(USER_ID, PROJECT_ID);

        // Same rules as an ACTIVE member: the row is deleted and the cache invalidated, with no
        // additional user-state gate (Req 8.4).
        verify(projectMemberDao).delete(inactive);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- remove: self-remove drops the caller's own access (Req 8.7) ---

    @Test
    @DisplayName("remove of the caller's own membership invalidates the caller's ProjectAccessCache entry (Req 8.7)")
    void removeSelfInvalidatesCallerAccess() {
        // A non-ADMIN MANAGER removes their own membership on an Accessible_Project.
        authenticate(String.valueOf(USER_ID), "ROLE_MANAGER");
        when(projectAccessCache.get(USER_ID)).thenReturn(Set.of(PROJECT_ID));
        ProjectMemberEntity own = new ProjectMemberEntity();
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(own));

        service.remove(USER_ID, PROJECT_ID);

        verify(projectMemberDao).delete(own);
        // The removed (self) user's access is dropped so the next request no longer treats the
        // project as Accessible (Req 8.7, Req 16 criterion 2).
        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- team composition: role.not.assignable (FOR-05-09 task 6.3, Req 6.1/6.4) ---

    @Test
    @DisplayName("assign raises 400 error.project.member.role.not.assignable for a non-assignable Company_Role (ADMIN), no bypass")
    void assignNonAssignableRoleRaisesBadRequest() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("ADMIN", true)));

        // A null projectRoleId skips the step-3 role.mismatch check and reaches the step-8
        // composition check. The default authenticated caller is ADMIN: composition still applies (Req 6.4).
        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.role.not.assignable");
                });

        // Rejected before any persist, role lookup, or cache invalidation (Req 6.7).
        verify(projectMemberDao, never()).save(any());
        verify(roleDao, never()).findById(anyLong());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("assign raises 400 error.project.member.role.not.assignable when the user has no Company_Role")
    void assignNullRoleRaisesRoleNotAssignable() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        UserEntity noRole = new UserEntity(); // role == null, active defaults to true
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(noRole));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.not.assignable"));

        verify(projectMemberDao, never()).save(any());
    }

    // --- team composition: user.inactive (FOR-05-09 task 6.3, Req 6.3) ---

    @Test
    @DisplayName("assign raises 400 error.project.member.user.inactive for an Inactive_User with an assignable role")
    void assignInactiveUserRaisesBadRequest() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("WORKER", false)));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.user.inactive");
                });

        verify(projectMemberDao, never()).save(any());
        verify(roleDao, never()).findById(anyLong());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("assign: role.not.assignable wins over user.inactive (lowest-numbered criterion, Req 6.6)")
    void assignRoleNotAssignableWinsOverInactive() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        // Non-assignable role AND inactive: criterion 1 (role) is reported, not criterion 3 (inactive).
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("ADMIN", false)));

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.not.assignable"));
    }

    // --- lifecycle lock (FOR-05-09 task 6.3, Req 10.1-10.4) ---

    @Test
    @DisplayName("assign raises 409 error.project.team.locked on a COMPLETED project, before the duplicate/user checks (ADMIN, no bypass)")
    void assignLockedCompletedProjectRaisesConflict() {
        ProjectEntity completed = new ProjectEntity();
        completed.setStatus(ProjectStatus.COMPLETED);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(completed));

        // A null projectRoleId skips the step-3 role.mismatch lookup, so step 5 (lock) is reached
        // without any user/role resolution and fires before the duplicate/user checks.
        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.team.locked");
                });

        // Step 5 (lock) runs before step 6/7 (duplicate/user) and before any persist (Req 10.4).
        verify(projectMemberDao, never()).existsByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verifyNoInteractions(userDao, roleDao);
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("remove raises 409 error.project.team.locked on a CANCELLED project without deleting")
    void removeLockedCancelledProjectRaisesConflict() {
        ProjectEntity cancelled = new ProjectEntity();
        cancelled.setStatus(ProjectStatus.CANCELLED);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.remove(USER_ID, PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.team.locked");
                });

        verify(projectMemberDao, never()).delete(any(ProjectMemberEntity.class));
        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("assign proceeds on an editable (ACTIVE) project: lifecycle lock does not trip")
    void assignEditableActiveProjectIsNotLocked() {
        ProjectEntity active = new ProjectEntity();
        active.setStatus(ProjectStatus.ACTIVE);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(active));
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(activeAssignableUser()));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // A null projectRoleId derives the role from the Company_Role; the lock check still runs first.
        service.assign(USER_ID, PROJECT_ID, null);

        verify(projectMemberDao).save(any(ProjectMemberEntity.class));
        verify(projectAccessCache).invalidate(USER_ID);
    }

    /** A user with the given Company_Role code and active flag, for the composition tests. */
    private static UserEntity userWithRole(String roleCode, boolean active) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        UserEntity user = new UserEntity();
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    // --- last-ACTIVE MANAGER / CLIENT guard (FOR-05-09 task 8.1, Requirement 9) ---

    /** A membership row with the given role code and ACTIVE assignment status on {@link #PROJECT_ID}. */
    private static ProjectMemberEntity activeMemberOfRole(String roleCode) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(roleWithCode(roleCode));
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        return member;
    }

    @Test
    @DisplayName("remove of the only ACTIVE MANAGER raises 409 error.project.member.last.manager and deletes nothing (Req 9.1)")
    void removeLastActiveManagerRaisesConflict() {
        ProjectMemberEntity manager = activeMemberOfRole("MANAGER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        // Only this one ACTIVE MANAGER exists on the project.
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(USER_ID, PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.last.manager");
                });

        // A rejected op changes no row and leaves the access cache untouched (Req 9.1).
        verify(projectMemberDao, never()).delete(any(ProjectMemberEntity.class));
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("remove of a non-last ACTIVE MANAGER succeeds: the guard permits it when another ACTIVE MANAGER remains (Req 9.5)")
    void removeNonLastActiveManagerSucceeds() {
        ProjectMemberEntity manager = activeMemberOfRole("MANAGER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        // Two ACTIVE MANAGERs exist, so removing this one still leaves one ACTIVE.
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn(2L);

        service.remove(USER_ID, PROJECT_ID);

        verify(projectMemberDao).delete(manager);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("remove of the only ACTIVE CLIENT raises 409 error.project.member.last.client and deletes nothing (Req 9.2)")
    void removeLastActiveClientRaisesConflict() {
        ProjectMemberEntity client = activeMemberOfRole("CLIENT");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(client));
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "CLIENT", AssignmentStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(USER_ID, PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.last.client");
                });

        verify(projectMemberDao, never()).delete(any(ProjectMemberEntity.class));
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("remove of a non-MANAGER / non-CLIENT member is never blocked by the last-ACTIVE guard (Req 9.1-9.2)")
    void removeNonGuardedRoleSkipsLastActiveGuard() {
        ProjectMemberEntity foreman = activeMemberOfRole("FOREMAN");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));

        service.remove(USER_ID, PROJECT_ID);

        // A FOREMAN is not guarded, so the ACTIVE-count query is never issued and the delete proceeds.
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), any());
        verify(projectMemberDao).delete(foreman);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("remove of an already-INACTIVE MANAGER is never blocked: it subtracts no ACTIVE member (Req 8.4 / 9.3)")
    void removeInactiveManagerSkipsLastActiveGuard() {
        ProjectMemberEntity inactiveManager = activeMemberOfRole("MANAGER");
        inactiveManager.setAssignmentStatus(AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(inactiveManager));

        service.remove(USER_ID, PROJECT_ID);

        // Removing an INACTIVE member changes no ACTIVE count, so the guard does not even count.
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), any());
        verify(projectMemberDao).delete(inactiveManager);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- updateAttributes: deactivate / reactivate (FOR-05-09 task 8.2, Requirement 27) ---

    /**
     * A membership row for the {@code (USER_ID, PROJECT_ID)} pair with the given role code and
     * assignment status, used by the Attribute_Update status tests. The role carries localized names
     * so the returned {@link com.foremen.controller.model.TeamMemberView} is well-formed.
     */
    private static ProjectMemberEntity memberOfRole(String roleCode, AssignmentStatus status) {
        UserEntity user = viewUser(USER_ID, "Member", "member@x.io", roleCode, true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(500L);
        member.setUser(user);
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(user.getRole());
        member.setAssignmentStatus(status);
        return member;
    }

    @Test
    @DisplayName("updateAttributes deactivates an ACTIVE member (ACTIVE -> INACTIVE) and invalidates the cache (Req 27.2)")
    void updateAttributesDeactivatesActiveMember() {
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        assertThat(foreman.getAssignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        // Identity/role unchanged (Req 27.2): only the status flips.
        assertThat(view.id()).isEqualTo(500L);
        assertThat(view.userId()).isEqualTo(USER_ID);
        assertThat(view.projectId()).isEqualTo(PROJECT_ID);
        assertThat(view.projectRoleCode()).isEqualTo("FOREMAN");
        verify(projectMemberDao).save(foreman);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("updateAttributes reactivates an INACTIVE member (INACTIVE -> ACTIVE) and invalidates the cache (Req 27.3)")
    void updateAttributesReactivatesInactiveMember() {
        ProjectMemberEntity worker = memberOfRole("WORKER", AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        assertThat(worker.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        verify(projectMemberDao).save(worker);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("updateAttributes reactivates the only MANAGER without the last-ACTIVE guard (Req 27.3)")
    void updateAttributesReactivatesLastManagerAlwaysAllowed() {
        ProjectMemberEntity manager = memberOfRole("MANAGER", AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        // Reactivation only adds an ACTIVE member, so the last-ACTIVE count is never consulted (Req 27.3).
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), any());
        verify(projectMemberDao).save(manager);
    }

    @Test
    @DisplayName("updateAttributes is an idempotent no-op when the status already matches: 200, no save, no invalidate (Req 27.6)")
    void updateAttributesIdempotentNoOpOnSameStatus() {
        ProjectMemberEntity activeForeman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(activeForeman));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        // Current view returned, status unchanged, and NO state change (no save, no cache invalidate).
        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(activeForeman.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes re-deactivating the only ACTIVE MANAGER is a clean no-op, not a 409 (Req 27.6 vs 27.7)")
    void updateAttributesReDeactivateInactiveManagerIsNoOp() {
        // The member is already INACTIVE; deactivating it again changes no ACTIVE count, so the
        // last-ACTIVE guard must not fire and the call is an idempotent no-op.
        ProjectMemberEntity inactiveManager = memberOfRole("MANAGER", AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(inactiveManager));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), any());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 404 error.project.member.not.found for a missing pair, modifying no row (Req 27.4)")
    void updateAttributesMissingPairRaisesNotFound() {
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.not.found");
                });

        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes with an all-null body dispatches to the tag change and raises 400 error.project.member.tag.invalid (task 9.5 fills the former status slot)")
    void updateAttributesAllNullBodyDispatchesToTagChange() {
        // Since task 9.5, a PATCH that carries no assignmentStatus and no workerTypeId always
        // dispatches to the tag change; a null tag list there is an invalid tag change (Req 15.3),
        // because clearing tags requires an explicit empty list. The former behavior of raising
        // error.project.member.assignment.status.invalid for an all-null body no longer applies.
        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.tag.invalid");
                });

        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
    }

    @Test
    @DisplayName("updateAttributes raises 400 error.project.member.assignment.status.invalid for an unknown status value, before any persistence (Req 27.5)")
    void updateAttributesInvalidStatusRaisesBadRequest() {
        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, "PENDING", null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.assignment.status.invalid");
                });

        // Step 3 fires before member existence (step 6): no lookup, no persistence.
        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 409 error.project.team.locked on a locked project, before member resolution (Req 27.9)")
    void updateAttributesLockedProjectRaisesConflict() {
        ProjectEntity completed = new ProjectEntity();
        completed.setStatus(ProjectStatus.COMPLETED);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.team.locked");
                });

        // Step 5 (lock) runs before step 6 (member existence): no lookup, no save (Req 27.9).
        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes deactivating the only ACTIVE MANAGER raises 409 error.project.member.last.manager, changing no row (Req 27.7)")
    void updateAttributesDeactivateLastManagerRaisesConflict() {
        ProjectMemberEntity manager = memberOfRole("MANAGER", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.last.manager");
                });

        // Guard trips before any flip: status stays ACTIVE, no save, no notification/cache change (Req 27.7).
        assertThat(manager.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes deactivating a non-last ACTIVE MANAGER succeeds when another ACTIVE MANAGER remains (Req 27.2)")
    void updateAttributesDeactivateNonLastManagerSucceeds() {
        ProjectMemberEntity manager = memberOfRole("MANAGER", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn(2L);
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        verify(projectMemberDao).save(manager);
        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- updateAttributes: worker-type set/replace (FOR-05-09 task 9.1, Requirement 14) ---

    /** A WORKER membership for the {@code (USER_ID, PROJECT_ID)} pair with the given worker type (or none). */
    private static ProjectMemberEntity workerMemberWithType(WorkerTypeEntity type) {
        UserEntity user = viewUser(USER_ID, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(600L);
        member.setUser(user);
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(user.getRole());
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        member.setWorkerType(type);
        member.setTags(new java.util.ArrayList<>());
        return member;
    }

    @Test
    @DisplayName("updateAttributes sets a worker type on an Uncategorized_Worker and returns the updated view (Req 14.5)")
    void updateAttributesSetsWorkerTypeOnUncategorizedWorker() {
        ProjectMemberEntity worker = workerMemberWithType(null); // Uncategorized_Worker
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");
        type.setNamePL("Spawacz");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(type));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        // The worker type is attached; identity/role/status unchanged (Req 14.5).
        assertThat(worker.getWorkerType()).isSameAs(type);
        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(view.workerTypeCode()).isEqualTo("WELDER");
        assertThat(view.id()).isEqualTo(600L);
        assertThat(view.projectRoleCode()).isEqualTo("WORKER");
        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        verify(projectMemberDao).save(worker);
        // A worker-type change does NOT change project access (Req 16.5).
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes replaces an existing worker type with a different Active_Worker_Type (Req 14.5)")
    void updateAttributesReplacesWorkerType() {
        WorkerTypeEntity oldType = activeWorkerType(99L);
        oldType.setCode("HELPER");
        ProjectMemberEntity worker = workerMemberWithType(oldType);
        WorkerTypeEntity newType = activeWorkerType(WORKER_TYPE_ID);
        newType.setCode("WELDER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(newType));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        assertThat(worker.getWorkerType()).isSameAs(newType);
        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(view.workerTypeCode()).isEqualTo("WELDER");
        verify(projectMemberDao).save(worker);
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 400 error.project.member.worker.type.not.allowed for a non-WORKER target (Req 14.4, Property 3)")
    void updateAttributesWorkerTypeOnNonWorkerRaisesNotAllowed() {
        ProjectMemberEntity manager = memberOfRole("MANAGER", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.worker.type.not.allowed");
                });

        // not.allowed fires before the invalid lookup: no worker-type resolution, no save (Req 14.9).
        verify(workerTypeDao, never()).findById(anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 400 error.project.member.worker.type.invalid for a nonexistent type (Req 14.3)")
    void updateAttributesNonexistentWorkerTypeRaisesInvalid() {
        ProjectMemberEntity worker = workerMemberWithType(null);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.worker.type.invalid");
                });

        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 400 error.project.member.worker.type.invalid for an inactive non-current type (Req 14.3)")
    void updateAttributesInactiveNonCurrentWorkerTypeRaisesInvalid() {
        ProjectMemberEntity worker = workerMemberWithType(null);
        WorkerTypeEntity inactive = activeWorkerType(WORKER_TYPE_ID);
        inactive.setActive(false);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.worker.type.invalid"));

        verify(projectMemberDao, never()).save(any());
    }

    @Test
    @DisplayName("updateAttributes raises 404 error.project.member.not.found for a missing pair, changing no row (Req 14.6)")
    void updateAttributesWorkerTypeMissingPairRaisesNotFound() {
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.not.found");
                });

        // Member existence (step 6) fires before the worker-type rules: no type lookup, no save.
        verify(workerTypeDao, never()).findById(anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes is an idempotent no-op when the member already has the requested worker type (Req 14.7)")
    void updateAttributesSameWorkerTypeIsNoOp() {
        WorkerTypeEntity current = activeWorkerType(WORKER_TYPE_ID);
        current.setCode("WELDER");
        ProjectMemberEntity worker = workerMemberWithType(current);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        // No change, no save, no cache invalidation; current view returned (Req 14.7).
        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(worker.getWorkerType()).isSameAs(current);
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes same-type no-op succeeds even when the current worker type is now inactive (Req 14.7 over 14.3)")
    void updateAttributesSameInactiveWorkerTypeIsNoOp() {
        // The member's current type has since been deactivated; re-submitting it must NOT fail the
        // invalid check (criterion 3 exempts the current type) — it is a clean no-op.
        WorkerTypeEntity inactiveCurrent = activeWorkerType(WORKER_TYPE_ID);
        inactiveCurrent.setActive(false);
        inactiveCurrent.setCode("WELDER");
        ProjectMemberEntity worker = workerMemberWithType(inactiveCurrent);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(worker.getWorkerType()).isSameAs(inactiveCurrent);
        // No lookup of the type is even required for the same-type path, and nothing is persisted.
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 409 error.project.team.locked on a locked project, before member/worker-type resolution (Req 10)")
    void updateAttributesWorkerTypeLockedProjectRaisesConflict() {
        ProjectEntity completed = new ProjectEntity();
        completed.setStatus(ProjectStatus.COMPLETED);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.team.locked");
                });

        // Step 5 (lock) runs before step 6 (member) and step 8 (worker type): no lookups, no save.
        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(workerTypeDao, never()).findById(anyLong());
        verify(projectMemberDao, never()).save(any());
    }

    // --- updateAttributes: tag replace (FOR-05-09 task 9.5, Requirement 15) ---

    /**
     * A membership for the {@code (USER_ID, PROJECT_ID)} pair with the given stored tags, used by the
     * Attribute_Update tag tests. The role carries localized names so the returned
     * {@link com.foremen.controller.model.TeamMemberView} is well-formed; a WORKER role is used so
     * the admin-staff reader sees the internal {@code tags} field.
     */
    private static ProjectMemberEntity memberWithTags(List<String> tags) {
        UserEntity user = viewUser(USER_ID, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(700L);
        member.setUser(user);
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(user.getRole());
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        member.setWorkerType(activeWorkerType(WORKER_TYPE_ID));
        member.setTags(tags == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(tags));
        return member;
    }

    @Test
    @DisplayName("updateAttributes replaces the whole tag list with the normalized submitted list, keeping identity/role/status/worker type unchanged (Req 15.5)")
    void updateAttributesReplacesTagList() {
        ProjectMemberEntity member = memberWithTags(List.of("old", "stale"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // "  lead  " trims to "lead"; the case-insensitive duplicate "LEAD" is dropped (first kept).
        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("  lead  ", "LEAD", "spec"));

        // The whole list is REPLACED (not merged): old tags are gone, new normalized tags present.
        assertThat(member.getTags()).containsExactly("lead", "spec");
        assertThat(view.tags()).containsExactly("lead", "spec");
        // Identity / role / status / worker type unchanged (Req 15.5).
        assertThat(view.id()).isEqualTo(700L);
        assertThat(view.userId()).isEqualTo(USER_ID);
        assertThat(view.projectId()).isEqualTo(PROJECT_ID);
        assertThat(view.projectRoleCode()).isEqualTo("WORKER");
        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        verify(projectMemberDao).save(member);
        // A tag change does NOT change project access (Req 16.5).
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes clears all tags for an explicit empty list (Req 15.5)")
    void updateAttributesEmptyListClearsTags() {
        ProjectMemberEntity member = memberWithTags(List.of("lead", "spec"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of());

        // An explicit empty list clears all tags (Req 15.5).
        assertThat(member.getTags()).isEmpty();
        assertThat(view.tags()).isEmpty();
        verify(projectMemberDao).save(member);
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 400 error.project.member.tag.invalid for an invalid tag before any lookup (Req 15.3)")
    void updateAttributesInvalidTagRaisesBadRequest() {
        // A 51-code-point tag exceeds the limit; the tag check is step 3, before any DAO lookup.
        String tooLong = "x".repeat(51);

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of(tooLong)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.tag.invalid");
                });

        // Step 3 fires before member existence (step 6): no lookup, no persistence.
        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 400 error.project.member.tag.invalid for a null tag list (clearing requires an explicit empty list) (Req 15.3)")
    void updateAttributesNullTagListRaisesBadRequest() {
        // No assignmentStatus and no workerTypeId: the dispatcher routes a null tag list to the tag
        // change, where a tag change REQUIRES an explicit list — a null list is itself invalid.
        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.tag.invalid");
                });

        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 404 error.project.member.not.found for a missing pair, changing no row (Req 15.5)")
    void updateAttributesTagsMissingPairRaisesNotFound() {
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("lead")))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.not.found");
                });

        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes raises 409 error.project.team.locked on a locked project, before member resolution (Req 15.5, Req 10)")
    void updateAttributesTagsLockedProjectRaisesConflict() {
        ProjectEntity completed = new ProjectEntity();
        completed.setStatus(ProjectStatus.COMPLETED);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("lead")))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.team.locked");
                });

        // Step 5 (lock) runs before step 6 (member existence): no lookup, no save (Req 15.5).
        verify(projectMemberDao, never()).findByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes is an idempotent no-op when the normalized list equals the stored list in the same case-sensitive positions: 200, no save, no invalidate (Req 15.6)")
    void updateAttributesSameTagListIsNoOp() {
        ProjectMemberEntity member = memberWithTags(List.of("lead", "spec"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));

        // Submitting "  lead  " / "spec" normalizes to the exact stored list in the same positions.
        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("  lead  ", "spec"));

        // Current view returned, stored list unchanged, no state change (Req 15.6).
        assertThat(view.tags()).containsExactly("lead", "spec");
        assertThat(member.getTags()).containsExactly("lead", "spec");
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("updateAttributes treats a reorder of the same tags as a real change, not a no-op (Req 15.6, 17.8)")
    void updateAttributesReorderIsAChange() {
        ProjectMemberEntity member = memberWithTags(List.of("lead", "spec"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Same tags, different order -> a real change (different positions, Req 15.6 / 17.8).
        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("spec", "lead"));

        assertThat(member.getTags()).containsExactly("spec", "lead");
        assertThat(view.tags()).containsExactly("spec", "lead");
        verify(projectMemberDao).save(member);
    }

    @Test
    @DisplayName("updateAttributes treats a letter-case change of a tag as a real change, not a no-op (Req 15.6, 17.8)")
    void updateAttributesCaseChangeIsAChange() {
        ProjectMemberEntity member = memberWithTags(List.of("lead"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Same spelling but different case -> stored and audited as a change (case-sensitive compare).
        com.foremen.controller.model.TeamMemberView view =
                service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("LEAD"));

        assertThat(member.getTags()).containsExactly("LEAD");
        assertThat(view.tags()).containsExactly("LEAD");
        verify(projectMemberDao).save(member);
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

    // --- listWorkerTypeAssignments: downstream worker-type read (FOR-05-09 task 9.3, Req 14.13) ---

    @Test
    @DisplayName("listWorkerTypeAssignments returns one entry per WORKER member, excluding admin-staff and CLIENT members (Req 14.13)")
    void listWorkerTypeAssignmentsIncludesOnlyWorkers() {
        UserEntity worker = viewUser(1L, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity manager = viewUser(2L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity client = viewUser(3L, "Cleo", "cleo@x.io", "CLIENT", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");
        ProjectMemberEntity workerMember = viewMember(10L, worker, AssignmentStatus.ACTIVE, type, null);
        ProjectMemberEntity managerMember = viewMember(11L, manager, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity clientMember = viewMember(12L, client, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(managerMember, workerMember, clientMember));

        List<com.foremen.controller.model.WorkerTypeAssignment> result =
                service.listWorkerTypeAssignments(PROJECT_ID);

        // Only the WORKER member is reported (admin-staff + CLIENT are excluded).
        assertThat(result).hasSize(1);
        com.foremen.controller.model.WorkerTypeAssignment a = result.get(0);
        assertThat(a.userId()).isEqualTo(1L);
        assertThat(a.membershipId()).isEqualTo(10L);
        assertThat(a.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(a.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(a.workerTypeCode()).isEqualTo("WELDER");
    }

    @Test
    @DisplayName("listWorkerTypeAssignments reports an inactive worker type's id and code (Req 14.10, 14.13)")
    void listWorkerTypeAssignmentsIncludesInactiveTypeCode() {
        UserEntity worker = viewUser(1L, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        WorkerTypeEntity inactive = activeWorkerType(WORKER_TYPE_ID);
        inactive.setActive(false);         // the type has since been deactivated through FOR-05-06
        inactive.setCode("WELDER");
        ProjectMemberEntity member = viewMember(10L, worker, AssignmentStatus.ACTIVE, inactive, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        List<com.foremen.controller.model.WorkerTypeAssignment> result =
                service.listWorkerTypeAssignments(PROJECT_ID);

        // The inactive type is still reported (its id + code), not dropped (Req 14.10).
        assertThat(result).hasSize(1);
        assertThat(result.get(0).workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(result.get(0).workerTypeCode()).isEqualTo("WELDER");
    }

    @Test
    @DisplayName("listWorkerTypeAssignments reports an explicit null worker type for an Uncategorized_Worker (Req 14.13)")
    void listWorkerTypeAssignmentsNullForUncategorizedWorker() {
        UserEntity worker = viewUser(1L, "Xena", "xena@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = viewMember(10L, worker, AssignmentStatus.INACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        List<com.foremen.controller.model.WorkerTypeAssignment> result =
                service.listWorkerTypeAssignments(PROJECT_ID);

        // An Uncategorized_Worker is reported with an explicit null worker type id + code, and its
        // own (here INACTIVE) Assignment_Status.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(1L);
        assertThat(result.get(0).workerTypeId()).isNull();
        assertThat(result.get(0).workerTypeCode()).isNull();
        assertThat(result.get(0).assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
    }

    @Test
    @DisplayName("listWorkerTypeAssignments is read-only: it never saves or deletes a member row (Req 14.13)")
    void listWorkerTypeAssignmentsIsReadOnly() {
        UserEntity worker = viewUser(1L, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = viewMember(10L, worker, AssignmentStatus.ACTIVE,
                activeWorkerType(WORKER_TYPE_ID), null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        service.listWorkerTypeAssignments(PROJECT_ID);

        // The read modifies no row and touches no mutating DAO/cache operation.
        verify(projectMemberDao, never()).save(any());
        verify(projectMemberDao, never()).delete(any(ProjectMemberEntity.class));
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("listWorkerTypeAssignments returns an empty list for a project with no WORKER members (Req 14.13)")
    void listWorkerTypeAssignmentsEmptyWhenNoWorkers() {
        UserEntity manager = viewUser(2L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity managerMember = viewMember(11L, manager, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(managerMember));

        List<com.foremen.controller.model.WorkerTypeAssignment> result =
                service.listWorkerTypeAssignments(PROJECT_ID);

        assertThat(result).isEmpty();
    }

    // --- listMemberViews (FOR-05-09 task 7.5, Requirement 4) ---

    /** Builds a persisted-style user with id, name, email, status, active flag, and Company_Role. */
    private static UserEntity viewUser(Long id, String name, String email, String roleCode,
                                       boolean active, com.foremen.dao.model.UserStatus status) {
        RoleEntity role = roleWithCode(roleCode);
        role.setId(100L + id);
        role.setNameRU(roleCode + "_RU");
        role.setNamePL(roleCode + "_PL");
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        user.setActive(active);
        user.setStatus(status);
        return user;
    }

    /** Builds a membership row for a user with the given id, status, worker type, and tags. */
    private static ProjectMemberEntity viewMember(Long id, UserEntity user, AssignmentStatus status,
                                                  WorkerTypeEntity type, List<String> tags) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(id);
        member.setUser(user);
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(user.getRole());
        member.setAssignmentStatus(status);
        member.setWorkerType(type);
        member.setTags(tags == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(tags));
        return member;
    }

    @Test
    @DisplayName("listMemberViews returns an empty list for an empty team (Req 4.2)")
    void listMemberViewsEmptyTeamReturnsEmptyList() {
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listMemberViews raises 400 error.project.member.project.id.invalid for a null or non-positive projectId (Req 4.8)")
    void listMemberViewsRejectsInvalidProjectId() {
        for (Long bad : new Long[] {null, 0L, -1L}) {
            assertThatThrownBy(() -> service.listMemberViews(bad))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getMessageCode()).isEqualTo("error.project.member.project.id.invalid");
                    });
        }
        // No lookup happens for an invalid id.
        verify(projectMemberDao, never()).findByProjectId(anyLong());
    }

    @Test
    @DisplayName("listMemberViews maps every member including INACTIVE, INVITED, and inactive-user members (Req 4.1, 4.3)")
    void listMemberViewsIncludesInactiveInvitedAndInactiveUser() {
        UserEntity activeManager = viewUser(1L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity invitedForeman = viewUser(2L, "Bob", "bob@x.io", "FOREMAN", true,
                com.foremen.dao.model.UserStatus.INVITED);
        UserEntity inactiveClient = viewUser(3L, "Cora", "cora@x.io", "CLIENT", false,
                com.foremen.dao.model.UserStatus.ACTIVE);

        ProjectMemberEntity mManager = viewMember(10L, activeManager, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity mForeman = viewMember(11L, invitedForeman, AssignmentStatus.INACTIVE, null, null);
        ProjectMemberEntity mClient = viewMember(12L, inactiveClient, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(mClient, mForeman, mManager)); // DAO order is arbitrary

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        // All three are present regardless of INACTIVE/INVITED/inactive-user state.
        assertThat(result).hasSize(3);
        // Deterministic order: ADMIN_STAFF (MANAGER, then FOREMAN), then CLIENTS.
        assertThat(result).extracting(v -> v.id()).containsExactly(10L, 11L, 12L);
        assertThat(result).extracting(v -> v.block()).containsExactly(
                com.foremen.service.team.TeamBlock.ADMIN_STAFF,
                com.foremen.service.team.TeamBlock.ADMIN_STAFF,
                com.foremen.service.team.TeamBlock.CLIENTS);
        // The invited foreman keeps its INACTIVE assignment status; the inactive user is still listed.
        assertThat(result.get(1).assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        assertThat(result.get(1).userStatus()).isEqualTo("INVITED");
        assertThat(result.get(2).userActive()).isFalse();
    }

    @Test
    @DisplayName("listMemberViews orders by block then admin-staff rank then name then id (Req 4.6)")
    void listMemberViewsDeterministicOrder() {
        // Two MANAGERs (name tiebreak), one FOREMAN, one WORKER, one CLIENT.
        UserEntity mgrZoe = viewUser(1L, "Zoe", "zoe@x.io", "MANAGER", true, com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity mgrAmy = viewUser(2L, "amy", "amy@x.io", "MANAGER", true, com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity foreman = viewUser(3L, "Fred", "fred@x.io", "FOREMAN", true, com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity worker = viewUser(4L, "Will", "will@x.io", "WORKER", true, com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity client = viewUser(5L, "Cleo", "cleo@x.io", "CLIENT", true, com.foremen.dao.model.UserStatus.ACTIVE);

        ProjectMemberEntity pmMgrZoe = viewMember(20L, mgrZoe, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity pmMgrAmy = viewMember(21L, mgrAmy, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity pmForeman = viewMember(22L, foreman, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity pmWorker = viewMember(23L, worker, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity pmClient = viewMember(24L, client, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(pmClient, pmWorker, pmForeman, pmMgrZoe, pmMgrAmy));

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        // ADMIN_STAFF: MANAGER(amy) before MANAGER(Zoe) (case-insensitive name), then FOREMAN;
        // then WORKERS; then CLIENTS.
        assertThat(result).extracting(v -> v.id()).containsExactly(21L, 20L, 22L, 23L, 24L);
    }

    @Test
    @DisplayName("listMemberViews resolves the Russian role name for a ru request (Req 4.4, 24.4)")
    void listMemberViewsResolvesRussianRoleName() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.of("ru"));
        UserEntity manager = viewUser(1L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = viewMember(30L, manager, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        assertThat(result.get(0).projectRoleName()).isEqualTo("MANAGER_RU");
        assertThat(result.get(0).projectRoleCode()).isEqualTo("MANAGER");
        assertThat(result.get(0).companyRoleCode()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("listMemberViews resolves the Polish role name for a non-ru request (Req 4.4)")
    void listMemberViewsResolvesPolishRoleNameForNonRu() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.ENGLISH);
        UserEntity manager = viewUser(1L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = viewMember(31L, manager, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        assertThat(result.get(0).projectRoleName()).isEqualTo("MANAGER_PL");
    }

    @Test
    @DisplayName("listMemberViews falls back to the role code when the localized name is blank (Req 24.4)")
    void listMemberViewsFallsBackToRoleCode() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.of("ru"));
        UserEntity manager = viewUser(1L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        manager.getRole().setNameRU("   "); // blank localized name
        ProjectMemberEntity member = viewMember(32L, manager, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        assertThat(result.get(0).projectRoleName()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("listMemberViews populates WORKER internal fields and computes workerTypeMissing (Req 4.3, 4.10, 14.16)")
    void listMemberViewsPopulatesWorkerInternalFields() {
        // A WORKER with a worker type and tags, plus an Uncategorized_Worker (missing type).
        UserEntity typedWorker = viewUser(1L, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        typedWorker.setWorkerKind(com.foremen.dao.model.WorkerKind.COMPANY);
        typedWorker.setContactPerson("Contact Co");
        typedWorker.setNip("1234567890");
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");
        type.setNameRU("Сварщик");
        type.setNamePL("Spawacz");
        ProjectMemberEntity typed = viewMember(40L, typedWorker, AssignmentStatus.ACTIVE, type, List.of("lead"));

        UserEntity uncategorized = viewUser(2L, "Xena", "xena@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity missing = viewMember(41L, uncategorized, AssignmentStatus.ACTIVE, null, null);

        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(typed, missing));

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        com.foremen.controller.model.TeamMemberView v0 = result.get(0); // id 40 (Will before Xena)
        assertThat(v0.block()).isEqualTo(com.foremen.service.team.TeamBlock.WORKERS);
        assertThat(v0.workerKind()).isEqualTo(com.foremen.dao.model.WorkerKind.COMPANY);
        assertThat(v0.contactPerson()).isEqualTo("Contact Co");
        assertThat(v0.nip()).isEqualTo("1234567890");
        assertThat(v0.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(v0.workerTypeCode()).isEqualTo("WELDER");
        assertThat(v0.workerTypeName()).isEqualTo("Spawacz"); // non-ru default -> Polish
        assertThat(v0.workerTypeActive()).isTrue();
        assertThat(v0.workerTypeMissing()).isFalse();
        assertThat(v0.tags()).containsExactly("lead");

        com.foremen.controller.model.TeamMemberView v1 = result.get(1); // id 41, Uncategorized_Worker
        assertThat(v1.workerTypeId()).isNull();
        assertThat(v1.workerTypeMissing()).isTrue(); // WORKERS + no worker type (Req 14.16)
        assertThat(v1.workerKind()).isEqualTo(com.foremen.dao.model.WorkerKind.PERSON); // null -> PERSON
        assertThat(v1.tags()).isEmpty();
    }

    @Test
    @DisplayName("listMemberViews leaves workerTypeMissing false and worker fields null for an admin-staff member (Req 14.16)")
    void listMemberViewsAdminStaffHasNoWorkerFields() {
        UserEntity manager = viewUser(1L, "Anna", "anna@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        ProjectMemberEntity member = viewMember(50L, manager, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(member));

        com.foremen.controller.model.TeamMemberView v = service.listMemberViews(PROJECT_ID).get(0);

        assertThat(v.block()).isEqualTo(com.foremen.service.team.TeamBlock.ADMIN_STAFF);
        assertThat(v.workerKind()).isNull();
        assertThat(v.workerTypeMissing()).isFalse(); // not a WORKERS member
        assertThat(v.nip()).isNull();
    }

    @Test
    @DisplayName("workerTypeMissing is true for a WORKER without a type, false for a typed WORKER, and false for a non-WORKER, for an admin-staff reader (Req 14.16)")
    void listMemberViewsWorkerTypeMissingMatrixForAdminStaff() {
        // Admin-staff reader (the @BeforeEach ADMIN) sees the internal workerTypeMissing flag.
        UserEntity untypedWorker = viewUser(1L, "Amy", "amy@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity typedWorker = viewUser(2L, "Bob", "bob@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        UserEntity manager = viewUser(3L, "Cora", "cora@x.io", "MANAGER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");

        ProjectMemberEntity mUntyped = viewMember(60L, untypedWorker, AssignmentStatus.ACTIVE, null, null);
        ProjectMemberEntity mTyped = viewMember(61L, typedWorker, AssignmentStatus.ACTIVE, type, null);
        ProjectMemberEntity mManager = viewMember(62L, manager, AssignmentStatus.ACTIVE, null, null);
        when(projectMemberDao.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(mManager, mUntyped, mTyped));

        List<com.foremen.controller.model.TeamMemberView> result = service.listMemberViews(PROJECT_ID);

        // Index by membership id so the assertion is order-independent.
        java.util.Map<Long, com.foremen.controller.model.TeamMemberView> byId = result.stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.foremen.controller.model.TeamMemberView::id, v -> v));
        // (role==WORKER and workerTypeId is null) -> true
        assertThat(byId.get(60L).workerTypeMissing()).isTrue();
        // typed WORKER -> false
        assertThat(byId.get(61L).workerTypeMissing()).isFalse();
        // non-WORKER (MANAGER) -> false
        assertThat(byId.get(62L).workerTypeMissing()).isFalse();
    }

    // --- listMemberViews internal-attribute masking (FOR-05-09 task 7.6, Requirement 4.10, 4.11) ---

    /**
     * A WORKER-with-type membership whose view carries every Internal_Attribute (worker type
     * id/code/name/active, nip, workerTypeMissing, tags) when the reader is admin-staff, so the mask
     * tests have a member with all internal fields set to assert against.
     */
    private ProjectMemberEntity typedWorkerMember() {
        UserEntity worker = viewUser(1L, "Will", "will@x.io", "WORKER", true,
                com.foremen.dao.model.UserStatus.ACTIVE);
        worker.setWorkerKind(com.foremen.dao.model.WorkerKind.COMPANY);
        worker.setContactPerson("Contact Co");
        worker.setNip("1234567890");
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");
        type.setNamePL("Spawacz");
        return viewMember(40L, worker, AssignmentStatus.ACTIVE, type, List.of("lead"));
    }

    @Test
    @DisplayName("listMemberViews omits all Internal_Attributes for a WORKER reader but keeps assignmentStatus (Req 4.10, 4.11)")
    void listMemberViewsMasksInternalFieldsForWorkerReader() {
        authenticate(String.valueOf(USER_ID), "ROLE_WORKER"); // not admin-staff
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(typedWorkerMember()));

        com.foremen.controller.model.TeamMemberView v = service.listMemberViews(PROJECT_ID).get(0);

        // Internal_Attributes are omitted (null -> dropped by @JsonInclude(NON_NULL)).
        assertThat(v.workerTypeId()).isNull();
        assertThat(v.workerTypeCode()).isNull();
        assertThat(v.workerTypeName()).isNull();
        assertThat(v.workerTypeActive()).isNull();
        assertThat(v.nip()).isNull();
        assertThat(v.workerTypeMissing()).isNull();
        assertThat(v.tags()).isNull();
        // Non-internal fields stay: assignmentStatus is never masked; identity/role/user fields remain.
        assertThat(v.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(v.id()).isEqualTo(40L);
        assertThat(v.projectRoleCode()).isEqualTo("WORKER");
        assertThat(v.userName()).isEqualTo("Will");
        assertThat(v.block()).isEqualTo(com.foremen.service.team.TeamBlock.WORKERS);
    }

    @Test
    @DisplayName("listMemberViews omits all Internal_Attributes for a CLIENT reader but keeps assignmentStatus (Req 4.10, 4.11)")
    void listMemberViewsMasksInternalFieldsForClientReader() {
        authenticate(String.valueOf(USER_ID), "ROLE_CLIENT"); // not admin-staff
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(typedWorkerMember()));

        com.foremen.controller.model.TeamMemberView v = service.listMemberViews(PROJECT_ID).get(0);

        assertThat(v.workerTypeId()).isNull();
        assertThat(v.workerTypeCode()).isNull();
        assertThat(v.workerTypeName()).isNull();
        assertThat(v.workerTypeActive()).isNull();
        assertThat(v.nip()).isNull();
        assertThat(v.workerTypeMissing()).isNull();
        assertThat(v.tags()).isNull();
        assertThat(v.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("listMemberViews keeps all Internal_Attributes for a non-ADMIN admin-staff reader (FOREMAN) (Req 4.10)")
    void listMemberViewsKeepsInternalFieldsForAdminStaffForemanReader() {
        authenticate(String.valueOf(USER_ID), "ROLE_FOREMAN"); // admin-staff, not ADMIN
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(typedWorkerMember()));

        com.foremen.controller.model.TeamMemberView v = service.listMemberViews(PROJECT_ID).get(0);

        // An admin-staff reader (FOREMAN/ESTIMATOR/FINANCIER/MANAGER) is an Internal_Attribute_Viewer.
        assertThat(v.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(v.workerTypeCode()).isEqualTo("WELDER");
        assertThat(v.workerTypeName()).isEqualTo("Spawacz");
        assertThat(v.workerTypeActive()).isTrue();
        assertThat(v.nip()).isEqualTo("1234567890");
        assertThat(v.workerTypeMissing()).isFalse();
        assertThat(v.tags()).containsExactly("lead");
    }

    @Test
    @DisplayName("listMemberViews omits internal fields for an unauthenticated reader (not admin-staff) (Req 4.11)")
    void listMemberViewsMasksInternalFieldsForUnauthenticatedReader() {
        SecurityContextHolder.clearContext(); // overrides the @BeforeEach ADMIN authentication
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(typedWorkerMember()));

        com.foremen.controller.model.TeamMemberView v = service.listMemberViews(PROJECT_ID).get(0);

        assertThat(v.workerTypeId()).isNull();
        assertThat(v.nip()).isNull();
        assertThat(v.tags()).isNull();
        assertThat(v.workerTypeMissing()).isNull();
        assertThat(v.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    // --- listProjects (FOR-05-09 Req 3.6) ---

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    /** Authenticates the given principal name with the given authorities for the current test. */
    private static void authenticate(String principalName, String... authorities) {
        var granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principalName, "n/a", granted));
    }

    @Test
    @DisplayName("listProjects returns every project id ascending for an ADMIN caller (bypass, no scope filter)")
    void listProjectsAdminReturnsAllAscending() {
        authenticate("1", "ROLE_ADMIN");
        when(projectMemberDao.findDistinctProjectIdsByUserIdOrderByProjectIdAsc(USER_ID))
                .thenReturn(List.of(1L, 2L, 3L));

        List<Long> result = service.listProjects(USER_ID);

        assertThat(result).containsExactly(1L, 2L, 3L);
        verify(projectMemberDao).findDistinctProjectIdsByUserIdOrderByProjectIdAsc(USER_ID);
        // ADMIN bypass: the caller's allowed-project set is never consulted.
        verify(projectAccessCache, never()).get(anyLong());
    }

    @Test
    @DisplayName("listProjects restricts a non-ADMIN caller to the intersection with Accessible_Projects, ascending")
    void listProjectsNonAdminRestrictsToAccessibleProjectsAscending() {
        authenticate("5", "ROLE_MANAGER");
        when(projectMemberDao.findDistinctProjectIdsByUserIdOrderByProjectIdAsc(USER_ID))
                .thenReturn(List.of(1L, 2L, 3L, 4L));
        when(projectAccessCache.get(5L)).thenReturn(Set.of(4L, 2L)); // unordered set from the cache

        List<Long> result = service.listProjects(USER_ID);

        // Only the accessible ids survive, and the DAO's ascending order is preserved.
        assertThat(result).containsExactly(2L, 4L);
        verify(projectAccessCache).get(5L);
    }

    @Test
    @DisplayName("listProjects returns an empty list (not 404) when the user has no memberships")
    void listProjectsEmptyWhenUserHasNoMemberships() {
        authenticate("5", "ROLE_MANAGER");
        when(projectMemberDao.findDistinctProjectIdsByUserIdOrderByProjectIdAsc(USER_ID))
                .thenReturn(List.of());
        when(projectAccessCache.get(5L)).thenReturn(Set.of(1L, 2L));

        List<Long> result = service.listProjects(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listProjects returns an empty list when none of the user's projects are accessible to a non-ADMIN caller")
    void listProjectsEmptyWhenNoneAccessible() {
        authenticate("5", "ROLE_MANAGER");
        when(projectMemberDao.findDistinctProjectIdsByUserIdOrderByProjectIdAsc(USER_ID))
                .thenReturn(List.of(10L, 11L));
        when(projectAccessCache.get(5L)).thenReturn(Set.of(1L, 2L));

        List<Long> result = service.listProjects(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listProjects returns an empty list when a non-ADMIN caller has an empty Accessible_Projects set")
    void listProjectsEmptyWhenCallerHasNoAccess() {
        authenticate("5", "ROLE_MANAGER");
        when(projectMemberDao.findDistinctProjectIdsByUserIdOrderByProjectIdAsc(USER_ID))
                .thenReturn(List.of(1L, 2L));
        when(projectAccessCache.get(5L)).thenReturn(Set.of());

        List<Long> result = service.listProjects(USER_ID);

        assertThat(result).isEmpty();
    }

    // --- readiness: the team gate (FOR-05-09 task 13.1, Requirement 20) ---

    /**
     * Stubs the per-role ACTIVE count query ({@code countBy...}) for the readiness gate so each of the
     * six Assignable_Project_Role codes returns the supplied count. The default authenticated caller
     * is ADMIN, so the step-4 project-access gate is bypassed and the gate is computed from these
     * counts (FOR-05-09 Requirement 20 criterion 2).
     */
    private void stubActiveCounts(int manager, int foreman, int estimator,
                                  int worker, int financier, int client) {
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn((long) manager);
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "FOREMAN", AssignmentStatus.ACTIVE)).thenReturn((long) foreman);
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "ESTIMATOR", AssignmentStatus.ACTIVE)).thenReturn((long) estimator);
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "WORKER", AssignmentStatus.ACTIVE)).thenReturn((long) worker);
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "FINANCIER", AssignmentStatus.ACTIVE)).thenReturn((long) financier);
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "CLIENT", AssignmentStatus.ACTIVE)).thenReturn((long) client);
    }

    @Test
    @DisplayName("readiness is DONE with the key \"team\" and per-role counts when at least one ACTIVE FOREMAN exists (Req 20.1, 20.2)")
    void readinessDoneWhenActiveForemanExists() {
        stubActiveCounts(1, 2, 0, 3, 1, 1);

        com.foremen.controller.model.TeamReadiness readiness = service.readiness(PROJECT_ID);

        assertThat(readiness.key()).isEqualTo("team");
        assertThat(readiness.state()).isEqualTo(com.foremen.service.team.ReadinessState.DONE);
        assertThat(readiness.counts().manager()).isEqualTo(1);
        assertThat(readiness.counts().foreman()).isEqualTo(2);
        assertThat(readiness.counts().estimator()).isEqualTo(0);
        assertThat(readiness.counts().worker()).isEqualTo(3);
        assertThat(readiness.counts().financier()).isEqualTo(1);
        assertThat(readiness.counts().client()).isEqualTo(1);
    }

    @Test
    @DisplayName("readiness is DONE for exactly one ACTIVE FOREMAN (boundary ACTIVE FOREMAN == 1, Req 20.1)")
    void readinessDoneAtExactlyOneForeman() {
        stubActiveCounts(0, 1, 0, 0, 0, 0);

        com.foremen.controller.model.TeamReadiness readiness = service.readiness(PROJECT_ID);

        assertThat(readiness.state()).isEqualTo(com.foremen.service.team.ReadinessState.DONE);
        assertThat(readiness.counts().foreman()).isEqualTo(1);
    }

    @Test
    @DisplayName("readiness is BLOCKED with zeroed counts when the team is empty (no ACTIVE FOREMAN, Req 20.2)")
    void readinessBlockedWhenNoActiveForeman() {
        stubActiveCounts(0, 0, 0, 0, 0, 0);

        com.foremen.controller.model.TeamReadiness readiness = service.readiness(PROJECT_ID);

        assertThat(readiness.key()).isEqualTo("team");
        assertThat(readiness.state()).isEqualTo(com.foremen.service.team.ReadinessState.BLOCKED);
        assertThat(readiness.counts().manager()).isZero();
        assertThat(readiness.counts().foreman()).isZero();
        assertThat(readiness.counts().estimator()).isZero();
        assertThat(readiness.counts().worker()).isZero();
        assertThat(readiness.counts().financier()).isZero();
        assertThat(readiness.counts().client()).isZero();
    }

    @Test
    @DisplayName("readiness is BLOCKED when the project has members of other roles but no ACTIVE FOREMAN (Req 20.2)")
    void readinessBlockedWithOtherRolesButNoForeman() {
        stubActiveCounts(2, 0, 1, 4, 1, 3);

        com.foremen.controller.model.TeamReadiness readiness = service.readiness(PROJECT_ID);

        assertThat(readiness.state()).isEqualTo(com.foremen.service.team.ReadinessState.BLOCKED);
        // The non-FOREMAN counts are still reported (they just don't drive the gate).
        assertThat(readiness.counts().manager()).isEqualTo(2);
        assertThat(readiness.counts().worker()).isEqualTo(4);
        assertThat(readiness.counts().client()).isEqualTo(3);
    }

    @Test
    @DisplayName("readiness counts only ACTIVE members: the gate reads the ACTIVE-status count query for every role (D14, Req 20.2)")
    void readinessCountsOnlyActiveMembers() {
        stubActiveCounts(1, 1, 0, 0, 0, 0);

        service.readiness(PROJECT_ID);

        // Every per-role count query is constrained to AssignmentStatus.ACTIVE, so INACTIVE members
        // are never counted toward readiness (D14). No other assignment status is ever queried.
        for (String role : new String[] {"MANAGER", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER", "CLIENT"}) {
            verify(projectMemberDao).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                    PROJECT_ID, role, AssignmentStatus.ACTIVE);
        }
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), org.mockito.ArgumentMatchers.eq(AssignmentStatus.INACTIVE));
    }

    @Test
    @DisplayName("readiness raises 404 error.entity.not.found for a non-accessible project to a non-ADMIN caller, without counting (Req 20.10)")
    void readinessNonAccessibleProjectRaisesNotFound() {
        // A non-ADMIN caller whose Accessible_Projects do not include PROJECT_ID.
        authenticate("5", "ROLE_MANAGER");
        when(projectAccessCache.get(5L)).thenReturn(Set.of(1L, 2L));

        assertThatThrownBy(() -> service.readiness(PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                });

        // The project-access gate fails before any count query runs (no state read).
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), any());
    }

    @Test
    @DisplayName("readiness computes the gate for a non-ADMIN caller whose Accessible_Projects include the project (Req 20.10)")
    void readinessAccessibleProjectForNonAdminCaller() {
        authenticate("5", "ROLE_FOREMAN");
        when(projectAccessCache.get(5L)).thenReturn(Set.of(PROJECT_ID));
        stubActiveCounts(1, 1, 0, 0, 0, 0);

        com.foremen.controller.model.TeamReadiness readiness = service.readiness(PROJECT_ID);

        assertThat(readiness.state()).isEqualTo(com.foremen.service.team.ReadinessState.DONE);
        assertThat(readiness.counts().foreman()).isEqualTo(1);
    }

    // --- audit snapshot per team change (FOR-05-09 task 12.1, Requirement 17) ---

    private static final com.fasterxml.jackson.databind.ObjectMapper AUDIT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Captures the single AuditLogEntity written by the service and returns it. */
    private com.foremen.service.audit.AuditLogEntity captureAudit() {
        ArgumentCaptor<com.foremen.service.audit.AuditLogEntity> captor =
                ArgumentCaptor.forClass(com.foremen.service.audit.AuditLogEntity.class);
        verify(auditLogDao).save(captor.capture());
        return captor.getValue();
    }

    /** Parses an audit snapshot JSON string into a Jackson tree for field assertions. */
    private static com.fasterxml.jackson.databind.JsonNode parse(String snapshot) {
        try {
            return AUDIT_MAPPER.readTree(snapshot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Asserts the snapshot carries exactly the Member_Snapshot fields and none of the forbidden ones. */
    private static void assertSnapshotShape(com.fasterxml.jackson.databind.JsonNode node) {
        // The six Member_Snapshot fields are present (Req 17.1).
        assertThat(node.has("userId")).isTrue();
        assertThat(node.has("projectId")).isTrue();
        assertThat(node.has("roleCode")).isTrue();
        assertThat(node.has("workerTypeCode")).isTrue();
        assertThat(node.has("assignmentStatus")).isTrue();
        assertThat(node.has("tags")).isTrue();
        // No forbidden field is ever present (Req 17.7): password/token/rate/tariff/tier/cost.
        for (String forbidden : new String[] {
                "password", "token", "rate", "tariff", "tier", "tierPercentage", "cost", "otp"}) {
            assertThat(node.has(forbidden)).as("snapshot must not carry " + forbidden).isFalse();
        }
    }

    @Test
    @DisplayName("assign writes exactly one CREATE audit row with the membership id, performer, and after Member_Snapshot (Req 17.1)")
    void assignWritesOneCreateAuditRow() {
        UserEntity worker = userWithRole("WORKER", true);
        worker.setId(USER_ID);
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(type));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> {
            ProjectMemberEntity m = inv.getArgument(0);
            m.setId(900L); // simulate the generated membership id
            return m;
        });

        service.assign(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, List.of("lead"));

        com.foremen.service.audit.AuditLogEntity audit = captureAudit();
        assertThat(audit.getEntityClass()).isEqualTo("ProjectMemberEntity");
        assertThat(audit.getEntityId()).isEqualTo(900L);
        assertThat(audit.getOperation()).isEqualTo("CREATE");
        assertThat(audit.getPerformedBy()).isEqualTo("1"); // the ADMIN principal id from @BeforeEach
        assertThat(audit.getPerformedAt()).isNotNull();
        // CREATE: only the after-snapshot (no before-state).
        assertThat(audit.getSnapshotBefore()).isNull();
        com.fasterxml.jackson.databind.JsonNode after = parse(audit.getSnapshotAfter());
        assertSnapshotShape(after);
        assertThat(after.get("userId").asLong()).isEqualTo(USER_ID);
        assertThat(after.get("projectId").asLong()).isEqualTo(PROJECT_ID);
        assertThat(after.get("roleCode").asText()).isEqualTo("WORKER");
        assertThat(after.get("workerTypeCode").asText()).isEqualTo("WELDER");
        assertThat(after.get("assignmentStatus").asText()).isEqualTo("ACTIVE");
        assertThat(after.get("tags").isArray()).isTrue();
        assertThat(after.get("tags").get(0).asText()).isEqualTo("lead");
    }

    @Test
    @DisplayName("assign of a non-WORKER with no worker type records an empty workerTypeCode in the snapshot (Req 17.1)")
    void assignEmptyWorkerTypeCodeWhenNone() {
        UserEntity manager = activeAssignableUser(); // MANAGER, no worker type
        manager.setId(USER_ID);
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> {
            ProjectMemberEntity m = inv.getArgument(0);
            m.setId(901L);
            return m;
        });

        service.assign(USER_ID, PROJECT_ID, null);

        com.foremen.service.audit.AuditLogEntity audit = captureAudit();
        com.fasterxml.jackson.databind.JsonNode after = parse(audit.getSnapshotAfter());
        assertThat(after.get("workerTypeCode").asText()).isEqualTo(""); // empty when none (Req 17.1)
        assertThat(after.get("tags").isArray()).isTrue();
        assertThat(after.get("tags")).isEmpty();
    }

    @Test
    @DisplayName("remove writes exactly one DELETE audit row with the removed membership id and the before Member_Snapshot (Req 17.3)")
    void removeWritesOneDeleteAuditRow() {
        ProjectMemberEntity member = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        // Two ACTIVE FOREMAN are not guarded; FOREMAN is not a guarded role anyway.

        service.remove(USER_ID, PROJECT_ID);

        com.foremen.service.audit.AuditLogEntity audit = captureAudit();
        assertThat(audit.getEntityClass()).isEqualTo("ProjectMemberEntity");
        assertThat(audit.getEntityId()).isEqualTo(500L); // memberOfRole sets id 500
        assertThat(audit.getOperation()).isEqualTo("DELETE");
        assertThat(audit.getPerformedBy()).isEqualTo("1");
        // DELETE: only the before-snapshot (no after-state).
        assertThat(audit.getSnapshotAfter()).isNull();
        com.fasterxml.jackson.databind.JsonNode before = parse(audit.getSnapshotBefore());
        assertSnapshotShape(before);
        assertThat(before.get("userId").asLong()).isEqualTo(USER_ID);
        assertThat(before.get("roleCode").asText()).isEqualTo("FOREMAN");
        assertThat(before.get("assignmentStatus").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("deactivate writes exactly one UPDATE audit row whose before/after snapshots differ only in assignmentStatus (Req 17.2)")
    void deactivateWritesOneUpdateAuditRow() {
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        com.foremen.service.audit.AuditLogEntity audit = captureAudit();
        assertThat(audit.getEntityId()).isEqualTo(500L);
        assertThat(audit.getOperation()).isEqualTo("UPDATE");
        assertThat(audit.getPerformedBy()).isEqualTo("1");
        com.fasterxml.jackson.databind.JsonNode before = parse(audit.getSnapshotBefore());
        com.fasterxml.jackson.databind.JsonNode after = parse(audit.getSnapshotAfter());
        assertSnapshotShape(before);
        assertSnapshotShape(after);
        // Only the Assignment_Status differs between the two snapshots (Req 17.2).
        assertThat(before.get("assignmentStatus").asText()).isEqualTo("ACTIVE");
        assertThat(after.get("assignmentStatus").asText()).isEqualTo("INACTIVE");
        assertThat(before.get("roleCode").asText()).isEqualTo(after.get("roleCode").asText());
        assertThat(before.get("workerTypeCode").asText()).isEqualTo(after.get("workerTypeCode").asText());
        assertThat(before.get("tags")).isEqualTo(after.get("tags"));
    }

    @Test
    @DisplayName("worker-type change writes exactly one UPDATE audit row whose snapshots differ only in workerTypeCode (Req 17.2, 17.8)")
    void workerTypeChangeWritesOneUpdateAuditRow() {
        ProjectMemberEntity worker = workerMemberWithType(null); // Uncategorized_Worker: empty before code
        WorkerTypeEntity newType = activeWorkerType(WORKER_TYPE_ID);
        newType.setCode("WELDER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(newType));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        com.foremen.service.audit.AuditLogEntity audit = captureAudit();
        assertThat(audit.getEntityId()).isEqualTo(600L); // workerMemberWithType sets id 600
        assertThat(audit.getOperation()).isEqualTo("UPDATE");
        com.fasterxml.jackson.databind.JsonNode before = parse(audit.getSnapshotBefore());
        com.fasterxml.jackson.databind.JsonNode after = parse(audit.getSnapshotAfter());
        // Only the Worker_Type code differs; the before is empty (first assignment, Req 17.8).
        assertThat(before.get("workerTypeCode").asText()).isEmpty();
        assertThat(after.get("workerTypeCode").asText()).isEqualTo("WELDER");
        assertThat(before.get("assignmentStatus").asText()).isEqualTo(after.get("assignmentStatus").asText());
        assertThat(before.get("tags")).isEqualTo(after.get("tags"));
    }

    @Test
    @DisplayName("tag change writes exactly one UPDATE audit row whose snapshots differ only in tags (Req 17.2, 17.8)")
    void tagChangeWritesOneUpdateAuditRow() {
        ProjectMemberEntity member = memberWithTags(List.of("old"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("lead", "spec"));

        com.foremen.service.audit.AuditLogEntity audit = captureAudit();
        assertThat(audit.getEntityId()).isEqualTo(700L); // memberWithTags sets id 700
        assertThat(audit.getOperation()).isEqualTo("UPDATE");
        com.fasterxml.jackson.databind.JsonNode before = parse(audit.getSnapshotBefore());
        com.fasterxml.jackson.databind.JsonNode after = parse(audit.getSnapshotAfter());
        // Only the tags differ.
        assertThat(before.get("tags").get(0).asText()).isEqualTo("old");
        assertThat(after.get("tags").get(0).asText()).isEqualTo("lead");
        assertThat(after.get("tags").get(1).asText()).isEqualTo("spec");
        assertThat(before.get("roleCode").asText()).isEqualTo(after.get("roleCode").asText());
        assertThat(before.get("workerTypeCode").asText()).isEqualTo(after.get("workerTypeCode").asText());
        assertThat(before.get("assignmentStatus").asText()).isEqualTo(after.get("assignmentStatus").asText());
    }

    @Test
    @DisplayName("an idempotent status no-op writes no audit row (Req 17.4)")
    void statusNoOpWritesNoAudit() {
        ProjectMemberEntity activeForeman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(activeForeman));

        service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("an idempotent worker-type no-op writes no audit row (Req 17.4)")
    void workerTypeNoOpWritesNoAudit() {
        WorkerTypeEntity current = activeWorkerType(WORKER_TYPE_ID);
        current.setCode("WELDER");
        ProjectMemberEntity worker = workerMemberWithType(current);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));

        service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("an idempotent tag no-op writes no audit row (Req 17.4)")
    void tagNoOpWritesNoAudit() {
        ProjectMemberEntity member = memberWithTags(List.of("lead", "spec"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));

        service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("  lead  ", "spec"));

        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("a rejected op (invalid tag before any mutation) writes no audit row (Req 17.5)")
    void rejectedOpWritesNoAudit() {
        String tooLong = "x".repeat(51);

        assertThatThrownBy(() -> service.assign(USER_ID, PROJECT_ID, null, null, List.of(tooLong)))
                .isInstanceOf(ForemenApiException.class);

        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("a rejected remove (last ACTIVE MANAGER) writes no audit row (Req 17.5)")
    void rejectedRemoveWritesNoAudit() {
        ProjectMemberEntity manager = activeMemberOfRole("MANAGER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(USER_ID, PROJECT_ID))
                .isInstanceOf(ForemenApiException.class);

        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("an audit-write failure propagates so the whole operation rolls back (Req 17.6)")
    void auditWriteFailurePropagates() {
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // The audit write fails: the exception must propagate out of the service so the surrounding
        // @Transactional unit rolls back the whole deactivate (Req 17.6).
        when(auditLogDao.save(any(com.foremen.service.audit.AuditLogEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("audit insert failed"));

        assertThatThrownBy(() -> service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // --- in-app notifications + access-cache invalidation (FOR-05-09 task 12.2, Requirement 18/16) ---
    //
    // The default @BeforeEach authenticates the ADMIN principal "1"; the affected member is USER_ID
    // (7), so by default the acting caller is NOT the member and the notification is emitted (Req
    // 18.4). The self-operation tests re-authenticate as principal String.valueOf(USER_ID).

    @Test
    @DisplayName("assign emits exactly one member-assigned notification to the assigned member with a workspace deep-link (Req 18.1)")
    void assignEmitsMemberAssignedNotification() {
        UserEntity user = activeAssignableUser();            // Company_Role = MANAGER
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.assign(USER_ID, PROJECT_ID, null);

        // Exactly one notification, to the assigned member, with the member-assigned type and the
        // /projects/{projectId} workspace deep-link (Req 18.1).
        verify(notificationService, times(1)).create(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("notification.team.memberAssigned"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("/projects/" + PROJECT_ID));
        org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("deactivate emits exactly one status-changed notification to the member (Req 18.2)")
    void deactivateEmitsStatusChangedNotification() {
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        verify(notificationService, times(1)).create(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("notification.team.statusChanged"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("/projects/" + PROJECT_ID));
        org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("reactivate emits exactly one status-changed notification to the member (Req 18.2)")
    void reactivateEmitsStatusChangedNotification() {
        ProjectMemberEntity worker = memberOfRole("WORKER", AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        verify(notificationService, times(1)).create(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("notification.team.statusChanged"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("/projects/" + PROJECT_ID));
    }

    @Test
    @DisplayName("worker-type change emits exactly one worker-type-changed notification to the worker only, with no tier/rate/cost in the body (Req 18.10, 18.12)")
    void workerTypeChangeEmitsWorkerTypeChangedNotificationToWorkerOnly() {
        ProjectMemberEntity worker = workerMemberWithType(null); // Uncategorized_Worker
        WorkerTypeEntity type = activeWorkerType(WORKER_TYPE_ID);
        type.setCode("WELDER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(workerTypeDao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(type));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        verify(notificationService, times(1)).create(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("notification.team.workerTypeChanged"),
                body.capture(),
                org.mockito.ArgumentMatchers.eq("/projects/" + PROJECT_ID));
        org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
        // No tier/rate/cost leaks into the worker-type notification body (Req 18.10).
        String b = body.getValue() == null ? "" : body.getValue().toLowerCase(Locale.ROOT);
        assertThat(b).doesNotContain("tier").doesNotContain("rate").doesNotContain("cost")
                .doesNotContain("tariff");
    }

    @Test
    @DisplayName("remove emits exactly one member-removed notification to the removed member with NO deep-link (Req 18.3)")
    void removeEmitsMemberRemovedNotificationWithNoDeepLink() {
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));

        service.remove(USER_ID, PROJECT_ID);

        verify(notificationService, times(1)).create(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("notification.team.memberRemoved"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull()); // no deep-link (Req 18.3)
        org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("a self-assignment (acting caller == affected member) emits no notification (Req 18.4)")
    void selfAssignEmitsNoNotification() {
        authenticate(String.valueOf(USER_ID), "ROLE_ADMIN"); // the acting caller IS the member
        UserEntity user = activeAssignableUser();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.assign(USER_ID, PROJECT_ID, null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a self-removal (acting caller == affected member) emits no notification (Req 18.4)")
    void selfRemoveEmitsNoNotification() {
        authenticate(String.valueOf(USER_ID), "ROLE_ADMIN"); // the acting caller IS the member
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));

        service.remove(USER_ID, PROJECT_ID);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a self-deactivation (acting caller == affected member) emits no notification (Req 18.4)")
    void selfDeactivateEmitsNoNotification() {
        authenticate(String.valueOf(USER_ID), "ROLE_ADMIN");
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a tag change emits no notification (Req 18.11)")
    void tagChangeEmitsNoNotification() {
        ProjectMemberEntity member = memberWithTags(List.of("old"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("lead", "spec"));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("an idempotent status no-op emits no notification (Req 18.8)")
    void statusNoOpEmitsNoNotification() {
        ProjectMemberEntity activeForeman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(activeForeman));

        service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("an idempotent worker-type no-op emits no notification (Req 18.8)")
    void workerTypeNoOpEmitsNoNotification() {
        WorkerTypeEntity current = activeWorkerType(WORKER_TYPE_ID);
        current.setCode("WELDER");
        ProjectMemberEntity worker = workerMemberWithType(current);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));

        service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a rejected op (last ACTIVE MANAGER remove) emits no notification (Req 18.6)")
    void rejectedRemoveEmitsNoNotification() {
        ProjectMemberEntity manager = activeMemberOfRole("MANAGER");
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                PROJECT_ID, "MANAGER", AssignmentStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(USER_ID, PROJECT_ID))
                .isInstanceOf(ForemenApiException.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a NotificationService failure is swallowed: assign still succeeds and invalidates the cache (Req 18.7)")
    void notificationFailureDoesNotFailAssign() {
        UserEntity user = activeAssignableUser();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(notificationService).create(anyLong(), any(), any(), any());

        // The committed assign is kept and the success response returned despite the notification failure.
        ProjectMemberEntity saved = service.assign(USER_ID, PROJECT_ID, null);

        assertThat(saved).isNotNull();
        verify(projectMemberDao).save(any(ProjectMemberEntity.class));
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("deactivate invalidates the ProjectAccessCache for the affected member (Req 16.1)")
    void deactivateInvalidatesAccessCache() {
        ProjectMemberEntity foreman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(foreman));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("reactivate invalidates the ProjectAccessCache for the affected member (Req 16.1)")
    void reactivateInvalidatesAccessCache() {
        ProjectMemberEntity worker = memberOfRole("WORKER", AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        verify(projectAccessCache).invalidate(USER_ID);
    }

    // --- assignAtCreation: project-creation restriction (FOR-05-09 task 15.1, Requirement 26) ---

    @Test
    @DisplayName("assignAtCreation persists an admin-staff member with ACTIVE status, no worker type, no tags (Req 26.1)")
    void assignAtCreationPersistsAdminStaffMember() {
        UserEntity manager = activeAssignableUser(); // Company_Role = MANAGER
        RoleEntity companyRole = manager.getRole();
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(manager));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberEntity saved = service.assignAtCreation(USER_ID, PROJECT_ID, null, null);

        assertThat(saved.getProjectRole()).isSameAs(companyRole);          // D2
        assertThat(saved.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE); // Req 26.1
        assertThat(saved.getWorkerType()).isNull();                        // no worker type at creation
        assertThat(saved.getTags()).isEmpty();                             // no tags at creation
        verify(projectAccessCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("assignAtCreation accepts a CLIENT user under the Client_Role (Req 26.4, 26.1)")
    void assignAtCreationAcceptsClient() {
        UserEntity client = userWithRole("CLIENT", true);
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(client));
        when(projectMemberDao.save(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberEntity saved = service.assignAtCreation(USER_ID, PROJECT_ID, null, null);

        assertThat(saved.getProjectRole().getCode()).isEqualTo("CLIENT");
        assertThat(saved.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("assignAtCreation rejects a Worker_Role entry with 400 error.project.member.role.not.allowed.at.creation (Req 26.2)")
    void assignAtCreationRejectsWorkerRole() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("WORKER", true)));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.role.not.allowed.at.creation");
                });

        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("assignAtCreation rejects a Worker_Role entry even when it carries a workerTypeId (Req 26.2 > 26.3)")
    void assignAtCreationWorkerRoleBeatsWorkerType() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("WORKER", true)));

        // Req 26.2 (Worker_Role not allowed at creation) is ranked before Req 26.3 (worker type not
        // allowed), so a WORKER entry carrying a workerTypeId is reported as not-allowed-at-creation.
        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.not.allowed.at.creation"));

        verify(workerTypeDao, never()).findById(anyLong());
        verify(projectMemberDao, never()).save(any());
    }

    @Test
    @DisplayName("assignAtCreation rejects any non-null workerTypeId with 400 error.project.member.worker.type.not.allowed, without a lookup (Req 26.3)")
    void assignAtCreationRejectsWorkerType() {
        UserEntity manager = activeAssignableUser(); // Company_Role = MANAGER
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.worker.type.not.allowed");
                });

        // The worker type is never looked up (Req 26.3) and no row is persisted.
        verify(workerTypeDao, never()).findById(anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("assignAtCreation rejects an ADMIN / non-system role with 400 error.project.member.role.not.assignable (Req 26.10)")
    void assignAtCreationRejectsAdminRole() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("ADMIN", true)));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.not.assignable"));

        verify(projectMemberDao, never()).save(any());
    }

    @Test
    @DisplayName("assignAtCreation: role.not.assignable (ADMIN) wins over the Worker_Role check (Req 26.5 sub-order)")
    void assignAtCreationRoleNotAssignableWinsOverWorkerRole() {
        // An ADMIN user is both non-assignable AND (trivially) not WORKER; role.not.assignable (Req 6.1)
        // is ranked first, so it is reported even if a workerTypeId is present.
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("ADMIN", false)));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.not.assignable"));
    }

    @Test
    @DisplayName("assignAtCreation: Worker_Role check wins over user.inactive (Req 26.5 sub-order)")
    void assignAtCreationWorkerRoleWinsOverInactive() {
        // An inactive WORKER user violates both Req 26.2 (worker role at creation) and Req 6.3
        // (inactive). Req 26.2 is ranked before Req 6.3 in the Req 26.5 sub-order.
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("WORKER", false)));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.not.allowed.at.creation"));
    }

    @Test
    @DisplayName("assignAtCreation rejects an inactive admin-staff user with 400 error.project.member.user.inactive (Req 6.3)")
    void assignAtCreationRejectsInactiveAdminStaff() {
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(userWithRole("MANAGER", false)));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.user.inactive"));

        verify(projectMemberDao, never()).save(any());
    }

    @Test
    @DisplayName("assignAtCreation rejects a supplied projectRoleId != Company_Role with 400 error.project.member.role.mismatch (Req 26.5 field validation)")
    void assignAtCreationRejectsRoleMismatch() {
        UserEntity manager = activeAssignableUser(); // Company_Role = MANAGER
        when(userDao.findById(USER_ID)).thenReturn(Optional.of(manager));
        when(roleDao.findById(ROLE_ID)).thenReturn(Optional.of(roleWithCode("FOREMAN")));

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, ROLE_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.role.mismatch");
                });

        // role.mismatch is a step-3 field check, before the duplicate / composition checks.
        verify(projectMemberDao, never()).existsByUserIdAndProjectId(anyLong(), anyLong());
        verify(projectMemberDao, never()).save(any());
    }

    @Test
    @DisplayName("assignAtCreation rejects a duplicate pair with 409 error.project.member.duplicate (Req 26.9)")
    void assignAtCreationRejectsDuplicate() {
        // A null projectRoleId skips role.mismatch; the duplicate check (step 6) fires before user
        // existence / composition, matching the Req 26.5 per-entry order.
        when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.assignAtCreation(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.duplicate");
                });

        verify(projectMemberDao, never()).save(any());
        verifyNoInteractions(userDao, projectAccessCache);
    }
}
