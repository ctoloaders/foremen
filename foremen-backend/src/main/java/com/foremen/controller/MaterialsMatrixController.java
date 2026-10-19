package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.procurement.matrix.MaterialsMatrixDto;
import com.foremen.service.procurement.matrix.MaterialsMatrixService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-05 (design §B3; R6.1, R6.2, R6.4) — REST controller for the <strong>materials matrix</strong>
 * (plan × fact), exposed under {@code /api/projects/{projectId}/materials-matrix}. It is a single
 * read-only endpoint that returns the money-based % выборки read model assembled by
 * {@link MaterialsMatrixService#getMatrix(Long)} — the FOR-05-05b planned materials (plan side) joined
 * with the FOR-06-04 reconciled procurement lines (fact side).
 *
 * <p>Like the sibling {@link PurchasesController} / {@link ProcurementDocumentController}, the matrix
 * is a bespoke read model rather than a generic JSON CRUD resource, so this controller does
 * <b>not</b> implement the {@link AdminController} CRUD contract. It wires the matrix service directly
 * and returns the service-layer {@link MaterialsMatrixDto} verbatim (mirroring the FOR-05-05b
 * {@code EstimateMaterialsController}, which returns its read-model DTO directly).
 *
 * <p><b>ABAC (entity-creation-rules step 3; R6.1, R6.2).</b> The class-level
 * {@link PermissionResource @PermissionResource("PURCHASES")} declares the resource — the same
 * resource FOR-06-03 seeded (changeset 169); <b>no new ABAC resource is introduced</b>, <b>no
 * migration is added</b>, and the {@code PURCHASES} matrix is unchanged. Because no endpoint is
 * inherited from {@link AdminController} (so no {@code @PermissionOperation} is inherited), the single
 * in-scope handler carries a method-level {@link RequiresPermission @RequiresPermission(... READ)},
 * which the {@code PermissionResolver} treats as a complete declaration. This keeps the controller
 * fully annotated, so the startup {@code PermissionAnnotationValidator} classifies the handler
 * {@code COMPLETE} and no half-annotation can degrade the endpoint into an unguarded one.
 *
 * <p><b>Stage &amp; project scope (R6.3, R6.4).</b> The read is auto project-filtered through the
 * backing {@link MaterialsMatrixService}'s {@code ProjectScopedService} contract, so it never returns
 * another project's data; the {@code DRAFT}-stage hiding of the materials surface is the frontend
 * tab gate (the matrix read itself is lifecycle-independent). A caller with {@code PURCHASES} READ on
 * an {@code ACTIVE}/{@code COMPLETED} project can read the matrix.
 *
 * <p><b>Endpoints.</b> Base path {@code /api/projects/{projectId}/materials-matrix}:
 * <ul>
 *   <li>{@code GET /} &rarr; {@code READ} — the project's full materials-matrix read model.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/materials-matrix")
@RequiredArgsConstructor
@PermissionResource("PURCHASES")
public class MaterialsMatrixController {

    private static final String RESOURCE = "PURCHASES";
    private static final String READ = "READ";

    private final MaterialsMatrixService service;

    /**
     * Returns the project's materials-matrix read model (R1, R6.4): the FOR-05-05b planned materials
     * joined with the FOR-06-04 reconciled procurement lines, folded per planned material into the
     * money-based % выборки (analogs aggregated across all tiers, uncapped over-purchase, unit-aware
     * quantity), grouped by branch → category with per-branch and project-wide dashboard totals.
     * Resolves to {@code PURCHASES}/{@code READ}.
     *
     * @param projectId the project whose materials matrix to assemble (required)
     * @return the assembled {@link MaterialsMatrixDto}
     */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<MaterialsMatrixDto> getMatrix(@PathVariable Long projectId) {
        return ResponseEntity.ok(service.getMatrix(projectId));
    }
}
