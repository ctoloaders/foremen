package com.foremen.service.property;

import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.ProjectCreateResponse;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the project-creation status default enforced by
 * {@link ProjectService#createProject(CreateProjectRequest)} (FOR-04-13 Requirement 2.3).
 *
 * <p><b>Property 1: Status defaults to DRAFT.</b> When a {@link CreateProjectRequest} carries a
 * {@code null} status, the created project persists {@code status = DRAFT}; when a concrete
 * {@link ProjectStatus} is supplied, that value is preserved unchanged.
 *
 * <p>The test instantiates {@link ProjectService} with Mockito mocks for all nine collaborators.
 * {@code ProjectDao.save} echoes back the entity it is handed with a generated id, and an
 * {@link ArgumentCaptor} inspects the entity actually passed to {@code save} — this is exactly the
 * status the service would persist. The request omits members and the client block so no other
 * collaborator is exercised, isolating the status-defaulting branch. The status is generated over
 * the full {@link ProjectStatus} enum plus {@code null}.
 *
 * Feature: FOR-04-13-project, Property 1
 * Validates: Requirements 2.3
 */
@Tag("Feature: FOR-04-13-project, Property 1: Status defaults to DRAFT")
class ProjectCreateStatusDefaultPropertyTest {

    /** The full ProjectStatus enum plus null (the "status omitted" case). */
    @Provide
    Arbitrary<ProjectStatus> requestStatuses() {
        return Arbitraries.of(
                ProjectStatus.DRAFT,
                ProjectStatus.ACTIVE,
                ProjectStatus.ON_HOLD,
                ProjectStatus.COMPLETED,
                ProjectStatus.CANCELLED,
                null);
    }

    private record Fixture(ProjectService service, ProjectDao projectDao) {}

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

        // save echoes back the entity it is given, assigning a generated id.
        when(projectDao.save(any(ProjectEntity.class))).thenAnswer(invocation -> {
            ProjectEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        ProjectService service = new ProjectService(
                projectDao, mapper, accessCache, auditLogDao, entityManager,
                memberService, clientRegistrationService, roleDao, googlePlacesService);
        return new Fixture(service, projectDao);
    }

    /** A minimal valid request carrying only a name and the generated status (no members, no client). */
    private static CreateProjectRequest request(ProjectStatus status) {
        return new CreateProjectRequest(
                "Test Project", null, null, null, null, null, null, null, null,
                status, null, null);
    }

    // Feature: FOR-04-13-project, Property 1
    // For all request statuses (the five enum values plus null), createProject persists DRAFT when
    // the request status is null and preserves the supplied value otherwise; the response echoes the
    // persisted status.
    // Validates: Requirements 2.3
    @Property(tries = 100)
    void statusDefaultsToDraftWhenNullOtherwisePreserved(@ForAll("requestStatuses") ProjectStatus requestStatus) {
        Fixture f = newFixture();

        ProjectCreateResponse response = f.service().createProject(request(requestStatus));

        ProjectStatus expected = requestStatus != null ? requestStatus : ProjectStatus.DRAFT;

        // The entity actually handed to save carries the expected status.
        ArgumentCaptor<ProjectEntity> captor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(f.projectDao()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(expected);

        // The returned projection echoes the persisted status.
        assertThat(response.status()).isEqualTo(expected);
    }
}
