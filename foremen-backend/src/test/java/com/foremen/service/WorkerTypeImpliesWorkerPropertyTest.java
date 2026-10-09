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
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based coverage of FOR-05-09 design <b>Property 3 — Worker_Type present implies the
 * Project_Role is WORKER (invariant)</b> for the two code paths that may attach a Worker_Type to a
 * {@link ProjectMemberEntity}: the 5-arg {@link ProjectMemberService#assign(Long, Long, Long, Long, java.util.List)}
 * (worker type submitted in the add dialog) and the worker-type PATCH
 * {@code changeWorkerType} reached through {@link ProjectMemberService#updateAttributes}.
 *
 * <p><b>Property 3.</b> <i>For any</i> sequence of accepted or rejected assign / Attribute_Update /
 * (de)activate / remove / worker-record / project-creation / Worker_Type-deactivation operations,
 * every resulting Project_Member satisfies: if it has a Worker_Type then its Project_Role code is
 * {@code WORKER}.
 *
 * <p>Here the invariant is validated at its two <em>enforcement points</em> in
 * {@link ProjectMemberService}: a Worker_Type may be attached only when the member's role is
 * {@code WORKER}. Equivalently — the direction we assert — for an arbitrary assignable active user
 * and an arbitrary non-null {@code workerTypeId}:
 *
 * <ul>
 *   <li>when the user's Company_Role is {@code WORKER}, assign persists exactly one member and that
 *       persisted member carries the submitted Worker_Type (and its role is {@code WORKER}); and</li>
 *   <li>when the user's Company_Role is <em>any non-WORKER</em> Assignable_Project_Role, assign is
 *       rejected with HTTP 400 {@code error.project.member.worker.type.not.allowed} and persists
 *       nothing.</li>
 * </ul>
 *
 * So a persisted member can carry a Worker_Type only under a {@code WORKER} role — the invariant
 * holds across the whole input space of (role, workerTypeId). The optional second half repeats the
 * same assertion for the {@code changeWorkerType} PATCH path (set/replace), where a non-WORKER
 * target is rejected with the same code and the member keeps its (null) worker type.
 *
 * <p>Mock-based, ADMIN-authenticated, following the style of {@link ProjectMemberRoleMismatchPropertyTest}:
 * Mockito-mocked DAOs + {@link ProjectAccessCache}, caller authenticated ADMIN so the step-4
 * project-access check is bypassed and every generated request reaches the step-8 worker-type
 * decision. The user is always active with an Assignable_Project_Role so the only composition check
 * that can fire is the worker-type rule under test.
 *
 * <p><b>Validates: Requirements 14.1</b>
 */
// Feature: FOR-05-09-team-selection, Property 3
@Tag("Feature: FOR-05-09-team-selection, Property 3")
class WorkerTypeImpliesWorkerPropertyTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 42L;

    private static final String WORKER_CODE = "WORKER";

    /** The non-WORKER Assignable_Project_Role codes (any of these must reject a submitted worker type). */
    private static final List<String> NON_WORKER_ASSIGNABLE_CODES =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "FINANCIER", "CLIENT");

    /**
     * Property 3 — assign path, WORKER half. For an arbitrary non-null {@code workerTypeId}, assigning
     * a WORKER-Company_Role active user persists exactly one member whose role code is {@code WORKER}
     * and whose Worker_Type is the submitted (active) type: a persisted Worker_Type therefore implies
     * a WORKER role.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 3")
    void assignWorkerWithWorkerTypePersistsItUnderWorkerRole(@ForAll("workerTypeId") long workerTypeId) {
        Fixture f = new Fixture();
        try {
            UserEntity user = activeUserWithRole(WORKER_CODE);
            WorkerTypeEntity type = activeWorkerType(workerTypeId);
            when(f.projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
            when(f.userDao.findById(USER_ID)).thenReturn(Optional.of(user));
            when(f.workerTypeDao.findById(workerTypeId)).thenReturn(Optional.of(type));
            when(f.projectMemberDao.save(any(ProjectMemberEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ProjectMemberEntity saved = f.service.assign(USER_ID, PROJECT_ID, null, workerTypeId, null);

            assertThat(saved).isNotNull();
            // A persisted Worker_Type is present...
            assertThat(saved.getWorkerType()).isSameAs(type);
            // ...only ever under a WORKER role (Property 3 invariant).
            assertThat(saved.getProjectRole().getCode()).isEqualTo(WORKER_CODE);
            assertThat(saved.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(f.projectMemberDao).save(any(ProjectMemberEntity.class));
        } finally {
            f.close();
        }
    }

    /**
     * Property 3 — assign path, non-WORKER half. For an arbitrary non-WORKER Assignable_Project_Role
     * and an arbitrary non-null {@code workerTypeId}, assign is rejected with 400
     * {@code error.project.member.worker.type.not.allowed} and persists nothing — so no non-WORKER
     * member can ever end up carrying a Worker_Type (the invariant's contrapositive).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 3")
    void assignNonWorkerWithWorkerTypeIsRejectedAndPersistsNothing(
            @ForAll("nonWorkerCode") String companyCode,
            @ForAll("workerTypeId") long workerTypeId) {

        Fixture f = new Fixture();
        try {
            UserEntity user = activeUserWithRole(companyCode);
            when(f.projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
            when(f.userDao.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> f.service.assign(USER_ID, PROJECT_ID, null, workerTypeId, null))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getMessageCode())
                                .isEqualTo("error.project.member.worker.type.not.allowed");
                    });

            // Nothing persisted: the worker.type.not.allowed check (step 8) fires before any save or
            // cache invalidation, so no non-WORKER member is ever created with a Worker_Type.
            verify(f.projectMemberDao, never()).save(any());
            verify(f.projectAccessCache, never()).invalidate(anyLong());
        } finally {
            f.close();
        }
    }

    /**
     * Property 3 — PATCH {@code changeWorkerType} path, WORKER half. Setting a worker type on an
     * existing WORKER member replaces its Worker_Type with the submitted (active) type and keeps the
     * role {@code WORKER}: the resulting member carries a Worker_Type only under a WORKER role.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 3")
    void changeWorkerTypeOnWorkerMemberAttachesTypeUnderWorkerRole(@ForAll("workerTypeId") long workerTypeId) {
        Fixture f = new Fixture();
        try {
            ProjectMemberEntity member = memberWithRole(WORKER_CODE); // no worker type yet
            WorkerTypeEntity type = activeWorkerType(workerTypeId);
            when(f.projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID))
                    .thenReturn(Optional.of(member));
            when(f.workerTypeDao.findById(workerTypeId)).thenReturn(Optional.of(type));
            when(f.projectMemberDao.save(any(ProjectMemberEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TeamMemberView view = f.service.updateAttributes(USER_ID, PROJECT_ID, null, workerTypeId, null);

            assertThat(view).isNotNull();
            // The member now carries a Worker_Type, and its role is WORKER (Property 3 invariant).
            assertThat(member.getWorkerType()).isSameAs(type);
            assertThat(member.getProjectRole().getCode()).isEqualTo(WORKER_CODE);
            verify(f.projectMemberDao).save(any(ProjectMemberEntity.class));
        } finally {
            f.close();
        }
    }

    /**
     * Property 3 — PATCH {@code changeWorkerType} path, non-WORKER half. Setting a worker type on an
     * existing non-WORKER member is rejected with 400
     * {@code error.project.member.worker.type.not.allowed} and the member keeps its (null)
     * Worker_Type: a non-WORKER member never gains a Worker_Type.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 3")
    void changeWorkerTypeOnNonWorkerMemberIsRejectedAndLeavesTypeNull(
            @ForAll("nonWorkerCode") String companyCode,
            @ForAll("workerTypeId") long workerTypeId) {

        Fixture f = new Fixture();
        try {
            ProjectMemberEntity member = memberWithRole(companyCode); // non-WORKER, no worker type
            when(f.projectMemberDao.findByUserIdAndProjectId(USER_ID, PROJECT_ID))
                    .thenReturn(Optional.of(member));

            assertThatThrownBy(() ->
                    f.service.updateAttributes(USER_ID, PROJECT_ID, null, workerTypeId, null))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getMessageCode())
                                .isEqualTo("error.project.member.worker.type.not.allowed");
                    });

            // Nothing changed / persisted: the not.allowed check fires before any save, so the
            // non-WORKER member keeps its null Worker_Type.
            assertThat(member.getWorkerType()).isNull();
            verify(f.projectMemberDao, never()).save(any());
        } finally {
            f.close();
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /** An arbitrary positive non-null worker type id. */
    @Provide
    Arbitrary<Long> workerTypeId() {
        return Arbitraries.longs().between(1L, 10_000L);
    }

    /** A non-WORKER Assignable_Project_Role code (so only the worker-type rule can trip). */
    @Provide
    Arbitrary<String> nonWorkerCode() {
        return Arbitraries.of(NON_WORKER_ASSIGNABLE_CODES);
    }

    // --- Fixtures / helpers ---------------------------------------------------------------------

    /** An active {@link UserEntity} with the given Company_Role code. */
    private static UserEntity activeUserWithRole(String roleCode) {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setRole(roleWithCode(roleCode));
        user.setActive(true);
        return user;
    }

    /** An existing {@link ProjectMemberEntity} of the pair with the given Project_Role and no worker type. */
    private static ProjectMemberEntity memberWithRole(String roleCode) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(1L);
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(roleWithCode(roleCode));
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        member.setWorkerType(null);
        return member;
    }

    /** A {@link RoleEntity} with the given code. */
    private static RoleEntity roleWithCode(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        return role;
    }

    /** An active {@link WorkerTypeEntity} with the given id (so resolveActiveWorkerType accepts it). */
    private static WorkerTypeEntity activeWorkerType(long id) {
        WorkerTypeEntity type = new WorkerTypeEntity();
        type.setId(id);
        type.setActive(true);
        return type;
    }

    /**
     * A self-contained set of Mockito mocks + a {@link ProjectMemberService} under test, with the
     * caller authenticated as ADMIN (so the step-4 project-access check is bypassed and the request
     * reaches the step-8 worker-type decision). jqwik re-runs the property method per {@code try}
     * without JUnit's {@code @BeforeEach}/{@code @AfterEach}, so each invocation builds and tears
     * down its own fixture; {@link #close()} clears the security context.
     */
    private static final class Fixture {
        final ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
        final UserDao userDao = mock(UserDao.class);
        final RoleDao roleDao = mock(RoleDao.class);
        final ProjectDao projectDao = mock(ProjectDao.class);
        final WorkerTypeDao workerTypeDao = mock(WorkerTypeDao.class);
        final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        final com.foremen.service.model.mapper.ProjectMemberServiceMapper projectMemberServiceMapper =
                mock(com.foremen.service.model.mapper.ProjectMemberServiceMapper.class);
        final com.foremen.service.audit.AuditLogDao auditLogDao =
                mock(com.foremen.service.audit.AuditLogDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final com.foremen.service.offer.NotificationService notificationService =
                mock(com.foremen.service.offer.NotificationService.class);

        final ProjectMemberService service = new ProjectMemberService(
                projectMemberDao, userDao, roleDao, projectDao, workerTypeDao,
                projectAccessCache, projectMemberServiceMapper, auditLogDao, entityManager,
                notificationService);

        Fixture() {
            authenticateAdmin();
        }

        void close() {
            SecurityContextHolder.clearContext();
        }

        private static void authenticateAdmin() {
            GrantedAuthority admin = new SimpleGrantedAuthority("ROLE_ADMIN");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", "n/a", List.of(admin)));
        }
    }
}
