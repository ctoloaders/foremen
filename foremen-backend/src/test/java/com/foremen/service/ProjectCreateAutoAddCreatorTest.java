package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.ProjectMemberInput;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for the creator auto-add in {@link ProjectService#createProject} (improvement: the
 * project creator is automatically added as a member in their own role). Collaborators are mocked;
 * the creator identity is supplied via the security principal (its name is the numeric user id).
 */
class ProjectCreateAutoAddCreatorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void autoAddsAnAdminStaffCreatorWithDerivedRole() {
        Fixture f = new Fixture();
        f.stubCreator(100L, "MANAGER", true);
        authenticateAs(100L);

        CreateProjectRequest request = request(null); // no explicit members
        f.service.createProject(request);

        // The creator is assigned via the creator-specific path (role derived from the Company_Role,
        // D2; the Accessible_Project gate skipped for the brand-new project).
        ArgumentCaptor<Long> userIdCap = ArgumentCaptor.forClass(Long.class);
        verify(f.projectMemberService).assignCreator(userIdCap.capture(), anyLong());
        assertThat(userIdCap.getValue()).isEqualTo(100L);
    }

    @Test
    void skipsAnAdminCreator() {
        Fixture f = new Fixture();
        f.stubCreator(1L, "ADMIN", true);
        authenticateAs(1L);

        f.service.createProject(request(null));

        // ADMIN is not an Assignable_Project_Role → never auto-added; creation still succeeds.
        verify(f.projectMemberService, never()).assignCreator(eq(1L), anyLong());
    }

    @Test
    void skipsAWorkerCreator() {
        Fixture f = new Fixture();
        f.stubCreator(5L, "WORKER", true);
        authenticateAs(5L);

        f.service.createProject(request(null));

        verify(f.projectMemberService, never()).assignCreator(eq(5L), anyLong());
    }

    @Test
    void skipsWhenCreatorAlreadyAmongSubmittedMembers() {
        Fixture f = new Fixture();
        f.stubCreator(100L, "MANAGER", true);
        authenticateAs(100L);

        // The creator (100) is explicitly listed in members[] → the explicit entry wins, the auto-add
        // is skipped so no duplicate assign is attempted for 100.
        CreateProjectRequest request = request(List.of(new ProjectMemberInput(100L, 1L)));
        f.service.createProject(request);

        // The explicit members[] entry assigns 100 via assignAtCreation; the creator auto-add
        // (assignCreator) is skipped so no duplicate is attempted for 100.
        verify(f.projectMemberService).assignAtCreation(eq(100L), anyLong(), eq(1L), isNull());
        verify(f.projectMemberService, never()).assignCreator(eq(100L), anyLong());
    }

    @Test
    void skipsWhenNoAuthenticatedPrincipal() {
        Fixture f = new Fixture();
        // No SecurityContext set → caller unresolved → no auto-add.
        f.service.createProject(request(null));

        verify(f.projectMemberService, never()).assignCreator(anyLong(), anyLong());
    }

    // --- helpers ---

    private static void authenticateAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "pw", List.of()));
    }

    private static CreateProjectRequest request(List<ProjectMemberInput> members) {
        return new CreateProjectRequest(
                "P", null, null, null, null, null, null, null, null, null, members, null);
    }

    private static final class Fixture {
        final ProjectDao projectDao = mock(ProjectDao.class);
        final ProjectServiceMapper mapper = mock(ProjectServiceMapper.class);
        final ProjectAccessCache accessCache = mock(ProjectAccessCache.class);
        final AuditLogDao auditLogDao = mock(AuditLogDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final ProjectMemberService projectMemberService = mock(ProjectMemberService.class);
        final ClientRegistrationService clientRegistrationService = mock(ClientRegistrationService.class);
        final RoleDao roleDao = mock(RoleDao.class);
        final GooglePlacesService googlePlacesService = mock(GooglePlacesService.class);
        final UserDao userDao = mock(UserDao.class);
        final ProjectService service;

        Fixture() {
            AtomicLong idSeq = new AtomicLong(1);
            when(projectDao.save(any(ProjectEntity.class))).thenAnswer(inv -> {
                ProjectEntity e = inv.getArgument(0);
                e.setId(idSeq.getAndIncrement());
                return e;
            });
            when(projectMemberService.assignAtCreation(anyLong(), anyLong(), any(), any()))
                    .thenAnswer(inv -> member(inv.getArgument(0)));
            when(projectMemberService.assignCreator(anyLong(), anyLong()))
                    .thenAnswer(inv -> member(inv.getArgument(0)));
            service = new ProjectService(
                    projectDao, mapper, accessCache, auditLogDao, entityManager,
                    projectMemberService, clientRegistrationService, roleDao, googlePlacesService,
                    mock(ProjectActivationService.class), userDao);
        }

        void stubCreator(long id, String roleCode, boolean active) {
            UserEntity user = new UserEntity();
            user.setId(id);
            user.setName("Creator " + id);
            user.setActive(active);
            RoleEntity role = new RoleEntity();
            role.setCode(roleCode);
            role.setNamePL(roleCode);
            role.setNameRU(roleCode);
            user.setRole(role);
            when(userDao.findById(id)).thenReturn(Optional.of(user));
        }
    }

    private static ProjectMemberEntity member(Long userId) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        UserEntity u = new UserEntity();
        u.setId(userId);
        u.setName("User " + userId);
        RoleEntity r = new RoleEntity();
        r.setCode("MANAGER");
        r.setNamePL("Menedżer");
        r.setNameRU("Менеджер");
        u.setRole(r);
        m.setUser(u);
        m.setProjectId(1L);
        m.setProjectRole(r);
        return m;
    }
}
