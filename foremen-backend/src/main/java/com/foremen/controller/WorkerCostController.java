package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.workercost.WorkerCostReadModelDto;
import com.foremen.service.workercost.WorkerCostService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-10 (design §B2, R5) — the optional standalone worker-cost read endpoint. It exposes a single
 * {@code GET /api/projects/{projectId}/worker-costs} that returns the {@link WorkerCostReadModelDto} —
 * the per-{@code APPROVED}-report cost lines plus the per-worker and per-work-item aggregates —
 * delegating all derivation to {@link WorkerCostService#costForProject(Long)}. It is a read-only
 * projection; it changes no state and emits no notifications (R7.1).
 *
 * <h2>ABAC guarding (R5.1, R5.2)</h2>
 * The class carries a class-level {@link PermissionResource @PermissionResource("WORK_REPORTS")} and
 * the sole handler a method-level {@link PermissionOperation @PermissionOperation("READ")}, so the
 * {@code PermissionResolver} derives the {@code (WORK_REPORTS, READ)} pair the
 * {@code PermissionInterceptor} enforces at runtime (401 unauthenticated, 403 without the grant;
 * ADMIN bypass included) and the startup {@code PermissionAnnotationValidator} classifies the
 * controller COMPLETE (no half-annotation that would fail startup; entity-creation-rules step 3). The
 * worker cost figures ride the existing project-scoped {@code WORK_REPORTS} resource seeded by
 * FOR-06-01 (changeset 165) — <b>no new ABAC resource is introduced</b> (R5.1) — so they are readable
 * by the same roles that read reports (ADMIN / MANAGER / FOREMAN) plus FINANCIER, whose
 * {@code WORK_REPORTS} READ grant FOR-06-01 already seeds (changeset
 * {@code 165-seed-work-reports-permissions-financier}); this spec therefore adds <b>no</b> Liquibase
 * changeset (R5.4).
 *
 * <p>The endpoint lives under {@code /api/projects/{projectId}/worker-costs} because the read is a
 * cost projection <em>of a project</em>, which keeps the {@code WORK_REPORTS} gate honest. The
 * {@link WorkerCostService} is project-scoped ({@code getProjectIdPath()} resolving the backing
 * {@code WorkReport} to its owning project id), so the read is auto project-filtered and never returns
 * another project's reports (R5.3). A missing / non-numeric {@code projectId} path value yields HTTP
 * 400 via Spring path-variable binding; a project with no approved reports yields an empty read model
 * (empty lines + empty aggregates), not an error. This is a bespoke read, not a CRUD controller, so no
 * new managed entity is introduced.
 *
 * <p>Requirements: 5.1, 5.2, 5.4
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@PermissionResource("WORK_REPORTS")
public class WorkerCostController {

    private static final String READ = "READ";

    private final WorkerCostService workerCostService;

    /**
     * Reads the project-scoped worker-cost read model (R1, R3): per {@code APPROVED} report the
     * resolved worker type (+ base-tier fallback flag), the work-item rate, the reported quantity, the
     * unit {@code Worker_Service_Cost} and the total {@code Cost_Value}, plus the per-worker and
     * per-work-item aggregates (approved-only, excluding unavailable-cost lines). {@code WORK_REPORTS}
     * READ.
     *
     * @param projectId the project whose worker-cost read model to read
     * @return HTTP 200 with the {@link WorkerCostReadModelDto}
     */
    @GetMapping("/{projectId}/worker-costs")
    @PermissionOperation(READ)
    public ResponseEntity<WorkerCostReadModelDto> workerCosts(@PathVariable Long projectId) {
        return ResponseEntity.ok(workerCostService.costForProject(projectId));
    }
}
