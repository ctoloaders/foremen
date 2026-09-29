package com.foremen.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.materials.MaterialsListDto;
import com.foremen.service.estimate.materials.MaterialsListService;
import com.foremen.service.estimate.materials.MaterialsReserveRequest;
import com.foremen.service.permission.ForemenPermissionEvaluator;

import lombok.RequiredArgsConstructor;

/**
 * The Materials tab controller (FOR-05-05b, design §B4) — the tab's read model plus the single
 * reserve-map write endpoint, layered on the estimate vertical's {@code ESTIMATE} ABAC resource
 * (shipped by FOR-05-03; no new resource, operation, or role grant is introduced here, R8.4).
 *
 * <p>This is a <b>second</b> controller on the same {@code /api/estimates} base path and the same
 * {@code ESTIMATE} resource as {@link EstimateMatrixController}; {@link PermissionResource
 * @PermissionResource} is per-class, so two controllers may share the resource. Keeping the Materials
 * handlers here (rather than growing the matrix controller) keeps each controller focused.
 *
 * <h2>ABAC guarding (R8.1, R8.3, R8.4)</h2>
 * The class carries {@link PermissionResource @PermissionResource("ESTIMATE")}; every handler carries
 * a method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the class
 * default (mirroring {@link EstimateMatrixController}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup:
 * <ul>
 *   <li>{@code GET /project/{projectId}/materials} — the materials read model, {@code ESTIMATE}
 *       READ (R8.1).</li>
 *   <li>{@code PUT /project/{projectId}/materials/reserve} — the single reserve-map write,
 *       {@code ESTIMATE} UPDATE (R8.3). The DRAFT-lifecycle gate is enforced in the service write
 *       path (R10.2).</li>
 * </ul>
 *
 * <h2>{@code editable} flag (R8.2, R10.3)</h2>
 * The returned {@link MaterialsListDto} carries {@code editable = project DRAFT ∧ caller has ESTIMATE
 * UPDATE}, resolved exactly like {@link EstimateMatrixController#resolveEditable}: the lifecycle half
 * by {@link MaterialsListService#isDraft(Long)} and the permission half by the
 * {@link ForemenPermissionEvaluator} against the caller's role (ADMIN bypass included). The read is
 * independent of the lifecycle (R10.3) — the tab always renders; only the reserve edits are gated by
 * the {@code editable} flag (client) and the service DRAFT gate (server).
 */
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateMaterialsController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ESTIMATE_RESOURCE = "ESTIMATE";
    private static final String UPDATE_OPERATION = "UPDATE";

    private final MaterialsListService materialsListService;
    private final ForemenPermissionEvaluator permissionEvaluator;

    /**
     * The Materials tab read model for a project (R8.1, R10.3): the materials&times;rooms matrix, the
     * per-branch + grand dashboard, and the fulfilment percentage. Read-only and independent of the
     * estimate's writable state. {@code ESTIMATE} READ. The returned {@code editable} flag folds the
     * caller's {@code ESTIMATE} UPDATE grant with the project's DRAFT lifecycle (R8.2).
     */
    @GetMapping("/project/{projectId}/materials")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = "READ")
    public ResponseEntity<MaterialsListDto> getMaterials(@PathVariable Long projectId) {
        return ResponseEntity.ok(materialsListService.getMaterials(projectId, resolveEditable(projectId)));
    }

    /**
     * Persists the project's per-material reserve map and returns the refreshed read model (R8.3,
     * R10.2). The body is the client-expanded per-material list ({@code { entries: [{ materialId,
     * percent }] }}, the mass-apply convenience already expanded client-side, R4.4). {@code ESTIMATE}
     * UPDATE — the grant is server-enforced here; the DRAFT gate and the per-entry bounds validation
     * are enforced in {@link MaterialsListService#saveReserveMap} (409 {@code error.estimate.locked}
     * when not DRAFT, 400 {@code error.estimate.reserve.invalid} on any invalid entry).
     */
    @PutMapping("/project/{projectId}/materials/reserve")
    @RequiresPermission(resource = ESTIMATE_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<MaterialsListDto> saveReserve(
            @PathVariable Long projectId, @RequestBody MaterialsReserveRequest request) {
        return ResponseEntity.ok(materialsListService.saveReserveMap(projectId, request));
    }

    /**
     * The {@code editable} flag (R8.2, R10.3): {@code true} iff the project's estimate is DRAFT AND the
     * caller holds {@code ESTIMATE} UPDATE (ADMIN bypass included). Resolved exactly like
     * {@link EstimateMatrixController#resolveEditable}. Reaching the READ handler already proves an
     * authenticated principal, so a missing role code here is defensive only.
     */
    private boolean resolveEditable(Long projectId) {
        String roleCode = currentRoleCode();
        if (roleCode == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        boolean canUpdate = permissionEvaluator.isAllowed(roleCode, ESTIMATE_RESOURCE, UPDATE_OPERATION);
        return canUpdate && materialsListService.isDraft(projectId);
    }

    /**
     * The authenticated caller's role code, read from the {@code ROLE_<code>} authority in the
     * security context, or {@code null} when there is no authenticated principal. Mirrors the
     * extraction used by {@link EstimateMatrixController} / {@code PermissionInterceptor}.
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
