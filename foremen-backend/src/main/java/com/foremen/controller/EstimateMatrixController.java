package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.ApplyAssignmentsRequest;
import com.foremen.controller.model.ApplyPackageRequest;
import com.foremen.controller.model.ApplyWorkRequest;
import com.foremen.controller.model.RecomputeFinishingRequest;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Estimate tab matrix controller (FOR-05-05, design §B6) — the read model plus the matrix
 * write / calculate endpoints, layered on the estimate vertical's {@code ESTIMATE} ABAC resource
 * (shipped by FOR-05-03; no new resource is introduced here).
 *
 * <h2>ABAC guarding (R19.1)</h2>
 * The class carries {@link PermissionResource @PermissionResource("ESTIMATE")}; every handler carries
 * a method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the class
 * default (mirroring {@code EstimateController#getOrCreateForProject} and
 * {@code ConstructionMaterialController#priceRanges}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup:
 * <ul>
 *   <li>{@code GET /project/{projectId}/matrix} — the full read model, {@code ESTIMATE} READ.</li>
 *   <li>{@code POST /project/{projectId}/apply-package}, {@code .../apply-work},
 *       {@code .../recompute-finishing} — the <b>read-only calculate/preview</b> endpoints. They
 *       compute and return the resulting matrix <b>without persisting anything</b>, so they are
 *       {@code ESTIMATE} READ (POST only because they carry a body, R15.6). The client stages the
 *       returned matrix as undoable edits, written to the server only on Save.</li>
 *   <li>{@code POST /project/{projectId}/assignments} — the single batched Save that persists the
 *       whole staged set (cell edits AND apply/recompute-originated edits), {@code ESTIMATE}
 *       UPDATE.</li>
 * </ul>
 *
 * <h2>{@code editable} flag (R15.5)</h2>
 * Every returned {@link EstimateMatrixDto} carries {@code editable = project DRAFT ∧ caller has
 * ESTIMATE UPDATE}. The lifecycle half is resolved by {@link EstimateAssignmentService#isDraft} and
 * the permission half by the {@link ForemenPermissionEvaluator} against the caller's role (ADMIN
 * bypass included), mirroring the programmatic evaluation done by {@code ImageController}. The Save
 * endpoint always returns {@code editable = true} (it is only reachable for an UPDATE caller on a
 * DRAFT estimate).
 */
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateMatrixController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ESTIMATE_RESOURCE = "ESTIMATE";
    private static final String UPDATE_OPERATION = "UPDATE";

    private final EstimateAssignmentService estimateAssignmentService;
    private final ForemenPermissionEvaluator permissionEvaluator;

    /**
     * The full Estimate matrix read model for a project (R1.1, R1.2, R2.1, R14): works grouped by
     * type, room columns, per-cell assignment + cost range + fill state, header totals, and the fill
     * indicator. {@code ESTIMATE} READ.
     */
    @GetMapping("/project/{projectId}/matrix")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = "READ")
    public ResponseEntity<EstimateMatrixDto> getMatrix(@PathVariable Long projectId) {
        return ResponseEntity.ok(estimateAssignmentService.getMatrix(projectId, resolveEditable(projectId)));
    }

    /**
     * Read-only Apply_Package calculate/preview (R11.2, R15.6): computes the resulting matrix under
     * the requested package and returns it <b>without persisting anything</b>. {@code ESTIMATE} READ.
     */
    @PostMapping("/project/{projectId}/apply-package")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = "READ")
    public ResponseEntity<EstimateMatrixDto> applyPackage(
            @PathVariable Long projectId, @Valid @RequestBody ApplyPackageRequest request) {
        EstimateMatrixDto preview = estimateAssignmentService.previewApplyPackage(
                projectId, request.packageCode(), resolveEditable(projectId));
        return ResponseEntity.ok(preview);
    }

    /**
     * Read-only work-row apply (hammer) calculate/preview (R9.5, R15.6): computes the resulting matrix
     * for the one work over its Room_Type_Attachment and returns it <b>without persisting anything</b>.
     * {@code ESTIMATE} READ.
     */
    @PostMapping("/project/{projectId}/apply-work")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = "READ")
    public ResponseEntity<EstimateMatrixDto> applyWork(
            @PathVariable Long projectId, @Valid @RequestBody ApplyWorkRequest request) {
        EstimateMatrixDto preview = estimateAssignmentService.previewApplyWork(
                projectId, request.workItemId(), request.packageCode(), resolveEditable(projectId));
        return ResponseEntity.ok(preview);
    }

    /**
     * Read-only Recompute_Finishing_Prices calculate/preview (R12.2, R15.6): recomputes the finishing
     * ranges for already-assigned finishing lines only under the requested package and returns the
     * resulting matrix <b>without persisting anything</b>. {@code ESTIMATE} READ.
     */
    @PostMapping("/project/{projectId}/recompute-finishing")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = "READ")
    public ResponseEntity<EstimateMatrixDto> recomputeFinishing(
            @PathVariable Long projectId, @Valid @RequestBody RecomputeFinishingRequest request) {
        EstimateMatrixDto preview = estimateAssignmentService.previewRecomputeFinishing(
                projectId, request.packageCode(), resolveEditable(projectId));
        return ResponseEntity.ok(preview);
    }

    /**
     * The single batched Save (R15.2, R15.7): persists the whole staged set — cell edits AND
     * apply/recompute-originated staged edits — in one request and returns the refreshed matrix read
     * model. {@code ESTIMATE} UPDATE. The DRAFT-lifecycle gate is enforced in the service write path.
     */
    @PostMapping("/project/{projectId}/assignments")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<EstimateMatrixDto> saveAssignments(
            @PathVariable Long projectId, @Valid @RequestBody ApplyAssignmentsRequest request) {
        EstimateMatrixDto matrix = estimateAssignmentService.saveAndAssemble(projectId, request.toStagedEdits());
        return ResponseEntity.ok(matrix);
    }

    /**
     * The {@code editable} flag (R15.5): {@code true} iff the project's estimate is DRAFT AND the
     * caller holds {@code ESTIMATE} UPDATE (ADMIN bypass included). Reaching a READ handler already
     * proves an authenticated principal, so a missing role code here is defensive only.
     */
    private boolean resolveEditable(Long projectId) {
        String roleCode = currentRoleCode();
        if (roleCode == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        boolean canUpdate = permissionEvaluator.isAllowed(roleCode, ESTIMATE_RESOURCE, UPDATE_OPERATION);
        return canUpdate && estimateAssignmentService.isDraft(projectId);
    }

    /**
     * The authenticated caller's role code, read from the {@code ROLE_<code>} authority in the
     * security context, or {@code null} when there is no authenticated principal. Mirrors the
     * extraction used by {@code PermissionInterceptor} / {@code ImageController}.
     */
    private String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }
}
