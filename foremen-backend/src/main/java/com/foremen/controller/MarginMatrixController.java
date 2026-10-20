package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.execution.marginmatrix.MarginMatrixDto;
import com.foremen.service.execution.marginmatrix.MarginMatrixService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-06 (design §B3; R6.1, R6.2, R6.4) — REST controller for the <strong>margin matrix</strong>
 * (plan × fact work margin), exposed under {@code /api/projects/{projectId}/margin-matrix}. It is a
 * single read-only endpoint that returns the per-work-item plan-vs-fact margin read model assembled by
 * {@link MarginMatrixService#getMatrix(Long)} — the FOR-05 estimate (plan side) joined with the
 * FOR-06-01 approved work reports (labour fact) and the FOR-06-03/04 reconciled procurement lines
 * (material + indirect fact).
 *
 * <p>Like the sibling {@link MaterialsMatrixController} / {@link PurchasesController}, the matrix is a
 * bespoke read model rather than a generic JSON CRUD resource, so this controller does <b>not</b>
 * implement the {@link AdminController} CRUD contract. It wires the matrix service directly and returns
 * the service-layer {@link MarginMatrixDto} verbatim.
 *
 * <p><b>ABAC (entity-creation-rules step 3; R6.1, R6.2).</b> The class-level
 * {@link PermissionResource @PermissionResource("WORK_REPORTS")} declares the resource — the same
 * resource FOR-06-01 seeded; <b>no new ABAC resource is introduced</b>, <b>no migration is added</b>,
 * and the {@code WORK_REPORTS} matrix is unchanged. Because no endpoint is inherited from
 * {@link AdminController} (so no {@code @PermissionOperation} is inherited), the single in-scope handler
 * carries a method-level {@link RequiresPermission @RequiresPermission(... READ)}, which the
 * {@code PermissionResolver} treats as a complete declaration. This keeps the controller fully
 * annotated, so the startup {@code PermissionAnnotationValidator} classifies the handler
 * {@code COMPLETE} and no half-annotation can degrade the endpoint into an unguarded one.
 *
 * <p><b>Stage &amp; project scope (R6.3, R6.4).</b> The read is auto project-filtered through the
 * backing {@link MarginMatrixService}'s {@code ProjectScopedService} contract, so it never returns
 * another project's data; the {@code DRAFT}-stage hiding of the margin surface is the frontend tab
 * gate (the matrix read itself is lifecycle-independent). A caller with {@code WORK_REPORTS} READ on
 * an {@code ACTIVE}/{@code COMPLETED} project can read the matrix.
 *
 * <p><b>Endpoints.</b> Base path {@code /api/projects/{projectId}/margin-matrix}:
 * <ul>
 *   <li>{@code GET /} &rarr; {@code READ} — the project's full margin-matrix read model.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/margin-matrix")
@RequiredArgsConstructor
@PermissionResource("WORK_REPORTS")
public class MarginMatrixController {

    private static final String RESOURCE = "WORK_REPORTS";
    private static final String READ = "READ";

    private final MarginMatrixService service;

    /**
     * Returns the project's margin-matrix read model (R1, R6.4): the FOR-05 estimate work items (plan
     * side) joined with the FOR-06-01 approved work reports (labour fact) and the FOR-06-03/04 linked
     * purchases (material fact), folded per work item into planned vs reported revenue / cost / margin
     * (each report costed at its own worker-type tier, base-tier fallback for Uncategorized workers),
     * grouped by work category with per-category subtotals and a project-level overall dashboard
     * (labour + linked material + separate indirect-by-category sum). Resolves to
     * {@code WORK_REPORTS}/{@code READ}.
     *
     * @param projectId the project whose margin matrix to assemble (required)
     * @return the assembled {@link MarginMatrixDto}
     */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<MarginMatrixDto> getMatrix(@PathVariable Long projectId) {
        return ResponseEntity.ok(service.getMatrix(projectId));
    }
}
