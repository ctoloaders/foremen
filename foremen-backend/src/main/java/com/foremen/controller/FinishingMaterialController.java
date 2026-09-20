package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.FinishingMaterialControllerMapper;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.FinishingMaterialService;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.service.model.FinishingMaterialServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD controller for the finishing-material vertical (FOR-04-18, task 2.3).
 *
 * <p>The sibling of {@code ConstructionMaterialController}, but structurally the minimal
 * {@code MaterialProducerController} shape: a thin {@link AdminController} implementation wiring the
 * {@link FinishingMaterialService}, the {@link FinishingMaterialControllerMapper} and the shared
 * {@link AuditServiceMapper}. It exposes ONLY the generic create/list/read/update/delete/count/
 * metadata/i18n operations inherited as {@code default} methods from {@link AdminController} — there
 * is NO computed price range and NO {@code /price-ranges} analog, so no custom endpoint is added
 * (Requirement 2.1).
 *
 * <p>The class-level {@link PermissionResource}{@code ("MATERIALS_FINISHING")} — whose value matches
 * the seeded resource {@code code} — combines with the {@code @PermissionOperation} declared on each
 * inherited CRUD {@code default} method to produce the {@code (resource, operation)} pair the
 * {@code PermissionInterceptor} enforces and the {@code PermissionAnnotationValidator} checks at
 * startup. Because every in-scope handler is inherited fully annotated and no bespoke handler is
 * added, the controller is fully annotated and passes startup validation (Requirements 5.4, 5.5).
 *
 * <p>Reference filters {@code category.id}/{@code material.id}/{@code type.id}/{@code producer.id}/
 * {@code unit.id}/{@code packages.id} require no extra code here: the inherited list operation
 * delegates to the FOR-04-01 {@code SpecificationBuilder}, which resolves dot-notation paths and
 * transparently turns a collection path (e.g. {@code packages.id}) into a JOIN with
 * {@code distinct} (Requirement 2.10).
 */
@RestController
@RequestMapping("/api/finishing-materials")
@RequiredArgsConstructor
@PermissionResource("MATERIALS_FINISHING")
public class FinishingMaterialController implements AdminController<
        FinishingMaterialServiceModel,
        FinishingMaterialServiceExtendedModel,
        FinishingMaterialDtoModel,
        FinishingMaterialDtoExtendedModel,
        FinishingMaterialEntity,
        Long,
        FinishingMaterialCreateRequest,
        FinishingMaterialCreateResponse,
        FinishingMaterialUpdateRequest,
        FinishingMaterialUpdateResponse> {

    private final FinishingMaterialService service;
    private final FinishingMaterialControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<FinishingMaterialServiceModel, FinishingMaterialServiceExtendedModel,
            FinishingMaterialDtoModel, FinishingMaterialDtoExtendedModel,
            FinishingMaterialCreateRequest, FinishingMaterialCreateResponse,
            FinishingMaterialUpdateRequest, FinishingMaterialUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<FinishingMaterialServiceModel, FinishingMaterialServiceExtendedModel,
            FinishingMaterialEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
