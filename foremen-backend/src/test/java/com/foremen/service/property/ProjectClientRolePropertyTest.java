package com.foremen.service.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.foremen.controller.model.ClientBlock;
import com.foremen.controller.model.ClientRegistrationRequest;
import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.NewClientInput;
import com.foremen.controller.model.ProjectMemberInput;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.ClientRegistrationService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectMemberService;
import com.foremen.service.ProjectService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.mapper.ProjectServiceMapper;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for FOR-04-13 Property 3: the client is always assigned under the
 * <strong>server-resolved</strong> {@code CLIENT} project role, regardless of any values carried by
 * the request.
 *
 * <p>The SUT is {@link ProjectService#createProject(CreateProjectRequest)}. The request carries no
 * client role identifier ({@link ClientBlock} exposes only {@code existingClientUserId} /
 * {@code newClient}), so the only way the client could be assigned a role is the one the server
 * resolves via {@link RoleDao#findByCode(String) roleDao.findByCode("CLIENT")}. These tests stub
 * that lookup to return a {@link RoleEntity} with a known id and assert that:
 *
 * <ul>
 *   <li><b>existing-client branch</b> — {@link ProjectMemberService#assign} is invoked for the
 *       existing client user id with exactly the resolved CLIENT role id (never a team member's
 *       {@code projectRoleId} nor any other request value), even when generated team members carry
 *       arbitrary role ids that may collide with or differ from the CLIENT role id;</li>
 *   <li><b>new-client branch</b> — the client is routed through
 *       {@link ClientRegistrationService#register} (which fixes {@code CLIENT} server-side), and
 *       {@code ProjectService} never issues a direct client {@code assign} with a request-borne role
 *       id. The client role for the new-client path is fixed inside the reused FOR-03-05 flow, not
 *       chosen from the request.</li>
 * </ul>
 *
 * <p>The service is constructed with Mockito mocks (mirroring
 * {@link AdminRoleProhibitionPropertyTest}); {@code projectDao.save} echoes the entity with a fixed
 * id and {@code projectMemberService.assign} returns a stub membership so the orchestrator runs to
 * completion. Team-member role ids are generated independently of the CLIENT role id to prove the
 * client assignment is unaffected by any request-provided role.
 *
 * <p>Feature: FOR-04-13-project, Property 3
 *
 * Validates: Requirements 2.7, 2.8
 */
@Tag("Feature: FOR-04-13-project, Property 3: client assigned under server-resolved CLIENT role")
class ProjectClientRolePropertyTest {

    private static final String CLIENT = "CLIENT";
    private static final long SAVED_PROJECT_ID = 777L;

    // --- Generators ---

    /** The server-resolved CLIENT role id — any long, including values that may collide with member roles. */
    @Provide
    Arbitrary<Long> clientRoleIds() {
        return Arbitraries.longs().between(1L, 10_000L);
    }

    /** An existing CLIENT user id supplied by the request's client block. */
    @Provide
    Arbitrary<Long> existingClientUserIds() {
        return Arbitraries.longs().between(1L, 10_000L);
    }

    /** Team members with arbitrary (possibly colliding) role ids, independent of the CLIENT role. */
    @Provide
    Arbitrary<List<ProjectMemberInput>> memberLists() {
        Arbitrary<ProjectMemberInput> member = Combinators.combine(
                        Arbitraries.longs().between(1L, 10_000L),
                        Arbitraries.longs().between(1L, 10_000L))
                .as(ProjectMemberInput::new);
        return member.list().ofMaxSize(5);
    }

    // --- Fixtures ---

    private record Fixture(ProjectService service,
                           ProjectMemberService projectMemberService,
                           ClientRegistrationService clientRegistrationService,
                           RoleDao roleDao) {}

    private static Fixture newFixture(long clientRoleId) {
        ProjectDao projectDao = mock(ProjectDao.class);
        ProjectServiceMapper mapper = mock(ProjectServiceMapper.class);
        ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProjectMemberService projectMemberService = mock(ProjectMemberService.class);
        ClientRegistrationService clientRegistrationService = mock(ClientRegistrationService.class);
        RoleDao roleDao = mock(RoleDao.class);
        GooglePlacesService googlePlacesService = mock(GooglePlacesService.class);

        // projectDao.save echoes the entity back with a fixed generated id.
        when(projectDao.save(any(ProjectEntity.class))).thenAnswer(inv -> {
            ProjectEntity e = inv.getArgument(0);
            e.setId(SAVED_PROJECT_ID);
            return e;
        });

        // The server resolves the CLIENT role via findByCode("CLIENT") -> role with the known id.
        when(roleDao.findByCode(CLIENT)).thenReturn(Optional.of(role(clientRoleId, CLIENT)));

        // Every membership assignment returns a stub row so the orchestrator runs to completion.
        when(projectMemberService.assign(anyLong(), anyLong(), anyLong()))
                .thenAnswer(inv -> stubMember(inv.getArgument(0), inv.getArgument(2)));

        ProjectService service = new ProjectService(
                projectDao, mapper, projectAccessCache, auditLogDao, entityManager,
                projectMemberService, clientRegistrationService, roleDao, googlePlacesService);
        return new Fixture(service, projectMemberService, clientRegistrationService, roleDao);
    }

    private static RoleEntity role(long id, String code) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(code);
        role.setNameRU("name-ru");
        role.setNamePL("name-pl");
        return role;
    }

    private static ProjectMemberEntity stubMember(Long userId, Long roleId) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setName("user-" + userId);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(SAVED_PROJECT_ID);
        member.setProjectRole(role(roleId, "R" + roleId));
        return member;
    }

    private static CreateProjectRequest request(List<ProjectMemberInput> members, ClientBlock client) {
        return new CreateProjectRequest(
                "Project", null, null, null, null, null, null, null, null, null,
                members, client);
    }

    // Feature: FOR-04-13-project, Property 3 (existing-client branch)
    // For any generated CLIENT role id, existing client user id, and team-member list (with
    // arbitrary role ids), the client membership is created via ProjectMemberService.assign with
    // exactly the server-resolved CLIENT role id — never a member's projectRoleId or any other
    // request value.
    // Validates: Requirements 2.7
    @Property(tries = 100)
    void existingClientAssignedUnderResolvedClientRole(
            @ForAll("clientRoleIds") long clientRoleId,
            @ForAll("existingClientUserIds") long existingClientUserId,
            @ForAll("memberLists") List<ProjectMemberInput> members) {

        Fixture f = newFixture(clientRoleId);
        ClientBlock client = new ClientBlock(existingClientUserId, null);

        f.service().createProject(request(members, client));

        // The role always comes from findByCode("CLIENT").
        verify(f.roleDao()).findByCode(CLIENT);

        // Capture every assign(...) invocation and locate the client assignment.
        ArgumentCaptor<Long> userIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> projectIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> roleIds = ArgumentCaptor.forClass(Long.class);
        verify(f.projectMemberService(), times(members.size() + 1))
                .assign(userIds.capture(), projectIds.capture(), roleIds.capture());

        // The last assign is the client assignment (members are assigned first, then the client).
        List<Long> capturedUsers = userIds.getAllValues();
        List<Long> capturedProjects = projectIds.getAllValues();
        List<Long> capturedRoles = roleIds.getAllValues();
        int last = capturedRoles.size() - 1;

        assertThat(capturedUsers.get(last)).isEqualTo(existingClientUserId);
        assertThat(capturedProjects.get(last)).isEqualTo(SAVED_PROJECT_ID);
        // The client is assigned under the server-resolved CLIENT role id, regardless of inputs.
        assertThat(capturedRoles.get(last)).isEqualTo(clientRoleId);

        // No client assignment ever uses a role id that was carried by the request's client block —
        // the block carries no role id at all, and the resolved id governs.
        assertThat(capturedRoles.get(last)).isEqualTo(f.roleDao().findByCode(CLIENT).get().getId());
    }

    // Feature: FOR-04-13-project, Property 3 (new-client branch)
    // For any generated CLIENT role id and team-member list, a newClient block routes the client
    // through ClientRegistrationService.register (which fixes CLIENT server-side); ProjectService
    // never issues a direct client assign with a request-borne role, and only the team members are
    // assigned directly.
    // Validates: Requirements 2.8
    @Property(tries = 100)
    void newClientRoutedThroughServerFixedRegistration(
            @ForAll("clientRoleIds") long clientRoleId,
            @ForAll("memberLists") List<ProjectMemberInput> members,
            @ForAll @Size(min = 1, max = 20) String clientName) {

        Fixture f = newFixture(clientRoleId);
        ClientBlock client = new ClientBlock(
                null, new NewClientInput(clientName, "client@example.com", null, null));

        f.service().createProject(request(members, client));

        // The new-client path delegates to the FOR-03-05 flow, which fixes CLIENT server-side.
        ArgumentCaptor<ClientRegistrationRequest> reg =
                ArgumentCaptor.forClass(ClientRegistrationRequest.class);
        verify(f.clientRegistrationService()).register(reg.capture());
        // The registration request carries no role identifier (server fixes CLIENT).
        assertThat(reg.getValue().projectId()).isEqualTo(SAVED_PROJECT_ID);

        // ProjectService issues assign(...) only for the team members — never a direct client
        // assign with a request-borne role in this branch.
        ArgumentCaptor<Long> assignedUsers = ArgumentCaptor.forClass(Long.class);
        verify(f.projectMemberService(), times(members.size()))
                .assign(assignedUsers.capture(), anyLong(), anyLong());

        List<Long> memberUserIds = new ArrayList<>();
        for (ProjectMemberInput m : members) {
            memberUserIds.add(m.userId());
        }
        assertThat(assignedUsers.getAllValues()).containsExactlyElementsOf(memberUserIds);
    }
}
