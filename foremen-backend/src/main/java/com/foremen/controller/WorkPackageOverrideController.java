package com.foremen.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.WorkPackageOverrideCreateRequest;
import com.foremen.controller.model.WorkPackageOverrideCreateResponse;
import com.foremen.controller.model.WorkPackageOverrideDtoExtendedModel;
import com.foremen.controller.model.WorkPackageOverrideDtoModel;
import com.foremen.controller.model.WorkPackageOverrideUpdateRequest;
import com.foremen.controller.model.WorkPackageOverrideUpdateResponse;
import com.foremen.controller.model.mapper.WorkPackageOverrideControllerMapper;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkPackageOverrideService;
import com.foremen.service.model.WorkPackageOverrideServiceExtendedModel;
import com.foremen.service.model.WorkPackageOverrideServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for a {@code (WorkItem, OfferPackage)} package membership + override formula
 * pair (FOR-05-04, Requirement 4, task 18.3).
 *
 * <p>A thin {@link AdminController} implementation — mirroring the minimal
 * {@code WorkMaterialConsumptionController} shape — wiring the {@link WorkPackageOverrideService},
 * the {@link WorkPackageOverrideControllerMapper}, and the shared {@link AuditServiceMapper}. It
 * exposes ONLY the generic create/list/read/update/delete/count/metadata/i18n operations
 * inherited as {@code default} methods from {@link AdminController} — no bespoke endpoints.
 *
 * <p><b>ABAC.</b> {@code WorkPackageOverride} is an owned extension of the work catalog, not a
 * standalone resource (design §ABAC) — it is exposed under the existing seeded
 * {@code WORK_CATALOG} resource (the actual code seeded by
 * {@code 037-seed-work-catalog-resource.xml} and used by {@link WorkItemController}; the task
 * text's literal {@code "WORK_ITEMS"} does not match any seeded resource and was NOT used, to
 * avoid a {@code PermissionAnnotationValidator}/startup failure from an unseeded resource code).
 * The class-level {@link PermissionResource}{@code ("WORK_CATALOG")} combines with the
 * {@code @PermissionOperation} declared on each inherited CRUD {@code default} method to produce
 * the {@code (resource, operation)} pair the {@code PermissionInterceptor} enforces and the
 * {@code PermissionAnnotationValidator} checks at startup. No half-annotation: every in-scope
 * handler is inherited fully annotated and no bespoke handler is added.
 */
@RestController
@RequestMapping("/api/work-package-overrides")
@RequiredArgsConstructor
@PermissionResource("WORK_CATALOG")
public class WorkPackageOverrideController implements AdminController<
        WorkPackageOverrideServiceModel,
        WorkPackageOverrideServiceExtendedModel,
        WorkPackageOverrideDtoModel,
        WorkPackageOverrideDtoExtendedModel,
        WorkPackageOverrideEntity,
        Long,
        WorkPackageOverrideCreateRequest,
        WorkPackageOverrideCreateResponse,
        WorkPackageOverrideUpdateRequest,
        WorkPackageOverrideUpdateResponse> {

    private final WorkPackageOverrideService service;
    private final WorkPackageOverrideControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkPackageOverrideServiceModel, WorkPackageOverrideServiceExtendedModel,
            WorkPackageOverrideDtoModel, WorkPackageOverrideDtoExtendedModel,
            WorkPackageOverrideCreateRequest, WorkPackageOverrideCreateResponse,
            WorkPackageOverrideUpdateRequest, WorkPackageOverrideUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkPackageOverrideServiceModel, WorkPackageOverrideServiceExtendedModel,
            WorkPackageOverrideEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
