package com.foremen.service;

import com.foremen.controller.model.TeamMemberView;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.team.TeamBlock;
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
 * Property-based coverage of FOR-05-09 design <b>Property 4 — {@code workerTypeMissing}
 * consistency</b> for the {@code Team_Member_View} produced by
 * {@link ProjectMemberService#listMemberViews(Long)} (via its private {@code toView} mapper).
 *
 * <p><b>Property 4.</b> <i>For any</i> Project_Member rendered as a Team_Member_View to an
 * Internal_Attribute_Viewer (admin-staff reader), the {@code workerTypeMissing} flag is {@code true}
 * if and only if the member's Project_Role code is {@code WORKER} (i.e. the WORKERS block) and the
 * member has no Worker_Type ({@code workerTypeId} is null); it is {@code false} for a WORKER member
 * that has a worker type and for every non-WORKER member. For a non-admin-staff reader (WORKER /
 * CLIENT) the flag is an Internal_Attribute and is always omitted (nulled, so the view's
 * {@code @JsonInclude(NON_NULL)} drops it from the payload).
 *
 * <p>The test drives the real list path: it stubs {@link ProjectMemberDao#findByProjectId(Long)} to
 * return an arbitrary team mixing WORKER (typed and untyped) and non-WORKER members, then asserts
 * the flag on each returned view. It authenticates an ADMIN caller for the present-case (admin-staff,
 * so the flag is populated) and a WORKER / CLIENT caller for the omitted-case (non-admin-staff, so
 * the flag is masked to null). Follows the Mockito-mocked-DAO style of
 * {@link ProjectMemberRoleMismatchPropertyTest}; jqwik re-runs the method per {@code try}, so each
 * invocation builds and tears down its own fixture.
 *
 * <p><b>Validates: Requirements 14.16</b>
 */
// Feature: FOR-05-09-team-selection, Property 4
@Tag("Feature: FOR-05-09-team-selection, Property 4")
class WorkerTypeMissingConsistencyPropertyTest {

    private static final long PROJECT_ID = 55L;

    /** Non-WORKER Assignable_Project_Role codes (ADMIN_STAFF + CLIENTS blocks). */
    private static final List<String> NON_WORKER_CODES =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "FINANCIER", "CLIENT");

    /**
     * Property 4 (present case). For an admin-staff reader, every returned view has
     * {@code workerTypeMissing} populated and equal to {@code (block == WORKERS and workerTypeId is
     * null)}: true for an untyped WORKER, false for a typed WORKER, false for every non-WORKER.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 4")
    void adminStaffReaderGetsConsistentWorkerTypeMissing(@ForAll("team") List<MemberSpec> specs) {
        Fixture f = new Fixture();
        f.authenticateAdmin(); // admin-staff -> Internal_Attributes visible
        List<ProjectMemberEntity> members = specs.stream().map(WorkerTypeMissingConsistencyPropertyTest::entity).toList();
        when(f.projectMemberDao.findByProjectId(eq(PROJECT_ID))).thenReturn(members);

        try {
            List<TeamMemberView> views = f.service.listMemberViews(PROJECT_ID);

            assertThat(views).hasSameSizeAs(specs);
            for (TeamMemberView view : views) {
                boolean isWorker = view.block() == TeamBlock.WORKERS;
                boolean noType = view.workerTypeId() == null;
                boolean expected = isWorker && noType;

                // The flag is present (never null) for an admin-staff reader and matches the rule.
                assertThat(view.workerTypeMissing())
                        .as("workerTypeMissing for member id=%s block=%s workerTypeId=%s",
                                view.id(), view.block(), view.workerTypeId())
                        .isNotNull()
                        .isEqualTo(expected);

                // Iff restated explicitly: true exactly for an untyped WORKER.
                if (isWorker && noType) {
                    assertThat(view.workerTypeMissing()).isTrue();
                } else {
                    assertThat(view.workerTypeMissing()).isFalse();
                }
            }
        } finally {
            f.close();
        }
    }

    /**
     * Property 4 (omitted case). For a non-admin-staff reader (WORKER or CLIENT), the internal
     * {@code workerTypeMissing} flag is always omitted (null) on every returned view, regardless of
     * the member's block or worker type.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 4")
    void nonAdminStaffReaderNeverSeesWorkerTypeMissing(
            @ForAll("team") List<MemberSpec> specs,
            @ForAll("nonAdminStaffRole") String readerRole) {
        Fixture f = new Fixture();
        f.authenticate(readerRole); // WORKER / CLIENT -> Internal_Attributes masked
        List<ProjectMemberEntity> members = specs.stream().map(WorkerTypeMissingConsistencyPropertyTest::entity).toList();
        when(f.projectMemberDao.findByProjectId(eq(PROJECT_ID))).thenReturn(members);

        try {
            List<TeamMemberView> views = f.service.listMemberViews(PROJECT_ID);

            assertThat(views).hasSameSizeAs(specs);
            for (TeamMemberView view : views) {
                assertThat(view.workerTypeMissing())
                        .as("workerTypeMissing must be omitted for a %s reader (member id=%s)",
                                readerRole, view.id())
                        .isNull();
            }
        } finally {
            f.close();
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /** An arbitrary team: 0..12 members mixing WORKER (typed/untyped) and non-WORKER rows. */
    @Provide
    Arbitrary<List<MemberSpec>> team() {
        return memberSpec().list().ofMinSize(0).ofMaxSize(12);
    }

    /**
     * A single member spec. {@code worker} picks the WORKERS block (role code WORKER) vs a non-WORKER
     * role; {@code hasType} decides whether a worker type is attached (only meaningful for a WORKER,
     * but attaching one to a non-WORKER would be an impossible state the mapper still handles — the
     * entity() builder only attaches a type to WORKER members to keep the fixture a valid team).
     */
    @Provide
    Arbitrary<MemberSpec> memberSpec() {
        Arbitrary<Boolean> worker = Arbitraries.of(true, false);
        Arbitrary<String> nonWorkerCode = Arbitraries.of(NON_WORKER_CODES);
        Arbitrary<Boolean> hasType = Arbitraries.of(true, false);
        Arbitrary<Long> id = Arbitraries.longs().between(1L, 10_000L);
        return Combinators.combine(worker, nonWorkerCode, hasType, id)
                .as(MemberSpec::new);
    }

    /** A non-admin-staff reader role: WORKER or CLIENT (the two Internal_Attribute non-viewers). */
    @Provide
    Arbitrary<String> nonAdminStaffRole() {
        return Arbitraries.of("WORKER", "CLIENT");
    }

    // --- Fixtures / helpers ---------------------------------------------------------------------

    /** A declarative member description resolved into a {@link ProjectMemberEntity} by {@link #entity}. */
    record MemberSpec(boolean worker, String nonWorkerCode, boolean hasType, long id) {
    }

    /** Builds a {@link ProjectMemberEntity} from a spec: WORKER (optionally typed) or a non-WORKER role. */
    private static ProjectMemberEntity entity(MemberSpec spec) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(spec.id());
        member.setProjectId(PROJECT_ID);

        String roleCode = spec.worker() ? "WORKER" : spec.nonWorkerCode();
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        role.setNameRU(roleCode);
        role.setNamePL(roleCode);
        member.setProjectRole(role);

        UserEntity user = new UserEntity();
        user.setId(spec.id());
        user.setName("user-" + spec.id());
        user.setActive(true);
        member.setUser(user);

        // Only a WORKER member may carry a worker type; non-WORKER members never get one, matching a
        // valid team (the Worker_Type-present => role=WORKER invariant of Property 3).
        if (spec.worker() && spec.hasType()) {
            WorkerTypeEntity type = new WorkerTypeEntity();
            type.setId(100L + spec.id());
            type.setCode("WT-" + spec.id());
            type.setNameRU("type-ru");
            type.setNamePL("type-pl");
            type.setActive(true);
            member.setWorkerType(type);
        }
        return member;
    }

    /**
     * Mockito mocks + a {@link ProjectMemberService} under test. The security context is set per
     * property method (admin-staff vs non-admin-staff) and cleared in {@link #close()}.
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

        void authenticateAdmin() {
            authenticate("ADMIN");
        }

        void authenticate(String roleCode) {
            GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + roleCode);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", "n/a", List.of(authority)));
        }

        void close() {
            SecurityContextHolder.clearContext();
        }
    }
}
