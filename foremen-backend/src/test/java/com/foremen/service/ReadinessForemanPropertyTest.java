package com.foremen.service;

import com.foremen.controller.model.TeamReadiness;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.service.team.ReadinessState;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based coverage of FOR-05-09 design <b>Property 9 — readiness is DONE exactly when an
 * ACTIVE FOREMAN exists</b> for {@link ProjectMemberService#readiness(Long)}.
 *
 * <p><b>Property 9.</b> <i>For any</i> team reached by any sequence of assign, deactivate,
 * reactivate, and remove operations, the Team_Readiness_Gate state equals {@code DONE} if and only
 * if the returned count of ACTIVE {@code FOREMAN} Project_Members is at least one (and {@code BLOCKED}
 * otherwise), and each returned per-role count equals the number of ACTIVE Project_Members of that
 * Assignable_Project_Role (INACTIVE members excluded).
 *
 * <p>Rather than drive a real operation sequence, this test models the net effect such a sequence
 * has on {@link ProjectMemberService#readiness(Long)}: the only state it observes is, per role, the
 * count of <strong>ACTIVE</strong> members, read through
 * {@link ProjectMemberDao#countByProjectIdAndProjectRoleCodeAndAssignmentStatus} with
 * {@link AssignmentStatus#ACTIVE}. An arbitrary per-role ACTIVE-count vector therefore stands in for
 * the terminal state of any such sequence (any mix of assigns/deactivates/reactivates/removes
 * produces some non-negative ACTIVE count per role). The service never reads INACTIVE members for
 * readiness, so modelling only the ACTIVE-count projection is faithful: an INACTIVE FOREMAN simply
 * does not appear in the ACTIVE FOREMAN count and so cannot drive the gate to DONE (D14,
 * Requirement 27 criterion 8).
 *
 * <p>The test follows the mock-based, ADMIN-authenticated style of
 * {@link ProjectMemberRoleMismatchPropertyTest}: Mockito-mocked DAOs + {@link ProjectAccessCache},
 * with the caller authenticated as ADMIN so the step-4 project-access check is bypassed and every
 * generated request reaches the counting logic. jqwik re-runs the property per {@code try} without
 * JUnit lifecycle hooks, so each invocation builds and tears down its own fixture.
 *
 * <p><b>Validates: Requirements 20.1, 20.2, 20.3, 27.8</b>
 */
// Feature: FOR-05-09-team-selection, Property 9
@Tag("Feature: FOR-05-09-team-selection, Property 9")
class ReadinessForemanPropertyTest {

    private static final long PROJECT_ID = 42L;

    private static final String MANAGER = "MANAGER";
    private static final String FOREMAN = "FOREMAN";
    private static final String ESTIMATOR = "ESTIMATOR";
    private static final String WORKER = "WORKER";
    private static final String FINANCIER = "FINANCIER";
    private static final String CLIENT = "CLIENT";

    /**
     * Property 9. For an arbitrary per-role ACTIVE-count vector, the readiness gate reports
     * {@code key == "team"}, counts that mirror the per-role ACTIVE counts exactly, and
     * {@code state == DONE} iff the ACTIVE FOREMAN count is at least one (BLOCKED otherwise).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 9")
    void readinessIsDoneIffActiveForemanExistsAndCountsMirrorActiveMembers(
            @ForAll("activeCounts") int[] counts) {

        int manager = counts[0];
        int foreman = counts[1];
        int estimator = counts[2];
        int worker = counts[3];
        int financier = counts[4];
        int client = counts[5];

        Fixture f = new Fixture();
        try {
            stubActiveCount(f, MANAGER, manager);
            stubActiveCount(f, FOREMAN, foreman);
            stubActiveCount(f, ESTIMATOR, estimator);
            stubActiveCount(f, WORKER, worker);
            stubActiveCount(f, FINANCIER, financier);
            stubActiveCount(f, CLIENT, client);

            TeamReadiness readiness = f.service.readiness(PROJECT_ID);

            // key is the constant gate key "team" (Requirement 20.2).
            assertThat(readiness.key()).isEqualTo("team").isEqualTo(TeamReadiness.KEY);

            // counts mirror the per-role ACTIVE counts exactly (Requirement 20.2/20.3).
            TeamReadiness.Counts c = readiness.counts();
            assertThat(c.manager()).isEqualTo(manager);
            assertThat(c.foreman()).isEqualTo(foreman);
            assertThat(c.estimator()).isEqualTo(estimator);
            assertThat(c.worker()).isEqualTo(worker);
            assertThat(c.financier()).isEqualTo(financier);
            assertThat(c.client()).isEqualTo(client);

            // state == DONE iff ACTIVE FOREMAN count >= 1 (Requirement 20.1/20.2, Property 9).
            ReadinessState expected = foreman >= 1 ? ReadinessState.DONE : ReadinessState.BLOCKED;
            assertThat(readiness.state()).isEqualTo(expected);
            assertThat(readiness.state() == ReadinessState.DONE).isEqualTo(foreman >= 1);
        } finally {
            f.close();
        }
    }

    /**
     * Property 9 (ACTIVE-only driver, Requirement 27 criterion 8). Only the ACTIVE FOREMAN count can
     * drive the gate to DONE: an arbitrary number of INACTIVE FOREMAN members (modelled by the
     * service never querying them — the ACTIVE count is independently generated and may be zero even
     * while other roles are populated) leaves the gate BLOCKED when the ACTIVE FOREMAN count is zero.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 9")
    void zeroActiveForemanBlocksRegardlessOfOtherRoles(
            @ForAll("nonForemanCounts") int[] others) {

        Fixture f = new Fixture();
        try {
            // No ACTIVE FOREMAN at all; every other role arbitrarily populated.
            stubActiveCount(f, FOREMAN, 0);
            stubActiveCount(f, MANAGER, others[0]);
            stubActiveCount(f, ESTIMATOR, others[1]);
            stubActiveCount(f, WORKER, others[2]);
            stubActiveCount(f, FINANCIER, others[3]);
            stubActiveCount(f, CLIENT, others[4]);

            TeamReadiness readiness = f.service.readiness(PROJECT_ID);

            assertThat(readiness.counts().foreman()).isZero();
            assertThat(readiness.state()).isEqualTo(ReadinessState.BLOCKED);
        } finally {
            f.close();
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /** Six independent non-negative per-role ACTIVE counts [MANAGER, FOREMAN, ESTIMATOR, WORKER, FINANCIER, CLIENT]. */
    @Provide
    Arbitrary<int[]> activeCounts() {
        Arbitrary<Integer> count = Arbitraries.integers().between(0, 20);
        return Combinators.combine(count, count, count, count, count, count)
                .as((m, f, e, w, fi, c) -> new int[]{m, f, e, w, fi, c});
    }

    /** Five non-negative counts for the non-FOREMAN roles [MANAGER, ESTIMATOR, WORKER, FINANCIER, CLIENT]. */
    @Provide
    Arbitrary<int[]> nonForemanCounts() {
        Arbitrary<Integer> count = Arbitraries.integers().between(0, 20);
        return Combinators.combine(count, count, count, count, count)
                .as((m, e, w, fi, c) -> new int[]{m, e, w, fi, c});
    }

    // --- Fixtures / helpers ---------------------------------------------------------------------

    /** Stubs the ACTIVE member count of {@code roleCode} on {@link #PROJECT_ID} to return {@code value}. */
    private static void stubActiveCount(Fixture f, String roleCode, int value) {
        when(f.projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                eq(PROJECT_ID), eq(roleCode), eq(AssignmentStatus.ACTIVE)))
                .thenReturn((long) value);
    }

    /**
     * A self-contained set of Mockito mocks + a {@link ProjectMemberService} under test, with the
     * caller authenticated as ADMIN so the step-4 project-access check is bypassed and the readiness
     * request reaches the per-role counting logic. jqwik re-runs the property method per {@code try}
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
