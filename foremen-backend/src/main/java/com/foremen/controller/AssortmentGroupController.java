package com.foremen.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.AssortmentGroupCreateRequest;
import com.foremen.controller.model.AssortmentGroupCreateResponse;
import com.foremen.controller.model.AssortmentGroupDtoExtendedModel;
import com.foremen.controller.model.AssortmentGroupDtoModel;
import com.foremen.controller.model.AssortmentGroupUpdateRequest;
import com.foremen.controller.model.AssortmentGroupUpdateResponse;
import com.foremen.controller.model.mapper.AssortmentGroupControllerMapper;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.AssortmentGroupService;
import com.foremen.service.model.AssortmentGroupServiceExtendedModel;
import com.foremen.service.model.AssortmentGroupServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for {@link AssortmentGroupEntity} (FOR-05-04, Requirement 6.1, task 18.2).
 *
 * <p>A thin {@link AdminController} implementation — mirroring the minimal
 * {@code WorkVolumeFormulaController} shape — wiring the {@link AssortmentGroupService}, the
 * {@link AssortmentGroupControllerMapper}, and the shared {@link AuditServiceMapper}. It exposes
 * ONLY the generic create/list/read/update/delete/count/metadata/i18n operations inherited as
 * {@code default} methods from {@link AdminController} — no bespoke endpoints.
 *
 * <p><b>ABAC.</b> The class-level {@link PermissionResource}{@code ("PACKAGE_ASSORTMENT")}
 * combines with the {@code @PermissionOperation} declared on each inherited CRUD {@code default}
 * method to produce the {@code (resource, operation)} pair the {@code PermissionInterceptor}
 * enforces and the {@code PermissionAnnotationValidator} checks at startup. {@code
 * PACKAGE_ASSORTMENT} matches the resource code seeded by
 * {@code 089-seed-package-assortment-resource.xml} exactly. No half-annotation: every in-scope
 * handler is inherited fully annotated and no bespoke handler is added.
 */
@RestController
@RequestMapping("/api/assortment-groups")
@RequiredArgsConstructor
@PermissionResource("PACKAGE_ASSORTMENT")
public class AssortmentGroupController implements AdminController<
        AssortmentGroupServiceModel,
        AssortmentGroupServiceExtendedModel,
        AssortmentGroupDtoModel,
        AssortmentGroupDtoExtendedModel,
        AssortmentGroupEntity,
        Long,
        AssortmentGroupCreateRequest,
        AssortmentGroupCreateResponse,
        AssortmentGroupUpdateRequest,
        AssortmentGroupUpdateResponse> {

    private final AssortmentGroupService service;
    private final AssortmentGroupControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<AssortmentGroupServiceModel, AssortmentGroupServiceExtendedModel,
            AssortmentGroupDtoModel, AssortmentGroupDtoExtendedModel,
            AssortmentGroupCreateRequest, AssortmentGroupCreateResponse,
            AssortmentGroupUpdateRequest, AssortmentGroupUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<AssortmentGroupServiceModel, AssortmentGroupServiceExtendedModel,
            AssortmentGroupEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
