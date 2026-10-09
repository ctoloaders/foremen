package com.foremen.service;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based coverage of FOR-05-09 design <b>Property 8 — the last ACTIVE MANAGER and last
 * ACTIVE CLIENT always survive</b>, exercising the reusable guard
 * {@link ProjectMemberService#assertNotLastActiveManagerOrClient(ProjectMemberEntity)} (task 8.1)
 * that both the {@code remove} flow (step 9 of the canonical checklist) and the deactivate flow
 * ({@code changeAssignmentStatus}) funnel through.
 *
 * <p><b>Property 8.</b> For an arbitrary project team and an arbitrary remove/deactivate target,
 * the operation — which subtracts exactly one ACTIVE member of the target's Project_Role — must
 * never drop the project's count of ACTIVE MANAGER (or ACTIVE CLIENT) members below one when it was
 * at least one immediately before the operation. Equivalently, the guard:
 * <ul>
 *   <li><b>rejects</b> with HTTP 409 {@code error.project.member.last.manager} /
 *       {@code error.project.member.last.client} exactly when the target is <em>ACTIVE</em>, its
 *       Project_Role is MANAGER or CLIENT, and that role currently has only this one ACTIVE member
 *       (modelled ACTIVE count {@code <= 1}); and</li>
 *   <li><b>accepts</b> (returns normally) in every other case — a non-last ACTIVE MANAGER/CLIENT
 *       (ACTIVE count {@code >= 2}), a target of any other Project_Role, or an already-INACTIVE
 *       member — because none of those drops a guarded role's ACTIVE count to zero.</li>
 * </ul>
 *
 * <p>The invariant is driven by stubbing
 * {@link ProjectMemberDao#countByProjectIdAndProjectRoleCodeAndAssignmentStatus} to model the number
 * of ACTIVE members of the target's role in the (arbitrary) team.
 *
 * <p>The test follows the mock-based style of {@link ProjectMemberServiceTest} /
 * {@link ProjectMemberRoleMismatchPropertyTest}: Mockito-mocked DAOs + {@link ProjectAccessCache},
 * with the caller authenticated as ADMIN (the guard reads the project's rows directly and applies to
 * ADMIN and non-ADMIN alike, so authentication only matches the sibling tests' setup).
 *
 * <p><b>Validates: Requirements 9.5, 8.1, 27.2, 27.7</b>
 */
// Feature: FOR-05-09-team-selection, Property 8
@Tag("Feature: FOR-05-09-team-selection, Property 8")
class LastActiveGuardPropertyTest {

    private static final long PROJECT_ID = 42L;

    /** The two guarded Project_Role codes (Requirement 9 criteria 1–2). */
    private static final List<String> GUARDED_CODES = List.of("MANAGER", "CLIENT");

    /**
     * Property 8 — the universal rule across an arbitrary team and target. For every combination of
     * (role code, Assignment_Status of the target, modelled ACTIVE count of that role), the guard
     * rejects with the role-specific 409 iff removing/deactivating the target would drop the last
     * ACTIVE MANAGER/CLIENT to zero, and returns normally otherwise.
     */
    @Property(tries = 400)
    @Tag("Feature: FOR-05-09-team-selection, Property 8")
    void lastActiveManagerOrClientAlwaysSurvives(
            @ForAll("roleCode") String roleCode,
            @ForAll("targetStatus") AssignmentStatus targetStatus,
            @ForAll("activeCount") long activeCountOfRole) {

        Fixture f = new Fixture();
        try {
            ProjectMemberEntity target = member(roleCode, targetStatus);

            // Model the team: the role currently has `activeCountOfRole` ACTIVE members (the target
            // included when it is itself ACTIVE). The guard only ever counts ACTIVE members.
            when(f.projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                    eq(PROJECT_ID), eq(roleCode), eq(AssignmentStatus.ACTIVE)))
                    .thenReturn(activeCountOfRole);

            boolean guardedRole = GUARDED_CODES.contains(roleCode);
            boolean targetActive = targetStatus == AssignmentStatus.ACTIVE;
            // The op removes one ACTIVE member of the role only when the target itself is ACTIVE;
            // it is the last one exactly when the modelled ACTIVE count is <= 1.
            boolean wouldDropLastActive = guardedRole && targetActive && activeCountOfRole <= 1;

            if (wouldDropLastActive) {
                String expectedCode = "MANAGER".equals(roleCode)
                        ? "error.project.member.last.manager"
                        : "error.project.member.last.client";
                assertThatThrownBy(() -> f.service.assertNotLastActiveManagerOrClient(target))
                        .isInstanceOf(ForemenApiException.class)
                        .satisfies(ex -> {
                            ForemenApiException api = (ForemenApiException) ex;
                            assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(api.getMessageCode()).isEqualTo(expectedCode);
                        });
            } else {
                // A non-last ACTIVE MANAGER/CLIENT (count >= 2), any other role, or an already
                // INACTIVE member: the op leaves at least one ACTIVE member of every guarded role,
                // so the guard permits it.
                assertThatCode(() -> f.service.assertNotLastActiveManagerOrClient(target))
                        .doesNotThrowAnyException();
            }
        } finally {
            f.close();
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /** Any Assignable_Project_Role code — guarded (MANAGER/CLIENT) and unguarded alike. */
    @Provide
    Arbitrary<String> roleCode() {
        return Arbitraries.of(
                "MANAGER", "CLIENT", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER");
    }

    /** The target's Assignment_Status: ACTIVE subtracts an ACTIVE member, INACTIVE does not. */
    @Provide
    Arbitrary<AssignmentStatus> targetStatus() {
        return Arbitraries.of(AssignmentStatus.ACTIVE, AssignmentStatus.INACTIVE);
    }

    /**
     * The modelled count of ACTIVE members of the target's role, spanning the boundary: 0 and 1 are
     * "last ACTIVE" cases, 2+ are "not last". 0 only arises for an already-INACTIVE target (an ACTIVE
     * target is itself counted), and the guard treats both 0 and 1 as "would drop to zero".
     */
    @Provide
    Arbitrary<Long> activeCount() {
        return Arbitraries.longs().between(0L, 8L);
    }

    // --- Fixtures / helpers ---------------------------------------------------------------------

    /** A Project_Member of the given Project_Role code and Assignment_Status, on {@link #PROJECT_ID}. */
    private static ProjectMemberEntity member(String roleCode, AssignmentStatus status) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId(PROJECT_ID);
        member.setProjectRole(role);
        member.setAssignmentStatus(status);
        return member;
    }

    /**
     * A self-contained set of Mockito mocks + a {@link ProjectMemberService} under test, with the
     * caller authenticated as ADMIN. jqwik re-runs the property method per {@code try} without
     * JUnit's {@code @BeforeEach}/{@code @AfterEach}, so each invocation builds and tears down its
     * own fixture; {@link #close()} clears the security context.
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
            GrantedAuthority admin = new SimpleGrantedAuthority("ROLE_ADMIN");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", "n/a", List.of(admin)));
        }

        void close() {
            SecurityContextHolder.clearContext();
        }
    }
}
