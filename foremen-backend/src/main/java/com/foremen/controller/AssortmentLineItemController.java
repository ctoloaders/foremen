package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.AssortmentLineItemCreateRequest;
import com.foremen.controller.model.AssortmentLineItemCreateResponse;
import com.foremen.controller.model.AssortmentLineItemDtoExtendedModel;
import com.foremen.controller.model.AssortmentLineItemDtoModel;
import com.foremen.controller.model.AssortmentLineItemUpdateRequest;
import com.foremen.controller.model.AssortmentLineItemUpdateResponse;
import com.foremen.controller.model.PackageZlM2Response;
import com.foremen.controller.model.mapper.AssortmentLineItemControllerMapper;
import com.foremen.dao.model.AssortmentLineItemEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.AssortmentLineItemService;
import com.foremen.service.model.AssortmentLineItemServiceExtendedModel;
import com.foremen.service.model.AssortmentLineItemServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for {@link AssortmentLineItemEntity} (FOR-05-04, Requirement 6.1, 6.2, task
 * 18.2), plus a bespoke read-only endpoint exposing the computed package zł/m² price
 * (Requirement 6.3, 6.4, 6.5, 6.8).
 *
 * <p>A thin {@link AdminController} implementation — mirroring the minimal
 * {@code WorkVolumeFormulaController} shape — wiring the {@link AssortmentLineItemService}, the
 * {@link AssortmentLineItemControllerMapper}, and the shared {@link AuditServiceMapper}. The
 * generic create/list/read/update/delete/count/metadata/i18n operations are inherited as
 * {@code default} methods from {@link AdminController}, unmodified.
 *
 * <p><b>ABAC.</b> The class-level {@link PermissionResource}{@code ("PACKAGE_ASSORTMENT")}
 * combines with the {@code @PermissionOperation} declared on each inherited CRUD {@code default}
 * method to produce the {@code (resource, operation)} pair the {@code PermissionInterceptor}
 * enforces and the {@code PermissionAnnotationValidator} checks at startup. {@code
 * PACKAGE_ASSORTMENT} matches the resource code seeded by
 * {@code 089-seed-package-assortment-resource.xml} exactly.
 *
 * <p><b>{@link #packageZlM2(String)}.</b> Not one of the inherited CRUD {@code default} methods,
 * so it carries no automatic {@code @PermissionOperation} — it is guarded by a method-level
 * {@link RequiresPermission @RequiresPermission(resource = "PACKAGE_ASSORTMENT", operation =
 * "READ")}, which takes precedence over the class {@code @PermissionResource} + method
 * {@code @PermissionOperation} combination for this handler (mirroring
 * {@code ConstructionMaterialController#priceRanges} / {@code EstimateController
 * #getOrCreateForProject}), keeping the controller fully annotated for
 * {@code PermissionAnnotationValidator} (no half-annotation).
 */
@RestController
@RequestMapping("/api/assortment-line-items")
@RequiredArgsConstructor
@PermissionResource("PACKAGE_ASSORTMENT")
public class AssortmentLineItemController implements AdminController<
        AssortmentLineItemServiceModel,
        AssortmentLineItemServiceExtendedModel,
        AssortmentLineItemDtoModel,
        AssortmentLineItemDtoExtendedModel,
        AssortmentLineItemEntity,
        Long,
        AssortmentLineItemCreateRequest,
        AssortmentLineItemCreateResponse,
        AssortmentLineItemUpdateRequest,
        AssortmentLineItemUpdateResponse> {

    private final AssortmentLineItemService service;
    private final AssortmentLineItemControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<AssortmentLineItemServiceModel, AssortmentLineItemServiceExtendedModel,
            AssortmentLineItemDtoModel, AssortmentLineItemDtoExtendedModel,
            AssortmentLineItemCreateRequest, AssortmentLineItemCreateResponse,
            AssortmentLineItemUpdateRequest, AssortmentLineItemUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<AssortmentLineItemServiceModel, AssortmentLineItemServiceExtendedModel,
            AssortmentLineItemEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Computed package zł/m² price endpoint (Requirements 6.3, 6.4, 6.5, 6.8).
     *
     * <p>Delegates to {@link AssortmentLineItemService#computePackageZlM2(String)}, which
     * recomputes the figure from the currently-persisted assortment data on every call — nothing
     * is cached or persisted. The returned value is the raw zł/m² price; the downstream
     * FOR-05-06 consumer is responsible for multiplying it by a project's floor area
     * (Requirement 6.8), which this endpoint does not do.
     *
     * @param packageCode the target {@code OfferPackage}'s {@code code} (e.g. {@code "budget"},
     *                    {@code "norm"}, {@code "lux"})
     */
    @GetMapping("/package-zl-m2")
    @RequiresPermission(resource = "PACKAGE_ASSORTMENT", operation = "READ")
    public ResponseEntity<PackageZlM2Response> packageZlM2(
            @RequestParam(name = "packageCode") String packageCode) {
        return ResponseEntity.ok(new PackageZlM2Response(service.computePackageZlM2(packageCode)));
    }
}
