package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.ProjectNotReadyException;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.readiness.AreaContribution;
import com.foremen.service.readiness.OutstandingItem;
import com.foremen.service.readiness.ProjectReadinessResult;
import com.foremen.service.readiness.ProjectReadinessService;
import com.foremen.service.readiness.ReadinessState;

/**
 * FOR-05-12 task 7.1 — plain Mockito unit tests for {@link ProjectActivationService}, the single
 * server-side readiness gate behind the explicit {@code pre-activation → ACTIVE} activation. The
 * collaborators ({@link ProjectReadinessService}, {@link ProjectDao}, {@link ProjectServiceMapper},
 * {@link ProjectAccessCache}, {@link ForemenPermissionEvaluator}) are mocked; the caller role /
 * ADMIN flag is driven through {@link SecurityContextHolder}, mirroring
 * {@code ProjectReadinessServiceTest} / {@code ProjectScheduleServiceTest}.
 *
 * <p>Exercises the full gate matrix (design §Testing strategy, §Error handling):
 * <ul>
 *   <li>pre-activation (DRAFT) + readiness 100% → ACTIVE, saved, mapped model returned;</li>
 *   <li>pre-activation + &lt;100%, force=false → {@link ProjectNotReadyException} carrying ONLY the
 *       areas whose state != {@code DONE}, status unchanged (no ACTIVE save);</li>
 *   <li>pre-activation + &lt;100% + force=true by ADMIN / by MANAGER → ACTIVE;</li>
 *   <li>pre-activation + &lt;100% + force=true by a non-ADMIN/non-MANAGER → still rejected (force
 *       ignored for that role);</li>
 *   <li>already ACTIVE → idempotent success, no readiness computed, no status corruption;</li>
 *   <li>terminal/later status (COMPLETED) → {@code 409 error.project.status.illegalTransition};</li>
 *   <li>access gate: non-accessible project (non-ADMIN, not in {@link ProjectAccessCache}) →
 *       {@code 404 error.entity.not.found};</li>
 *   <li>permission gate: caller without {@code PROJECTS} UPDATE → {@code 403 error.access.denied}.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 7.2</b>
 */
class ProjectActivationServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long USER_ID = 7L;

    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final ProjectServiceMapper projectServiceMapper = mock(ProjectServiceMapper.class);
    private final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
    private final ForemenPermissionEvaluator permissionEvaluator = mock(ForemenPermissionEvaluator.class);
    private final ProjectReadinessService projectReadinessService = mock(ProjectReadinessService.class);

    private final ProjectActivationService service = new ProjectActivationService(
            projectDao, projectServiceMapper, projectAccessCache, permissionEvaluator, projectReadinessService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =======================================================================
    // Happy path — fully ready pre-activation project is activated
    // =======================================================================

    @Test
    @DisplayName("pre-activation (DRAFT) + readiness 100% → ACTIVE, saved, mapped model returned (R1.1, R2.2)")
    void fullyReadyPreActivationIsActivated() {
        authenticateRole("MANAGER");
        allowUpdate("MANAGER");
        ProjectEntity project = projectFound(ProjectStatus.DRAFT);
        readinessIs(100, List.of());
        ProjectServiceExtendedModel mapped = savedModelOf(project);

        ProjectServiceExtendedModel result = service.activate(PROJECT_ID, false);

        assertThat(result).isSameAs(mapped);
        // Status flipped to ACTIVE and the (now ACTIVE) entity was persisted.
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        verify(projectDao).save(project);
    }

    // =======================================================================
    // Readiness gate — not ready, no force → blocked with only non-DONE areas
    // =======================================================================

    @Test
    @DisplayName("pre-activation + <100% + force=false → ProjectNotReadyException with only non-DONE areas, no ACTIVE save (R2.1, R2.3)")
    void notReadyWithoutForceIsBlockedAndCarriesOnlyNotReadyAreas() {
        authenticateRole("MANAGER");
        allowUpdate("MANAGER");
        ProjectEntity project = projectFound(ProjectStatus.DRAFT);

        AreaContribution doneArea = area("rooms", ReadinessState.DONE, 100);
        AreaContribution partialArea = area("estimate", ReadinessState.PARTIAL, 40);
        AreaContribution blockedArea = area("offer", ReadinessState.BLOCKED, 0);
        readinessIs(46, List.of(doneArea, partialArea, blockedArea));

        assertThatThrownBy(() -> service.activate(PROJECT_ID, false))
                .isInstanceOf(ProjectNotReadyException.class)
                .satisfies(ex -> {
                    List<AreaContribution> notReady = ((ProjectNotReadyException) ex).getNotReadyAreas();
                    // Only the non-DONE areas are carried; the DONE area is excluded.
                    assertThat(notReady).containsExactly(partialArea, blockedArea);
                    assertThat(notReady).doesNotContain(doneArea);
                });

        // Status untouched and no ACTIVE save happened.
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.DRAFT);
        verify(projectDao, never()).save(any());
    }

    // =======================================================================
    // Force — honored only for ADMIN / MANAGER
    // =======================================================================

    @Test
    @DisplayName("pre-activation + <100% + force=true by ADMIN → ACTIVE (R3.1)")
    void notReadyForcedByAdminIsActivated() {
        authenticateAdmin(); // ADMIN bypasses the access gate (code-level) and the real evaluator allows ADMIN
        allowUpdate("ADMIN"); // the real ForemenPermissionEvaluator short-circuits ADMIN to allow
        ProjectEntity project = projectFound(ProjectStatus.APPROVED);
        // Readiness is NOT computed when a valid force overrides the gate; stub leniently just in case.
        lenient().when(projectReadinessService.getReadinessAllAreas(PROJECT_ID))
                .thenReturn(result(10, List.of(area("estimate", ReadinessState.PARTIAL, 10))));
        ProjectServiceExtendedModel mapped = savedModelOf(project);

        ProjectServiceExtendedModel result = service.activate(PROJECT_ID, true);

        assertThat(result).isSameAs(mapped);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        verify(projectDao).save(project);
        // The readiness gate was bypassed entirely by the valid force.
        verify(projectReadinessService, never()).getReadinessAllAreas(any());
    }

    @Test
    @DisplayName("pre-activation + <100% + force=true by MANAGER → ACTIVE (R3.1)")
    void notReadyForcedByManagerIsActivated() {
        authenticateRole("MANAGER");
        allowUpdate("MANAGER");
        ProjectEntity project = projectFound(ProjectStatus.DRAFT);
        ProjectServiceExtendedModel mapped = savedModelOf(project);

        ProjectServiceExtendedModel result = service.activate(PROJECT_ID, true);

        assertThat(result).isSameAs(mapped);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        verify(projectDao).save(project);
        verify(projectReadinessService, never()).getReadinessAllAreas(any());
    }

    @Test
    @DisplayName("pre-activation + <100% + force=true by non-ADMIN/non-MANAGER (FOREMAN) → still rejected (R3.2)")
    void notReadyForcedByNonPrivilegedRoleIsStillBlocked() {
        authenticateRole("FOREMAN");
        allowUpdate("FOREMAN"); // the role holds UPDATE, but force is ignored for it
        ProjectEntity project = projectFound(ProjectStatus.DRAFT);
        AreaContribution partialArea = area("estimate", ReadinessState.PARTIAL, 40);
        readinessIs(40, List.of(partialArea));

        assertThatThrownBy(() -> service.activate(PROJECT_ID, true))
                .isInstanceOf(ProjectNotReadyException.class)
                .satisfies(ex -> assertThat(((ProjectNotReadyException) ex).getNotReadyAreas())
                        .containsExactly(partialArea));

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.DRAFT);
        verify(projectDao, never()).save(any());
    }

    // =======================================================================
    // Pre-activation check — idempotent ACTIVE, illegal terminal transition
    // =======================================================================

    @Test
    @DisplayName("already ACTIVE → idempotent success, no readiness computed, no status corruption (R1.2)")
    void alreadyActiveIsIdempotent() {
        authenticateRole("MANAGER");
        allowUpdate("MANAGER");
        ProjectEntity project = projectFound(ProjectStatus.ACTIVE);
        ProjectServiceExtendedModel mapped = mock(ProjectServiceExtendedModel.class);
        when(projectServiceMapper.toServiceExtendedModel(project)).thenReturn(mapped);

        ProjectServiceExtendedModel result = service.activate(PROJECT_ID, false);

        assertThat(result).isSameAs(mapped);
        // Status stays ACTIVE, nothing is saved, and readiness is never computed.
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        verify(projectDao, never()).save(any());
        verify(projectReadinessService, never()).getReadinessAllAreas(any());
    }

    @Test
    @DisplayName("terminal/later status (COMPLETED) → 409 error.project.status.illegalTransition (R1.2)")
    void terminalStatusIsIllegalTransition() {
        authenticateRole("MANAGER");
        allowUpdate("MANAGER");
        ProjectEntity project = projectFound(ProjectStatus.COMPLETED);

        assertThatThrownBy(() -> service.activate(PROJECT_ID, false))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.status.illegalTransition");
                });

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
        verify(projectDao, never()).save(any());
        verify(projectReadinessService, never()).getReadinessAllAreas(any());
    }

    // =======================================================================
    // Access gate (404) — runs before existence lookup and before readiness
    // =======================================================================

    @Test
    @DisplayName("non-accessible project (non-ADMIN, not in ProjectAccessCache) → 404 error.entity.not.found (R1.4)")
    void nonAccessibleProjectIs404() {
        authenticateRole("MANAGER");
        when(projectAccessCache.get(USER_ID)).thenReturn(Set.of()); // project not accessible

        assertThatThrownBy(() -> service.activate(PROJECT_ID, false))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                });

        // The access gate short-circuits before loading the row, before the permission + readiness gates.
        verify(projectDao, never()).findById(any());
        verify(projectReadinessService, never()).getReadinessAllAreas(any());
        verify(projectDao, never()).save(any());
    }

    // =======================================================================
    // Permission gate (403) — caller without PROJECTS UPDATE
    // =======================================================================

    @Test
    @DisplayName("caller without PROJECTS UPDATE → 403 error.access.denied (R1.3, R3.3)")
    void callerWithoutUpdatePermissionIsForbidden() {
        authenticateRole("FOREMAN");
        projectFound(ProjectStatus.DRAFT); // accessible + existing, so the permission gate is reached
        when(projectAccessCache.get(USER_ID)).thenReturn(Set.of(PROJECT_ID));
        when(permissionEvaluator.isAllowed("FOREMAN", "PROJECTS", "UPDATE")).thenReturn(false);

        assertThatThrownBy(() -> service.activate(PROJECT_ID, false))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.getMessageCode()).isEqualTo("error.access.denied");
                });

        // Blocked before readiness + save.
        verify(projectReadinessService, never()).getReadinessAllAreas(any());
        verify(projectDao, never()).save(any());
    }

    // --- collaborator builders ----------------------------------------------

    /** Makes {@code PROJECT_ID} accessible to {@code USER_ID} and present with the given status. */
    private ProjectEntity projectFound(ProjectStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setName("Project");
        project.setStatus(status);
        lenient().when(projectAccessCache.get(USER_ID)).thenReturn(Set.of(PROJECT_ID));
        lenient().when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        return project;
    }

    /** Grants the given role {@code PROJECTS} UPDATE through the evaluator. */
    private void allowUpdate(String roleCode) {
        lenient().when(permissionEvaluator.isAllowed(roleCode, "PROJECTS", "UPDATE")).thenReturn(true);
    }

    /** Stubs the project-wide readiness over all areas. */
    private void readinessIs(int overallPercentage, List<AreaContribution> areas) {
        when(projectReadinessService.getReadinessAllAreas(PROJECT_ID))
                .thenReturn(result(overallPercentage, areas));
    }

    /** Stubs the mapper so the saved entity maps to a fresh model, returned on activation. */
    private ProjectServiceExtendedModel savedModelOf(ProjectEntity project) {
        ProjectServiceExtendedModel mapped = mock(ProjectServiceExtendedModel.class);
        when(projectDao.save(project)).thenReturn(project);
        when(projectServiceMapper.toServiceExtendedModel(project)).thenReturn(mapped);
        return mapped;
    }

    private static ProjectReadinessResult result(int overallPercentage, List<AreaContribution> areas) {
        ReadinessState overallState = overallPercentage == 100 ? ReadinessState.DONE : ReadinessState.PARTIAL;
        return new ProjectReadinessResult(PROJECT_ID, overallPercentage, overallState, areas);
    }

    private static AreaContribution area(String key, ReadinessState state, int percentage) {
        List<OutstandingItem> items = (state == ReadinessState.DONE && percentage == 100)
                ? List.of()
                : List.of(new OutstandingItem("readiness." + key + ".todo", Map.of("count", 1), key));
        return new AreaContribution(key, "workspace.tab." + key, percentage, state, items);
    }

    // --- auth helpers -------------------------------------------------------

    private static void authenticateAdmin() {
        authenticate("1", "ROLE_ADMIN");
    }

    private static void authenticateRole(String roleCode) {
        authenticate(String.valueOf(USER_ID), "ROLE_" + roleCode);
    }

    private static void authenticate(String principal, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
