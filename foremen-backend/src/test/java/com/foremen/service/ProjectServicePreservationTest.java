package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.PlaceDetailsDto;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * PRESERVATION TESTS — FOR-04-bugs Requirements 3.6 and 3.7.
 *
 * <p>These capture BASELINE {@link ProjectService} behavior that MUST remain unchanged after the
 * backend fixes (tasks 9 and 10). They are expected to <b>PASS on the current UNFIXED code</b>.
 *
 * <ul>
 *   <li><b>Req 3.7 — project UPDATE still audited.</b> {@link ProjectService} does NOT override the
 *       generic {@code update(...)}, so a project update routes through the audited
 *       {@link AdminService#update(Object, Object)} default and writes one {@link AuditLogEntity}
 *       with {@code entityClass = "ProjectEntity"} and {@code operation = "UPDATE"}. This path is
 *       decoupled from {@code createProject} (which the Bug 6 fix will touch) and must stay audited.</li>
 *   <li><b>Req 3.6 — server-side place resolution on create.</b> When {@code createProject} receives
 *       a request with a {@code googlePlaceId} set and {@code formattedAddress}/{@code latitude}/
 *       {@code longitude} all null, it resolves details via {@link GooglePlacesService#resolveDetails}
 *       and persists the resolved fields on the {@link ProjectEntity}. This must remain true after
 *       the Bug 4 fix.</li>
 * </ul>
 *
 * <p>Both tests instantiate {@link ProjectService} with Mockito mocks for all nine collaborators
 * (mirroring {@link ProjectCreateAuditExplorationTest}), so they run fast with no Spring context and
 * no Testcontainers stack.
 *
 * Feature: FOR-04-bugs, Bugs 4/6 preservation
 * Validates: Requirements 3.6, 3.7
 */
class ProjectServicePreservationTest {

    private record Fixture(ProjectService service,
                           ProjectDao projectDao,
                           ProjectServiceMapper mapper,
                           AuditLogDao auditLogDao,
                           GooglePlacesService googlePlacesService) {}

    private static Fixture newFixture() {
        ProjectDao projectDao = mock(ProjectDao.class);
        ProjectServiceMapper mapper = mock(ProjectServiceMapper.class);
        ProjectAccessCache accessCache = mock(ProjectAccessCache.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProjectMemberService memberService = mock(ProjectMemberService.class);
        ClientRegistrationService clientRegistrationService = mock(ClientRegistrationService.class);
        RoleDao roleDao = mock(RoleDao.class);
        GooglePlacesService googlePlacesService = mock(GooglePlacesService.class);

        when(mapper.getI18nSupportedProperties()).thenReturn(Set.of());

        ProjectService service = new ProjectService(
                projectDao, mapper, accessCache, auditLogDao, entityManager,
                memberService, clientRegistrationService, roleDao, googlePlacesService);
        return new Fixture(service, projectDao, mapper, auditLogDao, googlePlacesService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- Req 3.7: project UPDATE remains audited via AdminService.update ---

    @Test
    @DisplayName("project update(...) writes one ProjectEntity/UPDATE audit row "
            + "(baseline via AdminService.update — must stay audited)")
    void projectUpdateWritesUpdateAuditRow() {
        Fixture f = newFixture();

        // ProjectService.update is inherited from ProjectScopedService, which asserts project access
        // BEFORE routing to the audited AdminService.update default. An ADMIN caller is the real
        // bypass path (assertProjectAccess returns without resolving the owning project id), so the
        // update proceeds and the UPDATE audit row is written — the baseline we must preserve.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        ProjectEntity existing = new ProjectEntity();
        existing.setId(99L);
        existing.setName("Before");
        existing.setStatus(ProjectStatus.DRAFT);

        when(f.projectDao().findById(99L)).thenReturn(Optional.of(existing));
        when(f.projectDao().save(any(ProjectEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(f.mapper().toServiceExtendedModel(any(ProjectEntity.class)))
                .thenReturn(model(99L, "After"));

        ProjectServiceExtendedModel update = model(99L, "After");
        f.service().update(99L, update);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(f.auditLogDao(), times(1)).save(captor.capture());

        AuditLogEntity row = captor.getValue();
        assertThat(row.getEntityClass()).isEqualTo("ProjectEntity");
        assertThat(row.getOperation()).isEqualTo("UPDATE");
        assertThat(row.getEntityId()).isEqualTo(99L);
    }

    // --- Req 3.6: server-side place resolution on create ---

    @Test
    @DisplayName("createProject resolves googlePlaceId server-side and persists "
            + "formattedAddress/latitude/longitude (baseline — must be preserved)")
    void createProjectResolvesPlaceDetailsServerSide() {
        Fixture f = newFixture();

        when(f.projectDao().save(any(ProjectEntity.class))).thenAnswer(inv -> {
            ProjectEntity entity = inv.getArgument(0);
            entity.setId(42L);
            return entity;
        });

        String placeId = "place-abc";
        BigDecimal lat = new BigDecimal("52.2296756");
        BigDecimal lng = new BigDecimal("21.0122287");
        PlaceDetailsDto details = new PlaceDetailsDto(
                "Resolved Street 1, Warsaw", lat, lng, List.of());
        when(f.googlePlacesService().resolveDetails(placeId)).thenReturn(details);

        // Request carries a placeId but NO formattedAddress/latitude/longitude → triggers resolution.
        CreateProjectRequest request = new CreateProjectRequest(
                "Place Resolution Project", null, placeId, null, null, null, null,
                null, null, null, null, null);

        f.service().createProject(request);

        verify(f.googlePlacesService(), times(1)).resolveDetails(eq(placeId));

        ArgumentCaptor<ProjectEntity> captor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(f.projectDao(), times(1)).save(captor.capture());

        ProjectEntity saved = captor.getValue();
        assertThat(saved.getFormattedAddress()).isEqualTo("Resolved Street 1, Warsaw");
        assertThat(saved.getLatitude()).isEqualByComparingTo(lat);
        assertThat(saved.getLongitude()).isEqualByComparingTo(lng);
    }

    private static ProjectServiceExtendedModel model(Long id, String name) {
        return new ProjectServiceExtendedModel(
                id, name, null, null, null, null, null, null, null, null, ProjectStatus.DRAFT);
    }
}
