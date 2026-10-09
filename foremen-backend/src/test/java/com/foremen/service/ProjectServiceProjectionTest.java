package com.foremen.service;

import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.ProjectListDto;
import com.foremen.controller.model.ProjectMemberInput;
import com.foremen.controller.model.ProjectMemberSummaryDto;
import com.foremen.controller.model.ProjectReadDto;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.ProjectServiceModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FOR-05-09 task 15.3 — unit tests for {@link ProjectService}'s multi-client projection and
 * CLIENT-caller suppression (Requirement 19) plus the project-creation restriction (Requirement 26,
 * via {@link ProjectService#createProject}).
 *
 * <p>The projection/masking helpers ({@code toListDto}, {@code toReadDto}) are private, so they are
 * exercised through reflection with a hand-built {@link ProjectEntity} and a stubbed caller
 * {@code SecurityContext}: a non-CLIENT admin-staff caller sees the full {@code members}/{@code
 * clients}/{@code client} projection with Internal_Attributes, a non-admin-staff (WORKER) caller
 * sees the projection with the Internal_Attributes masked but {@code assignmentStatus} kept, and a
 * CLIENT caller sees {@code members}/{@code clients}/{@code client} all suppressed (null).
 *
 * <p>The creation restriction is tested at the {@code createProject} orchestration level: each
 * rejection raised by {@code ProjectMemberService.assignAtCreation} propagates out of the
 * orchestrator (rolling the whole creation back under its {@code @Transactional} boundary) and the
 * client block is never processed.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceProjectionTest {

    @Mock
    private ProjectDao projectDao;
    @Mock
    private com.foremen.service.model.mapper.ProjectServiceMapper projectServiceMapper;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private com.foremen.service.audit.AuditLogDao auditLogDao;
    @Mock
    private jakarta.persistence.EntityManager entityManager;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private ClientRegistrationService clientRegistrationService;
    @Mock
    private RoleDao roleDao;
    @Mock
    private com.foremen.service.google.GooglePlacesService googlePlacesService;

    @InjectMocks
    private ProjectService service;

    private static final Long PROJECT_ID = 42L;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- helpers -------------------------------------------------------------

    private static void authenticate(String principal, String... authorities) {
        var granted = new ArrayList<SimpleGrantedAuthority>();
        for (String a : authorities) {
            granted.add(new SimpleGrantedAuthority(a));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", granted));
    }

    private static RoleEntity role(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNamePL(code + "_pl");
        role.setNameRU(code + "_ru");
        return role;
    }

    private static UserEntity user(Long id, String name) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setName(name);
        return u;
    }

    private static ProjectMemberEntity member(Long id, UserEntity user, RoleEntity role,
                                              AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setId(id);
        m.setUser(user);
        m.setProjectRole(role);
        m.setProjectId(PROJECT_ID);
        m.setAssignmentStatus(status);
        m.setTags(new ArrayList<>());
        return m;
    }

    /**
     * A project with: one MANAGER (admin-staff), one WORKER carrying worker-type + NIP + tags
     * (Internal_Attributes), and three CLIENT members added out of membership-id order so the
     * {@code clients} ordering (ascending membership id) is actually exercised. The CLIENT set spans
     * an INVITED/inactive user and an INACTIVE assignment to cover Requirement 19 criterion 1.
     */
    private ProjectEntity projectWithTeam() {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setName("Site A");
        project.setStatus(ProjectStatus.DRAFT);

        RoleEntity managerRole = role("MANAGER");
        RoleEntity workerRole = role("WORKER");
        RoleEntity clientRole = role("CLIENT");

        ProjectMemberEntity manager = member(1L, user(10L, "Manager"), managerRole, AssignmentStatus.ACTIVE);

        WorkerTypeEntity type = new WorkerTypeEntity();
        type.setId(5L);
        type.setCode("GENERAL");
        type.setNamePL("Ogólny");
        type.setNameRU("Общий");
        type.setActive(true);
        UserEntity workerUser = user(11L, "Worker");
        workerUser.setNip("1234563218");
        ProjectMemberEntity worker = member(2L, workerUser, workerRole, AssignmentStatus.ACTIVE);
        worker.setWorkerType(type);
        worker.setTags(new ArrayList<>(List.of("crew", "lead")));

        // Three CLIENT members; added to the collection in a non-ascending id order (7, 3, 9) so the
        // deriveClients ordering by membership id ascending (3, 7, 9) is a real assertion.
        ProjectMemberEntity client7 = member(7L, user(20L, "Client Seven"), clientRole, AssignmentStatus.ACTIVE);
        ProjectMemberEntity client3 = member(3L, user(21L, "Client Three"), clientRole, AssignmentStatus.INACTIVE);
        ProjectMemberEntity client9 = member(9L, user(22L, "Client Nine"), clientRole, AssignmentStatus.ACTIVE);

        project.setMembers(new ArrayList<>(List.of(manager, worker, client7, client3, client9)));
        return project;
    }

    @SuppressWarnings("unchecked")
    private ProjectListDto invokeToListDto(ProjectEntity entity) throws Exception {
        ProjectServiceModel model = new ProjectServiceModel(
                entity.getId(), entity.getName(), null, null, null, null, null, null, null, null,
                entity.getStatus());
        Method m = ProjectService.class.getDeclaredMethod(
                "toListDto", ProjectServiceModel.class, ProjectEntity.class);
        m.setAccessible(true);
        return (ProjectListDto) m.invoke(service, model, entity);
    }

    private ProjectReadDto invokeToReadDto(ProjectEntity entity) throws Exception {
        ProjectServiceExtendedModel model = new ProjectServiceExtendedModel(
                entity.getId(), entity.getName(), null, null, null, null, null, null, null, null,
                entity.getStatus());
        Method m = ProjectService.class.getDeclaredMethod(
                "toReadDto", ProjectServiceExtendedModel.class, ProjectEntity.class);
        m.setAccessible(true);
        return (ProjectReadDto) m.invoke(service, model, entity);
    }

    // --- projection: multi-client clients/client ordering (Req 19.1-19.3) ---

    @Test
    @DisplayName("list projection derives clients ordered by membership id ascending, including INVITED/inactive/INACTIVE clients, with client == clients[0]")
    void listProjectionDerivesMultiClientOrdering() throws Exception {
        authenticate("1", "ROLE_MANAGER"); // non-CLIENT, admin-staff

        ProjectListDto dto = invokeToListDto(projectWithTeam());

        // clients: one entry per CLIENT member, ordered by membership id ascending (3, 7, 9).
        assertThat(dto.clients()).extracting(ProjectMemberSummaryDto::userId)
                .containsExactly(21L, 20L, 22L);
        assertThat(dto.clients()).allSatisfy(c -> assertThat(c.roleCode()).isEqualTo("CLIENT"));
        // The INACTIVE / lowest-id client (id 3, user 21) is included and is clients[0] == client.
        assertThat(dto.client()).isNotNull();
        assertThat(dto.client().userId()).isEqualTo(21L);
        assertThat(dto.client().assignmentStatus()).isEqualTo(AssignmentStatus.INACTIVE);
        assertThat(dto.client()).isEqualTo(dto.clients().get(0));

        // members: every member including all CLIENTs -> # CLIENT members == # clients entries.
        assertThat(dto.members()).hasSize(5);
        long clientMembers = dto.members().stream()
                .filter(m -> "CLIENT".equals(m.roleCode())).count();
        assertThat(clientMembers).isEqualTo(dto.clients().size());
    }

    @Test
    @DisplayName("read projection returns an empty clients list (never null) and a null client when the project has no CLIENT member")
    void readProjectionEmptyClientsWhenNoClient() throws Exception {
        authenticate("1", "ROLE_MANAGER");

        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setName("No clients");
        project.setStatus(ProjectStatus.DRAFT);
        project.setMembers(new ArrayList<>(List.of(
                member(1L, user(10L, "Manager"), role("MANAGER"), AssignmentStatus.ACTIVE))));

        ProjectReadDto dto = invokeToReadDto(project);

        assertThat(dto.clients()).isNotNull().isEmpty();
        assertThat(dto.client()).isNull();
        assertThat(dto.members()).hasSize(1);
    }

    // --- masking: admin-staff vs non-admin-staff (Req 19.8) ---

    @Test
    @DisplayName("admin-staff reader sees the WORKER member's internal worker-type/NIP/tags")
    void adminStaffReaderSeesInternalAttributes() throws Exception {
        authenticate("1", "ROLE_MANAGER"); // admin-staff -> Internal_Attribute_Viewer

        ProjectReadDto dto = invokeToReadDto(projectWithTeam());

        ProjectMemberSummaryDto worker = dto.members().stream()
                .filter(m -> "WORKER".equals(m.roleCode())).findFirst().orElseThrow();
        assertThat(worker.workerTypeId()).isEqualTo(5L);
        assertThat(worker.workerTypeCode()).isEqualTo("GENERAL");
        assertThat(worker.workerTypeActive()).isTrue();
        assertThat(worker.nip()).isEqualTo("1234563218");
        assertThat(worker.tags()).containsExactly("crew", "lead");
        // assignmentStatus is present for everyone.
        assertThat(worker.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("non-admin-staff (WORKER) reader has the internal worker-type/NIP/tags masked but keeps assignmentStatus")
    void workerReaderHasInternalAttributesMasked() throws Exception {
        authenticate("1", "ROLE_WORKER"); // not admin-staff, not CLIENT -> masked but members visible

        ProjectReadDto dto = invokeToReadDto(projectWithTeam());

        // A WORKER caller is not a CLIENT, so members/clients are still projected.
        assertThat(dto.members()).hasSize(5);
        ProjectMemberSummaryDto worker = dto.members().stream()
                .filter(m -> "WORKER".equals(m.roleCode())).findFirst().orElseThrow();
        // Internal attributes are nulled (omitted by @JsonInclude(NON_NULL)) for a non-admin-staff reader.
        assertThat(worker.workerTypeId()).isNull();
        assertThat(worker.workerTypeCode()).isNull();
        assertThat(worker.workerTypeName()).isNull();
        assertThat(worker.workerTypeActive()).isNull();
        assertThat(worker.nip()).isNull();
        assertThat(worker.tags()).isNull();
        // assignmentStatus is NOT internal -> still returned.
        assertThat(worker.assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    // --- CLIENT-caller suppression (Req 19.9) ---

    @Test
    @DisplayName("CLIENT caller has members, clients, and client all suppressed in the list projection")
    void clientCallerSuppressesProjectionsInList() throws Exception {
        authenticate("1", "ROLE_CLIENT");

        ProjectListDto dto = invokeToListDto(projectWithTeam());

        assertThat(dto.members()).isNull();
        assertThat(dto.clients()).isNull();
        assertThat(dto.client()).isNull();
        // Base fields are still present.
        assertThat(dto.id()).isEqualTo(PROJECT_ID);
        assertThat(dto.name()).isEqualTo("Site A");
    }

    @Test
    @DisplayName("CLIENT caller has members, clients, and client all suppressed in the read projection")
    void clientCallerSuppressesProjectionsInRead() throws Exception {
        authenticate("1", "ROLE_CLIENT");

        ProjectReadDto dto = invokeToReadDto(projectWithTeam());

        assertThat(dto.members()).isNull();
        assertThat(dto.clients()).isNull();
        assertThat(dto.client()).isNull();
        assertThat(dto.id()).isEqualTo(PROJECT_ID);
    }

    // --- creation restriction with rollback (Req 26 via createProject) -------

    private static CreateProjectRequest requestWithMember(Long userId, Long projectRoleId, Long workerTypeId) {
        return new CreateProjectRequest(
                "New project", null, null, null, null, null, null, null, null, null,
                List.of(new ProjectMemberInput(userId, projectRoleId, workerTypeId)),
                null);
    }

    private void stubProjectSave() {
        when(projectDao.save(any(ProjectEntity.class))).thenAnswer(inv -> {
            ProjectEntity p = inv.getArgument(0);
            p.setId(PROJECT_ID);
            return p;
        });
    }

    @Test
    @DisplayName("createProject propagates a Worker_Role rejection and never processes the client block (rollback)")
    void createRejectsWorkerRoleAtCreationAndRollsBack() {
        authenticate("1", "ROLE_ADMIN");
        stubProjectSave();
        // The creation-restricted assign rejects a Worker_Role entry.
        when(projectMemberService.assignAtCreation(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.role.not.allowed.at.creation"));

        assertThatThrownBy(() -> service.createProject(requestWithMember(10L, 3L, null)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode())
                            .isEqualTo("error.project.member.role.not.allowed.at.creation");
                });

        // The whole creation rolls back: the client block is never processed and no CLIENT is registered.
        verifyNoInteractions(clientRegistrationService);
    }

    @Test
    @DisplayName("createProject propagates a non-null workerTypeId rejection (rollback), passing the workerTypeId through to the restricted assign")
    void createRejectsWorkerTypeAtCreationAndRollsBack() {
        authenticate("1", "ROLE_ADMIN");
        stubProjectSave();
        when(projectMemberService.assignAtCreation(10L, PROJECT_ID, 3L, 99L))
                .thenThrow(new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.worker.type.not.allowed"));

        assertThatThrownBy(() -> service.createProject(requestWithMember(10L, 3L, 99L)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.worker.type.not.allowed"));

        // The orchestrator forwards the non-null workerTypeId so the restricted assign can reject it.
        verify(projectMemberService).assignAtCreation(10L, PROJECT_ID, 3L, 99L);
        verifyNoInteractions(clientRegistrationService);
    }

    @Test
    @DisplayName("createProject propagates a role.mismatch rejection and never processes the client block (rollback)")
    void createRejectsRoleMismatchAndRollsBack() {
        authenticate("1", "ROLE_ADMIN");
        stubProjectSave();
        when(projectMemberService.assignAtCreation(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.project.member.role.mismatch"));

        assertThatThrownBy(() -> service.createProject(requestWithMember(10L, 999L, null)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.role.mismatch"));

        verifyNoInteractions(clientRegistrationService);
    }

    @Test
    @DisplayName("createProject propagates a duplicate-userId rejection (409) and never processes the client block (rollback)")
    void createRejectsDuplicateAndRollsBack() {
        authenticate("1", "ROLE_ADMIN");
        stubProjectSave();
        // Two members with the same userId; the second assignAtCreation call reports the duplicate.
        when(projectMemberService.assignAtCreation(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(member(1L, user(10L, "Manager"), role("MANAGER"), AssignmentStatus.ACTIVE))
                .thenThrow(new ForemenApiException(
                        HttpStatus.CONFLICT, "error.project.member.duplicate"));

        CreateProjectRequest request = new CreateProjectRequest(
                "Dup project", null, null, null, null, null, null, null, null, null,
                List.of(new ProjectMemberInput(10L, 3L, null), new ProjectMemberInput(10L, 3L, null)),
                null);

        assertThatThrownBy(() -> service.createProject(request))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.duplicate");
                });

        verifyNoInteractions(clientRegistrationService);
    }

    @Test
    @DisplayName("createProject assigns an admin-staff member and processes no client block when none is supplied")
    void createAssignsAdminStaffMemberWithoutClient() {
        authenticate("1", "ROLE_ADMIN");
        stubProjectSave();
        ProjectMemberEntity assigned =
                member(1L, user(10L, "Manager"), role("MANAGER"), AssignmentStatus.ACTIVE);
        when(projectMemberService.assignAtCreation(10L, PROJECT_ID, 3L, null)).thenReturn(assigned);

        var response = service.createProject(requestWithMember(10L, 3L, null));

        assertThat(response.id()).isEqualTo(PROJECT_ID);
        assertThat(response.members()).hasSize(1);
        assertThat(response.members().get(0).roleCode()).isEqualTo("MANAGER");
        assertThat(response.members().get(0).assignmentStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        verify(projectMemberService).assignAtCreation(10L, PROJECT_ID, 3L, null);
        verifyNoInteractions(clientRegistrationService);
    }
}
