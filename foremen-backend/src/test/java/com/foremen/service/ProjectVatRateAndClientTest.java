package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foremen.controller.model.ClientBlock;
import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.ProjectCreateResponse;
import com.foremen.controller.model.ProjectMemberSummaryDto;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Project-level VAT rate on create and the add-client-on-edit path of {@link ProjectService}. */
class ProjectVatRateAndClientTest {

    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ProjectMemberService projectMemberService = mock(ProjectMemberService.class);
    private final RoleDao roleDao = mock(RoleDao.class);
    private final ProjectService service = spy(new ProjectService(
            projectDao, mock(ProjectServiceMapper.class), mock(ProjectAccessCache.class),
            mock(AuditLogDao.class), entityManager, projectMemberService,
            mock(ClientRegistrationService.class), roleDao, mock(GooglePlacesService.class),
            mock(ProjectActivationService.class), mock(UserDao.class)));

    private static CreateProjectRequest request(Long vatRateId) {
        return new CreateProjectRequest(
                "P", null, null, null, null, null, null, null, null, null, vatRateId, null, null);
    }

    private void stubSave() {
        when(projectDao.save(any(ProjectEntity.class))).thenAnswer(inv -> {
            ProjectEntity p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
    }

    @Test
    void createDefaultsToTheEightPercentRateLookedUpByValue() {
        stubSave();
        VatRateEntity eight = new VatRateEntity();
        eight.setId(77L);
        eight.setRate(new BigDecimal("8.00"));
        when(projectDao.findVatRatesByRate(ProjectService.DEFAULT_VAT_RATE)).thenReturn(List.of(eight));

        ProjectCreateResponse response = service.createProject(request(null));

        assertThat(response.vatRateId()).isEqualTo(77L);
    }

    @Test
    void createUsesTheRequestedRateWhenSupplied() {
        stubSave();
        VatRateEntity chosen = new VatRateEntity();
        chosen.setId(5L);
        when(entityManager.getReference(VatRateEntity.class, 5L)).thenReturn(chosen);

        ProjectCreateResponse response = service.createProject(request(5L));

        assertThat(response.vatRateId()).isEqualTo(5L);
    }

    @Test
    void addClientAssignsAnExistingClientUnderTheClientRole() {
        doReturn(mock(ProjectServiceExtendedModel.class)).when(service).findById(10L);
        RoleEntity clientRole = new RoleEntity();
        clientRole.setId(9L);
        clientRole.setCode("CLIENT");
        clientRole.setNamePL("Klient");
        clientRole.setNameRU("Клиент");
        when(roleDao.findByCode("CLIENT")).thenReturn(java.util.Optional.of(clientRole));
        UserEntity user = new UserEntity();
        user.setId(3L);
        user.setName("Client");
        user.setRole(clientRole);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(10L);
        member.setProjectRole(clientRole);
        when(projectMemberService.assign(anyLong(), anyLong(), anyLong())).thenReturn(member);

        ProjectMemberSummaryDto summary = service.addClient(10L, new ClientBlock(3L, null));

        verify(projectMemberService).assign(3L, 10L, 9L);
        assertThat(summary).isNotNull();
        assertThat(summary.roleCode()).isEqualTo("CLIENT");
    }
}
