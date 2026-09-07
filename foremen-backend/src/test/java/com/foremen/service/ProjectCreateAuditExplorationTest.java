package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * BUG CONDITION EXPLORATION TEST — FOR-04-bugs Bug 6 (Requirement 1.6 / expected 2.6).
 *
 * <p><b>CRITICAL: this test is expected to FAIL on the current UNFIXED code.</b> Its assertion
 * encodes the <em>desired</em> behavior — that a successful {@code POST /api/projects} (routed
 * through {@link ProjectService#createProject(CreateProjectRequest)}) writes exactly one
 * {@link AuditLogEntity} with {@code entityClass = "ProjectEntity"} and {@code operation = "CREATE"}
 * within the create transaction. Failure confirms the bug: {@code createProject} persists via
 * {@code projectDao.save(...)} and never calls {@code saveAudit(...)}, so no CREATE audit row is
 * written today.
 *
 * <p>The test instantiates {@link ProjectService} with Mockito mocks for all nine collaborators
 * (mirroring {@code ProjectCreateStatusDefaultPropertyTest}), so it runs fast with no Spring context
 * and no Testcontainers stack. {@code projectDao.save} echoes back the entity with a generated id;
 * the request carries no members and no client block, isolating the create/audit path. An
 * {@link ArgumentCaptor} inspects the entity handed to {@code auditLogDao.save(...)}.
 *
 * <p>Once Bug 6 is fixed ({@code saveAudit(null, saved, "CREATE")} added to {@code createProject}),
 * this test SHALL pass.
 *
 * Feature: FOR-04-bugs, Bug 6 (project CREATE not audited)
 * Validates: Requirements 1.6, 2.6
 */
class ProjectCreateAuditExplorationTest {

    private record Fixture(ProjectService service, AuditLogDao auditLogDao) {}

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

        // save echoes back the entity it is given, assigning a generated id (so the audited "after"
        // snapshot carries a real ProjectEntity with an id).
        when(projectDao.save(any(ProjectEntity.class))).thenAnswer(invocation -> {
            ProjectEntity entity = invocation.getArgument(0);
            entity.setId(42L);
            return entity;
        });

        ProjectService service = new ProjectService(
                projectDao, mapper, accessCache, auditLogDao, entityManager,
                memberService, clientRegistrationService, roleDao, googlePlacesService);
        return new Fixture(service, auditLogDao);
    }

    /** A minimal valid request carrying only a name (no members, no client). */
    private static CreateProjectRequest request() {
        return new CreateProjectRequest(
                "Audit Test Project", null, null, null, null, null, null, null, null,
                null, null, null);
    }

    @Test
    @DisplayName("createProject writes exactly one ProjectEntity/CREATE audit row "
            + "(FAILS now: createProject never calls saveAudit)")
    void createProjectWritesCreateAuditRow() {
        Fixture f = newFixture();

        f.service().createProject(request());

        // Expected behavior (2.6): exactly one AuditLogEntity(entityClass=ProjectEntity,
        // operation=CREATE) is saved within the create transaction. On unfixed code auditLogDao.save
        // is never invoked, so this verify fails — confirming the bug.
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(f.auditLogDao(), times(1)).save(captor.capture());

        List<AuditLogEntity> rows = captor.getAllValues();
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getEntityClass()).isEqualTo("ProjectEntity");
        assertThat(row.getOperation()).isEqualTo("CREATE");
        assertThat(row.getEntityId()).isEqualTo(42L);
    }
}
