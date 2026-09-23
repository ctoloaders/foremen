package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.AssortmentPositionCreateRequest;
import com.foremen.controller.model.AssortmentPositionCreateResponse;
import com.foremen.controller.model.AssortmentPositionDtoExtendedModel;
import com.foremen.controller.model.AssortmentPositionDtoModel;
import com.foremen.controller.model.AssortmentPositionUpdateRequest;
import com.foremen.controller.model.AssortmentPositionUpdateResponse;
import com.foremen.controller.model.PackageAssortmentClearQtyRequest;
import com.foremen.controller.model.PackageAssortmentEditorResponse;
import com.foremen.controller.model.PackageAssortmentSaveRequest;
import com.foremen.controller.model.PackageZlM2Response;
import com.foremen.controller.model.mapper.AssortmentPositionControllerMapper;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.AssortmentPositionService;
import com.foremen.service.model.AssortmentPositionServiceExtendedModel;
import com.foremen.service.model.AssortmentPositionServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for {@link AssortmentPositionEntity} (FOR-05-04-UI assortment rework), plus the
 * bespoke single-package grouped editor and computed package zł/m² endpoints.
 *
 * <p>A thin {@link AdminController} implementation wiring the {@link AssortmentPositionService},
 * the {@link AssortmentPositionControllerMapper}, and the shared {@link AuditServiceMapper}. The
 * generic create/list/read/update/delete/count/metadata/i18n operations are inherited as
 * {@code default} methods from {@link AdminController}. Creating a position (POST) adds a GLOBAL
 * position that appears for all packages; deleting it (DELETE /{id}) cascades its per-package
 * price rows.
 *
 * <p><b>ABAC.</b> The class-level {@link PermissionResource}{@code ("PACKAGE_ASSORTMENT")}
 * combines with the {@code @PermissionOperation} declared on each inherited CRUD {@code default}
 * method to produce the {@code (resource, operation)} pair the {@code PermissionInterceptor}
 * enforces and the {@code PermissionAnnotationValidator} checks at startup. The three bespoke
 * handlers carry a method-level {@link RequiresPermission @RequiresPermission}, which takes
 * precedence over the class {@code @PermissionResource} + method {@code @PermissionOperation}
 * combination, keeping the controller fully annotated (no half-annotation).
 *
 * <p><b>Endpoints.</b> Base path {@code /api/assortment-positions} (renamed from the retired
 * {@code /api/assortment-line-items}). The bespoke endpoints keep the same path segments the
 * frontend already calls: {@code GET /package-editor?packageCode=},
 * {@code POST /package-save?packageCode=}, {@code GET /package-zl-m2?packageCode=}.
 */
@RestController
@RequestMapping("/api/assortment-positions")
@RequiredArgsConstructor
@PermissionResource("PACKAGE_ASSORTMENT")
public class AssortmentPositionController implements AdminController<
        AssortmentPositionServiceModel,
        AssortmentPositionServiceExtendedModel,
        AssortmentPositionDtoModel,
        AssortmentPositionDtoExtendedModel,
        AssortmentPositionEntity,
        Long,
        AssortmentPositionCreateRequest,
        AssortmentPositionCreateResponse,
        AssortmentPositionUpdateRequest,
        AssortmentPositionUpdateResponse> {

    private final AssortmentPositionService service;
    private final AssortmentPositionControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<AssortmentPositionServiceModel, AssortmentPositionServiceExtendedModel,
            AssortmentPositionDtoModel, AssortmentPositionDtoExtendedModel,
            AssortmentPositionCreateRequest, AssortmentPositionCreateResponse,
            AssortmentPositionUpdateRequest, AssortmentPositionUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<AssortmentPositionServiceModel, AssortmentPositionServiceExtendedModel,
            AssortmentPositionEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Computed package zł/m² price endpoint (Requirements 6.3, 6.4, 6.5, 6.8).
     *
     * <p>Delegates to {@link AssortmentPositionService#computePackageZlM2(String)}, which
     * recomputes the figure from the currently-persisted assortment data on every call — nothing
     * is cached or persisted. The returned value is the raw zł/m² price; the downstream
     * FOR-05-06 consumer is responsible for multiplying it by a project's floor area
     * (Requirement 6.8).
     *
     * @param packageCode the target {@code OfferPackage}'s {@code code}
     */
    @GetMapping("/package-zl-m2")
    @RequiresPermission(resource = "PACKAGE_ASSORTMENT", operation = "READ")
    public ResponseEntity<PackageZlM2Response> packageZlM2(
            @RequestParam(name = "packageCode") String packageCode) {
        return ResponseEntity.ok(new PackageZlM2Response(service.computePackageZlM2(packageCode)));
    }

    /**
     * Single-package grouped assortment editor read endpoint (FOR-05-04-UI). Returns the package
     * (code, name, persisted {@code zlM2}), the live min/avg/max TOTAL zł/m² band, and every
     * assortment group (sorted by {@code sortOrder} then name) with its {@code referenceQty}/
     * {@code referenceUnit} and ALL of the group's global positions (each carrying this package's
     * prices, null when no price row yet). Read-only. Guarded by a method-level
     * {@code @RequiresPermission(PACKAGE_ASSORTMENT, READ)}.
     *
     * @param packageCode the target {@code OfferPackage}'s {@code code}
     */
    @GetMapping("/package-editor")
    @RequiresPermission(resource = "PACKAGE_ASSORTMENT", operation = "READ")
    public ResponseEntity<PackageAssortmentEditorResponse> packageEditor(
            @RequestParam(name = "packageCode") String packageCode) {
        return ResponseEntity.ok(service.buildEditor(packageCode));
    }

    /**
     * Single-package grouped assortment editor save endpoint (FOR-05-04-UI). In one transaction:
     * updates each group's {@code referenceQty}/{@code referenceUnit}; upserts the min/avg/max
     * price rows of this package's positions; recomputes the package avg zł/m² and persists it into
     * {@code offer_packages.zl_m2}; and returns the refreshed editor response. Guarded by a
     * method-level {@code @RequiresPermission(PACKAGE_ASSORTMENT, UPDATE)}.
     *
     * @param packageCode the target {@code OfferPackage}'s {@code code}
     * @param request     the edited groups and positions
     */
    @PostMapping("/package-save")
    @RequiresPermission(resource = "PACKAGE_ASSORTMENT", operation = "UPDATE")
    public ResponseEntity<PackageAssortmentEditorResponse> packageSave(
            @RequestParam(name = "packageCode") String packageCode,
            @Valid @RequestBody PackageAssortmentSaveRequest request) {
        return ResponseEntity.ok(service.savePackage(packageCode, request));
    }

    /**
     * Clears ONE band's quantity override for a position under {@code packageCode} (FOR-05-04-UI):
     * sets the matching {@code assortment_position_prices.<band>_qty} to {@code NULL} in place,
     * recomputes the package MAX zł/m², and returns the refreshed editor. A dedicated, immediate
     * operation (not the batched save) because {@code 0} is now a legitimate stored override, so
     * only {@code null} can mean "not overridden". Guarded by a method-level
     * {@code @RequiresPermission(PACKAGE_ASSORTMENT, UPDATE)}.
     *
     * @param packageCode the target {@code OfferPackage} code
     * @param request     the position id + the band to clear ({@code min}/{@code avg}/{@code max})
     */
    @PostMapping("/package-clear-qty")
    @RequiresPermission(resource = "PACKAGE_ASSORTMENT", operation = "UPDATE")
    public ResponseEntity<PackageAssortmentEditorResponse> packageClearQty(
            @RequestParam(name = "packageCode") String packageCode,
            @Valid @RequestBody PackageAssortmentClearQtyRequest request) {
        return ResponseEntity.ok(
                service.clearPositionQty(packageCode, request.positionId(), request.band()));
    }
}
