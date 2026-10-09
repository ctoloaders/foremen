package com.foremen.service;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;
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
 * Property-based coverage of FOR-05-09 design <b>Property 2 — a mismatching supplied role is
 * rejected</b> for {@link ProjectMemberService#assign(Long, Long, Long)} and its
 * {@code mandatoryFieldsCheck} (role.mismatch) step.
 *
 * <p><b>Property 2.</b> <i>For any</i> assign request whose <em>non-null</em> {@code projectRoleId}
 * resolves to a role code different from the assigned user's current Company_Role code, the Team_API
 * rejects it with HTTP 400 {@code error.project.member.role.mismatch} and persists no
 * Project_Member. Conversely, when the supplied {@code projectRoleId} resolves to the <em>same</em>
 * code as the user's Company_Role (or is {@code null}), the request is <em>not</em> rejected for
 * mismatch — it reaches persistence and saves exactly one member.
 *
 * <p>These are the two complementary halves the design's prework identified as the universal rule
 * behind Requirement 5 criterion 11 (D2): the supplied role is only ever a consistency assertion
 * against the Company_Role; the persisted Project_Role is always the Company_Role itself.
 *
 * <p>The test follows the mock-based style of {@link ProjectMemberServiceTest}: Mockito-mocked DAOs
 * + {@link ProjectAccessCache}, with the caller authenticated as ADMIN so the step-4 project-access
 * check is bypassed and every generated request reaches the step-3 role.mismatch decision. The
 * generated Company_Role is always drawn from the Assignable_Project_Role set and the user is active,
 * so neither half is sidetracked by the team-composition step; on the matching half this lets the
 * flow run through to a real {@code save}.
 *
 * <p><b>Validates: Requirements 5.11, 7.2</b>
 */
// Feature: FOR-05-09-team-selection, Property 2
@Tag("Feature: FOR-05-09-team-selection, Property 2")
class ProjectMemberRoleMismatchPropertyTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 42L;
    private static final long SUPPLIED_ROLE_ID = 3L;

    /** The six Assignable_Project_Role codes (TeamComposition); any of these passes composition. */
    private static final List<String> ASSIGNABLE_CODES =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER", "CLIENT");

    /**
     * Property 2 (rejection half). A non-null {@code projectRoleId} resolving to a code
     * {@code D != C} (the user's Company_Role) is rejected with 400
     * {@code error.project.member.role.mismatch} and persists nothing: no duplicate lookup, no save,
     * no cache invalidation (the mismatch is a step-3 check, before any of those).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 2")
    void mismatchingSuppliedRoleIsRejectedAndPersistsNothing(
            @ForAll("mismatchedRolePair") String[] pair) {

        String companyCode = pair[0];
        String suppliedCode = pair[1]; // guaranteed != companyCode

        Fixture f = new Fixture();
        UserEntity user = activeUserWithRole(companyCode);
        when(f.userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        // The supplied projectRoleId resolves to a DIFFERENT code than the Company_Role.
        when(f.roleDao.findById(SUPPLIED_ROLE_ID)).thenReturn(Optional.of(roleWithCode(suppliedCode)));

        try {
            assertThatThrownBy(() -> f.service.assign(USER_ID, PROJECT_ID, SUPPLIED_ROLE_ID))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getMessageCode()).isEqualTo("error.project.member.role.mismatch");
                    });

            // Nothing persisted and no side effect: role.mismatch (step 3) fires before the
            // duplicate check (step 6) and before any save / cache invalidation.
            verify(f.projectMemberDao, never()).existsByUserIdAndProjectId(anyLong(), anyLong());
            verify(f.projectMemberDao, never()).save(any());
            verify(f.projectAccessCache, never()).invalidate(anyLong());
        } finally {
            f.close();
        }
    }

    /**
     * Property 2 (acceptance half). A supplied {@code projectRoleId} resolving to the <em>same</em>
     * code as the Company_Role, or a {@code null} {@code projectRoleId}, is <em>not</em> rejected for
     * mismatch: the flow reaches persistence and saves exactly one member whose persisted role is the
     * user's Company_Role (D2), with Assignment_Status ACTIVE, and invalidates the cache.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 2")
    void matchingOrNullSuppliedRoleIsNotRejectedForMismatch(
            @ForAll("assignableCode") String companyCode,
            @ForAll boolean supplyMatchingRoleId) {

        Fixture f = new Fixture();
        UserEntity user = activeUserWithRole(companyCode);
        when(f.projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
        when(f.userDao.findById(USER_ID)).thenReturn(Optional.of(user));
        when(f.projectMemberDao.save(any(ProjectMemberEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Long suppliedRoleId;
        if (supplyMatchingRoleId) {
            // A non-null projectRoleId resolving to the SAME code as the Company_Role: no mismatch.
            suppliedRoleId = SUPPLIED_ROLE_ID;
            when(f.roleDao.findById(SUPPLIED_ROLE_ID)).thenReturn(Optional.of(roleWithCode(companyCode)));
        } else {
            // A null projectRoleId needs no mismatch comparison at all.
            suppliedRoleId = null;
        }

        try {
            ProjectMemberEntity saved = f.service.assign(USER_ID, PROJECT_ID, suppliedRoleId);

            // Not rejected for mismatch: exactly one member persisted, role = Company_Role (D2).
            assertThat(saved).isNotNull();
            assertThat(saved.getProjectRole()).isSameAs(user.getRole());
            assertThat(saved.getProjectRole().getCode()).isEqualTo(companyCode);
            assertThat(saved.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(f.projectMemberDao).save(any(ProjectMemberEntity.class));
            verify(f.projectAccessCache).invalidate(USER_ID);
        } finally {
            f.close();
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /** An Assignable_Project_Role code (so the team-composition step never pre-empts the mismatch check). */
    @Provide
    Arbitrary<String> assignableCode() {
        return Arbitraries.of(ASSIGNABLE_CODES);
    }

    /**
     * A pair {@code [companyCode, suppliedCode]} of Assignable_Project_Role codes that are
     * guaranteed <em>different</em>, so the supplied role always mismatches the Company_Role. Drawing
     * both from the assignable set keeps the user's Company_Role itself assignable and active, so the
     * only tripped check is the step-3 role.mismatch under test (never role.not.assignable).
     */
    @Provide
    Arbitrary<String[]> mismatchedRolePair() {
        Arbitrary<String> company = Arbitraries.of(ASSIGNABLE_CODES);
        Arbitrary<String> supplied = Arbitraries.of(ASSIGNABLE_CODES);
        return Combinators.combine(company, supplied)
                .as((c, s) -> new String[]{c, s})
                .filter(p -> !p[0].equals(p[1]));
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

    /** A {@link RoleEntity} with the given code. */
    private static RoleEntity roleWithCode(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        return role;
    }

    /**
     * A self-contained set of Mockito mocks + a {@link ProjectMemberService} under test, with the
     * caller authenticated as ADMIN (so the step-4 project-access check is bypassed and the request
     * reaches the step-3 role.mismatch decision). jqwik re-runs the property method per {@code try}
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
