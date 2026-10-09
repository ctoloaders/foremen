package com.foremen.service;

import com.foremen.controller.model.TeamMemberView;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.ProjectMemberServiceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FOR-05-09 task 9.7 — focused, consolidated unit tests for the three idempotent Attribute_Update
 * <em>no-op</em> branches of {@link ProjectMemberService#updateAttributes} (Requirements 14.7, 15.6,
 * 27.6). The service's PATCH dispatcher routes to one of three attribute changes — worker-type
 * ({@code changeWorkerType}), tag ({@code changeTags}), and assignment-status
 * ({@code changeAssignmentStatus}) — and each has a no-op path reached when the submitted value
 * already equals the member's current value:
 *
 * <ul>
 *   <li><b>worker type</b> — the WORKER member already carries the submitted Worker_Type (even when
 *       that type is now inactive, Requirement 14.7);</li>
 *   <li><b>tags</b> — the normalized submitted list equals the stored list in the same
 *       case-sensitive positions (Requirement 15.6);</li>
 *   <li><b>status</b> — the member already has the submitted Assignment_Status (Requirement 27.6).</li>
 * </ul>
 *
 * <p>For every no-op this class asserts the uniform contract the design guarantees: the call returns
 * 200 with the <em>current</em> {@link TeamMemberView} (verified by the returned view reflecting the
 * unchanged attribute), the member row is left untouched ({@code projectMemberDao.save} is never
 * invoked), the user's project access is not disturbed ({@code projectAccessCache.invalidate} is
 * never invoked), and — defensively — no Audit_Log row is written ({@code auditLogDao} has no
 * interaction).
 *
 * <p><b>Audit / notification status.</b> Audit snapshots and in-app notifications are wired by the
 * later FOR-05-09 tasks 12.x; the {@link ProjectMemberService} under test does not yet depend on a
 * notification service, so "no notification" holds structurally (there is no collaborator to call)
 * and is asserted here only for the Audit_Log DAO via {@code verifyNoInteractions(auditLogDao)}.
 * When the audit/notification writes land, the no-op branches must continue to make no such write,
 * and this test (plus its audit-side counterpart) protects that invariant.
 *
 * <p>The tests mock every {@link ProjectMemberService} collaborator and authenticate an ADMIN
 * caller so the canonical rejection checklist's project-access gate is bypassed and each flow
 * reaches the no-op branch under test (mirroring {@link ProjectMemberServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class AttributeUpdateNoOpTest {

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
    private ProjectMemberServiceMapper projectMemberServiceMapper;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private com.foremen.service.offer.NotificationService notificationService;

    @InjectMocks
    private ProjectMemberService service;

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 42L;
    private static final Long WORKER_TYPE_ID = 11L;

    /**
     * Authenticate an ADMIN caller so the canonical checklist's step-4 project-access check is
     * bypassed and each update reaches the no-op branch under test. ADMIN is also an
     * Internal_Attribute_Viewer, so the returned {@link TeamMemberView} carries the internal
     * worker-type / tag fields the assertions read.
     */
    @BeforeEach
    void authenticateAdmin() {
        authenticate("1", "ROLE_ADMIN");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- worker-type no-op (Requirement 14.7) ---

    @Test
    @DisplayName("worker-type no-op: same type returns 200 with the current view, no save, no cache invalidation, no audit")
    void workerTypeNoOpMakesNoStateChange() {
        WorkerTypeEntity current = activeWorkerType(WORKER_TYPE_ID, "WELDER");
        ProjectMemberEntity worker = workerMemberWithType(current);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));

        TeamMemberView view = service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        // 200 with the CURRENT view: the worker type is unchanged on the row and reflected in the view.
        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(view.workerTypeCode()).isEqualTo("WELDER");
        assertThat(worker.getWorkerType()).isSameAs(current);
        // No state change: no row saved, no cache invalidation, no audit row.
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("worker-type no-op holds even when the current worker type is now inactive (14.7 over 14.3)")
    void workerTypeNoOpHoldsForInactiveCurrentType() {
        // The member's current type has since been deactivated; re-submitting it is still a clean
        // no-op (criterion 3 exempts the current type), with no save, invalidation, or audit.
        WorkerTypeEntity inactiveCurrent = activeWorkerType(WORKER_TYPE_ID, "WELDER");
        inactiveCurrent.setActive(false);
        ProjectMemberEntity worker = workerMemberWithType(inactiveCurrent);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(worker));

        TeamMemberView view = service.updateAttributes(USER_ID, PROJECT_ID, null, WORKER_TYPE_ID, null);

        assertThat(view.workerTypeId()).isEqualTo(WORKER_TYPE_ID);
        assertThat(worker.getWorkerType()).isSameAs(inactiveCurrent);
        // The same-type path needs no worker-type lookup at all.
        verify(workerTypeDao, never()).findById(anyLong());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
        verifyNoInteractions(auditLogDao);
    }

    // --- tag no-op (Requirement 15.6) ---

    @Test
    @DisplayName("tag no-op: normalized list equal to the stored list in the same case-sensitive positions returns 200, no save, no cache invalidation, no audit")
    void tagNoOpMakesNoStateChange() {
        ProjectMemberEntity member = workerMemberWithTags(List.of("lead", "spec"));
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(member));

        // "  lead  " trims to "lead"; the list normalizes to the exact stored list in the same positions.
        TeamMemberView view = service.updateAttributes(USER_ID, PROJECT_ID, null, null, List.of("  lead  ", "spec"));

        // 200 with the CURRENT view: the tag list is unchanged on the row and reflected in the view.
        assertThat(view.tags()).containsExactly("lead", "spec");
        assertThat(member.getTags()).containsExactly("lead", "spec");
        // No state change: no row saved, no cache invalidation, no audit row.
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
        verifyNoInteractions(auditLogDao);
    }

    // --- status no-op (Requirement 27.6) ---

    @Test
    @DisplayName("status no-op: requesting the already-current status returns 200 with the current view, no save, no cache invalidation, no audit")
    void statusNoOpMakesNoStateChange() {
        ProjectMemberEntity activeForeman = memberOfRole("FOREMAN", AssignmentStatus.ACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(activeForeman));

        TeamMemberView view = service.updateAttributes(USER_ID, PROJECT_ID, "ACTIVE", null, null);

        // 200 with the CURRENT view: the status is unchanged on the row and reflected in the view.
        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(activeForeman.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        // No state change: no row saved, no cache invalidation, no audit row.
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
        verifyNoInteractions(auditLogDao);
    }

    @Test
    @DisplayName("status no-op: re-deactivating the only ACTIVE MANAGER is a clean no-op (not a 409), with no save/invalidate/audit and no last-ACTIVE count")
    void statusNoOpReDeactivateLastManagerIsCleanNoOp() {
        // The member is already INACTIVE; re-deactivating changes no ACTIVE count, so the last-ACTIVE
        // guard must not fire and the call is a clean idempotent no-op (Req 27.6 over 27.7).
        ProjectMemberEntity inactiveManager = memberOfRole("MANAGER", AssignmentStatus.INACTIVE);
        when(projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(Optional.of(inactiveManager));

        TeamMemberView view = service.updateAttributes(USER_ID, PROJECT_ID, "INACTIVE", null, null);

        assertThat(view.assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        assertThat(inactiveManager.getAssignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        // The guard is never even consulted for a no-op, and nothing is persisted / invalidated / audited.
        verify(projectMemberDao, never()).countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                anyLong(), any(), any());
        verify(projectMemberDao, never()).save(any());
        verify(projectAccessCache, never()).invalidate(anyLong());
        verifyNoInteractions(auditLogDao);
    }

    // --- fixtures ---

    /** An active worker type with the given id and code. */
    private static WorkerTypeEntity activeWorkerType(Long id, String code) {
        WorkerTypeEntity type = new WorkerTypeEntity();
        type.setId(id);
        type.setActive(true);
        type.setCode(code);
        type.setNamePL(code + "_PL");
        return type;
    }

    /** A persisted-style user with id, name, email, status, active flag, and Company_Role. */
    private static UserEntity user(Long id, String name, String roleCode) {
        RoleEntity role = new RoleEntity();
        role.setId(100L + id);
        role.setCode(roleCode);
        role.setNameRU(roleCode + "_RU");
        role.setNamePL(roleCode + "_PL");
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        user.setEmail(name.toLowerCase() + "@x.io");
        user.setRole(role);
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    /** A WORKER membership for the pair carrying the given (possibly null) worker type. */
    private static ProjectMemberEntity workerMemberWithType(WorkerTypeEntity type) {
        UserEntity worker = user(USER_ID, "Will", "WORKER");
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(600L);
        member.setUser(worker);
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(worker.getRole());
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        member.setWorkerType(type);
        member.setTags(new ArrayList<>());
        return member;
    }

    /** A WORKER membership for the pair carrying the given stored tags (and an active worker type). */
    private static ProjectMemberEntity workerMemberWithTags(List<String> tags) {
        ProjectMemberEntity member = workerMemberWithType(activeWorkerType(WORKER_TYPE_ID, "WELDER"));
        member.setId(700L);
        member.setTags(new ArrayList<>(tags));
        return member;
    }

    /** A membership for the pair with the given role code and assignment status. */
    private static ProjectMemberEntity memberOfRole(String roleCode, AssignmentStatus status) {
        UserEntity member = user(USER_ID, "Member", roleCode);
        ProjectMemberEntity entity = new ProjectMemberEntity();
        entity.setId(500L);
        entity.setUser(member);
        entity.setProjectId(PROJECT_ID);
        entity.setProjectRole(member.getRole());
        entity.setAssignmentStatus(status);
        entity.setTags(new ArrayList<>());
        return entity;
    }

    /** Authenticates the given principal name with the given authorities for the current test. */
    private static void authenticate(String principalName, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .map(a -> (GrantedAuthority) a)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principalName, "n/a", granted));
    }
}
