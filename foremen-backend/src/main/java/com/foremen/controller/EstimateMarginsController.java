package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.estimate.margins.MarginsListDto;
import com.foremen.service.estimate.margins.MarginsListService;

import lombok.RequiredArgsConstructor;

/**
 * The Margins tab controller (FOR-05-06, design §B5) — the tab's read-only cost/margin model, layered
 * on the estimate vertical's {@code ESTIMATE} ABAC resource (shipped by FOR-05-03; no new tab
 * resource, operation, or role grant is introduced here). Mirrors {@link EstimateMaterialsController}:
 * a second controller on the same {@code /api/estimates} base path and the same {@code ESTIMATE}
 * resource as {@link EstimateMatrixController} — {@link PermissionResource @PermissionResource} is
 * per-class, so several controllers may share the resource.
 *
 * <h2>ABAC guarding</h2>
 * The class carries {@link PermissionResource @PermissionResource("ESTIMATE")}; its single handler
 * carries a method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the
 * class default, so the controller is <b>fully annotated</b> and {@code PermissionAnnotationValidator}
 * classifies it COMPLETE at startup:
 * <ul>
 *   <li>{@code GET /project/{projectId}/margins} — the margins read model, {@code ESTIMATE} READ.</li>
 * </ul>
 *
 * <p>The tab is read-only over the estimate/costs (R4.1); there is no write endpoint. A pre-estimate
 * project returns an empty structure (not {@code 404}) because the service reuses the kosztorys
 * get-or-create path.
 */
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateMarginsController {

    private static final String ESTIMATE_RESOURCE = "ESTIMATE";

    private final MarginsListService marginsListService;

    /**
     * The Margins tab read model for a project (R4.1, R5.1): the works&times;cost matrix grouped by
     * work type (offer, per-tier costs, labour min/avg/max profit, per-branch material retail/cost/
     * margin) plus the per-tier + per-branch cost dashboard. Read-only and independent of the
     * estimate's writable state. {@code ESTIMATE} READ.
     *
     * @param projectId the owning project whose margins to render
     * @return the assembled {@link MarginsListDto}
     */
    @GetMapping("/project/{projectId}/margins")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = "READ")
    public ResponseEntity<MarginsListDto> getMargins(@PathVariable Long projectId) {
        return ResponseEntity.ok(marginsListService.getMargins(projectId));
    }
}
