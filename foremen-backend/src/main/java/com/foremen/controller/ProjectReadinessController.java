package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.readiness.ProjectReadinessView;
import com.foremen.service.readiness.ProjectReadinessService;

import lombok.RequiredArgsConstructor;

/**
 * The aggregated project-readiness read endpoint (FOR-05-11 Requirements 2, 3, 4; design
 * §ProjectReadinessController, Decision D7). It exposes a single
 * {@code GET /api/projects/{projectId}/readiness} that returns the {@link ProjectReadinessView} —
 * the overall percentage, overall state, and the ordered per-area contributions for the caller's
 * Readable_Areas — delegating all composition to {@link ProjectReadinessService}.
 *
 * <h2>ABAC guarding (Requirement 2)</h2>
 * The class carries a class-level {@link PermissionResource @PermissionResource("PROJECTS")} and the
 * sole handler a method-level {@link PermissionOperation @PermissionOperation("READ")}, so the
 * {@code PermissionResolver} derives the {@code (PROJECTS, READ)} pair the {@code PermissionInterceptor}
 * enforces at runtime (401 unauthenticated, 403 without the grant; ADMIN bypass included) and
 * {@code PermissionAnnotationValidator} classifies the controller COMPLETE at startup (entity-creation
 * -rules step 3). The aggregate reuses the existing {@code PROJECTS} resource — <b>no new ABAC
 * resource is introduced</b> (Requirement 2.2, Decision D7); the real per-area filtering is the
 * service's per-{@code Area_Resource} READ check (Requirement 6).
 *
 * <p>The endpoint lives under {@code /api/projects/{projectId}/readiness} because the resource read
 * is a projection <em>of a project</em>, which keeps the {@code PROJECTS} gate honest. The service
 * owns the 404 {@code error.entity.not.found} project-scope gate (Requirement 3) — a missing or
 * inaccessible project is indistinguishable — and a missing/non-numeric {@code projectId} path value
 * yields HTTP 400 via Spring path-variable binding (Requirement 3.4). This is a bespoke read, not a
 * CRUD controller, so no {@code ProjectScopedService}/{@code getProjectIdPath()} surface is needed
 * and no new managed entity is introduced.
 *
 * <p>Requirements: 2.1, 2.2, 3.4, 4.1, 4.6, 7.2, 7.3
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@PermissionResource("PROJECTS")
public class ProjectReadinessController {

    private static final String READ = "READ";

    private final ProjectReadinessService projectReadinessService;

    /**
     * Reads the aggregated readiness of the given project (Requirement 4): the equal-weight overall
     * percentage, the rolled-up overall state, and the ordered per-area contributions for the
     * caller's Readable_Areas, each with its outstanding items. {@code PROJECTS} READ.
     *
     * @param projectId the project whose readiness is read
     * @return HTTP 200 with the {@link ProjectReadinessView}
     */
    @GetMapping("/{projectId}/readiness")
    @PermissionOperation(READ)
    public ResponseEntity<ProjectReadinessView> readiness(@PathVariable Long projectId) {
        return ResponseEntity.ok(ProjectReadinessView.from(projectReadinessService.getReadiness(projectId)));
    }
}
