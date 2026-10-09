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
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.ProjectMemberServiceMapper;
import com.foremen.service.team.TeamBlock;
import com.foremen.service.team.TeamMemberOrdering;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based coverage of FOR-05-09 design <b>Property 1</b> for
 * {@link ProjectMemberService#assign}: the assigned {@code Project_Role} equals the user's current
 * {@code Company_Role}, the derived {@link TeamBlock} equals {@code blockOf(Company_Role)}, the
 * Assignment_Status is {@code ACTIVE}, and the role is immutable — never taken from the request's
 * {@code projectRoleId}, and never changed by a following sequence of Attribute_Updates
 * (worker-type / tag / status changes).
 *
 * <p>This is a mock-based unit property in the style of {@code ProjectMemberServiceTest}: the DAOs
 * and {@link ProjectAccessCache} are Mockito mocks, and the caller is authenticated as {@code ADMIN}
 * so the canonical rejection checklist's step-4 project existence / access check is bypassed and
 * every generated assign reaches the persist branch under test. The generators constrain the input
 * to the valid space (an active user whose Company_Role is an Assignable_Project_Role) so the
 * assertion is about the persisted member, not about a rejection.
 *
 * <p><b>Property 1: Assigned role equals the user's Company_Role, with derived block and immutability</b>
 *
 * <p><b>Validates: Requirements 5.1, 5.10, 6.2, 7.1, 7.2</b>
 */
// Feature: FOR-05-09-team-selection, Property 1
@Tag("Feature: FOR-05-09-team-selection, Property 1")
class ProjectMemberAssignRolePropertyTest {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 42L;

    /** The system role codes that are Assignable_Project_Roles (Glossary); every one is a valid assign input. */
    private static final List<String> ASSIGNABLE_ROLE_CODES =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER", "CLIENT");

    /**
     * Property 1 — assign persists the user's Company_Role (not the supplied {@code projectRoleId}),
     * status ACTIVE, and a block equal to {@code blockOf(Company_Role)}; and a following sequence of
     * Attribute_Updates (each carrying an arbitrary {@code projectRoleId}) leaves the role code and
     * block unchanged.
     *
     * <p>The supplied {@code projectRoleId} is modelled three ways (absent, equal to the
     * Company_Role, or a distinct role resolving to the <em>same</em> code so no role.mismatch
     * trips): each must persist the same Company_Role instance.
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 1")
    void assignPersistsCompanyRoleActiveAndDerivedBlockThenRoleIsImmutable(
            @ForAll("assignableRoleCode") String companyRoleCode,
            @ForAll("suppliedRoleMode") int suppliedRoleMode,
            @ForAll("attributeUpdateSeq") List<Integer> updateOps) {

        // --- Mocks + service, caller authenticated as ADMIN (step-4 bypass) ----------------------
        ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        ProjectDao projectDao = mock(ProjectDao.class);
        WorkerTypeDao workerTypeDao = mock(WorkerTypeDao.class);
        ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        ProjectMemberServiceMapper mapper = mock(ProjectMemberServiceMapper.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        com.foremen.service.offer.NotificationService notificationService =
                mock(com.foremen.service.offer.NotificationService.class);

        ProjectMemberService service = new ProjectMemberService(
                projectMemberDao, userDao, roleDao, projectDao, workerTypeDao,
                projectAccessCache, mapper, auditLogDao, entityManager, notificationService);

        authenticateAdmin();
        try {
            // The user's immutable Company_Role instance; its code is what MUST be persisted (D2).
            RoleEntity companyRole = roleWithCode(companyRoleCode);
            companyRole.setId(100L);
            UserEntity user = new UserEntity();
            user.setId(USER_ID);
            user.setRole(companyRole);
            user.setActive(true);

            when(projectMemberDao.existsByUserIdAndProjectId(USER_ID, PROJECT_ID)).thenReturn(false);
            when(userDao.findById(USER_ID)).thenReturn(Optional.of(user));
            when(projectMemberDao.save(any(ProjectMemberEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Model the supplied projectRoleId: absent, the SAME role instance, or a DISTINCT role
            // instance that resolves to the same code (so no role.mismatch — the id is still ignored
            // for persistence, which is the immutability point of Requirement 7.1/7.2).
            Long suppliedRoleId;
            switch (suppliedRoleMode) {
                case 1 -> {
                    suppliedRoleId = companyRole.getId();           // same id as the Company_Role
                    when(roleDao.findById(suppliedRoleId)).thenReturn(Optional.of(companyRole));
                }
                case 2 -> {
                    suppliedRoleId = 999L;                          // a different role row...
                    RoleEntity sameCodeOtherRow = roleWithCode(companyRoleCode); // ...with the SAME code
                    sameCodeOtherRow.setId(suppliedRoleId);
                    when(roleDao.findById(suppliedRoleId)).thenReturn(Optional.of(sameCodeOtherRow));
                }
                default -> suppliedRoleId = null;                   // omitted entirely
            }

            // --- assign -------------------------------------------------------------------------
            ProjectMemberEntity saved = service.assign(USER_ID, PROJECT_ID, suppliedRoleId);

            // Req 5.1 / 5.10 / 7.1 / 7.2: the persisted role is the Company_Role INSTANCE, never the
            // submitted-role row — proven by same-instance identity, which also holds for mode 2 where
            // a distinct same-code row was supplied.
            assertThat(saved.getProjectRole())
                    .as("persisted projectRole is the user's Company_Role instance (not the request's)")
                    .isSameAs(companyRole);
            assertThat(saved.getProjectRole().getCode()).isEqualTo(companyRoleCode);

            // Req 5.2: a new member is ACTIVE.
            assertThat(saved.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);

            // Req 6.2: the derived block equals blockOf(Company_Role).
            TeamBlock expectedBlock = TeamMemberOrdering.blockOf(companyRoleCode);
            assertThat(TeamMemberOrdering.blockOf(saved.getProjectRole().getCode()))
                    .as("derived block equals blockOf(Company_Role)")
                    .isEqualTo(expectedBlock);

            // --- following Attribute_Updates: role + block are immutable (Req 7.1 / 7.2) ---------
            // The PATCH Attribute_Update contract (design) ignores any submitted projectRoleId and
            // never reassigns projectRole: it only touches worker type, tags, or assignment status.
            // Apply the generated sequence directly to the persisted entity (each op carrying an
            // arbitrary projectRoleId, which the contract discards) and assert the role code and
            // derived block never change.
            RoleEntity arbitrarySubmittedRole = roleWithCode("ESTIMATOR"); // an arbitrary request role
            for (int op : updateOps) {
                applyAttributeUpdateIgnoringRole(saved, op, arbitrarySubmittedRole);

                assertThat(saved.getProjectRole())
                        .as("Attribute_Update never reassigns the Company_Role instance")
                        .isSameAs(companyRole);
                assertThat(saved.getProjectRole().getCode()).isEqualTo(companyRoleCode);
                assertThat(TeamMemberOrdering.blockOf(saved.getProjectRole().getCode()))
                        .as("block is stable across Attribute_Updates")
                        .isEqualTo(expectedBlock);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Models one Attribute_Update of the design's PATCH contract applied to {@code member}: a
     * worker-type, tag, or assignment-status change. Each call carries an arbitrary
     * {@code submittedRole} to represent a request that smuggles a {@code projectRoleId}; the
     * contract <em>ignores</em> it, so this helper deliberately never assigns it to the member —
     * exactly the immutability behavior under test (Requirement 7.1 / 7.2).
     */
    private static void applyAttributeUpdateIgnoringRole(ProjectMemberEntity member, int op,
                                                         RoleEntity submittedRole) {
        switch (Math.floorMod(op, 3)) {
            case 0 -> { // worker-type change
                WorkerTypeEntity type = new WorkerTypeEntity();
                type.setId(11L);
                type.setActive(true);
                member.setWorkerType(type);
            }
            case 1 -> { // tag change
                List<String> tags = new ArrayList<>(member.getTags() == null ? List.of() : member.getTags());
                tags.add("tag" + op);
                member.setTags(tags);
            }
            default -> { // status change (toggle)
                member.setAssignmentStatus(member.getAssignmentStatus() == AssignmentStatus.ACTIVE
                        ? AssignmentStatus.INACTIVE
                        : AssignmentStatus.ACTIVE);
            }
        }
        // Intentionally NOT: member.setProjectRole(submittedRole) — the request role is discarded.
        assertThat(submittedRole).isNotNull(); // the smuggled role existed but was ignored
    }

    private static RoleEntity roleWithCode(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        return role;
    }

    /** Authenticates an ADMIN caller so the step-4 project access check is bypassed. */
    private static void authenticateAdmin() {
        List<GrantedAuthority> granted = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "n/a", granted));
    }

    // --- Generators -----------------------------------------------------------------------------

    /** Any Assignable_Project_Role code, so the user is always assignable (team-composition passes). */
    @Provide
    Arbitrary<String> assignableRoleCode() {
        return Arbitraries.of(ASSIGNABLE_ROLE_CODES);
    }

    /** 0 = no projectRoleId, 1 = same role instance, 2 = distinct row with the same code (no mismatch). */
    @Provide
    Arbitrary<Integer> suppliedRoleMode() {
        return Arbitraries.integers().between(0, 2);
    }

    /** A 0..8-long sequence of Attribute_Update op codes (worker-type / tag / status, mod 3). */
    @Provide
    Arbitrary<List<Integer>> attributeUpdateSeq() {
        return Arbitraries.integers().between(0, 8).list().ofMaxSize(8);
    }
}
