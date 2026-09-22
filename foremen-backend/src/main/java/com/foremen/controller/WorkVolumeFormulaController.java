package com.foremen.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.WorkVolumeFormulaCreateRequest;
import com.foremen.controller.model.WorkVolumeFormulaCreateResponse;
import com.foremen.controller.model.WorkVolumeFormulaDtoExtendedModel;
import com.foremen.controller.model.WorkVolumeFormulaDtoModel;
import com.foremen.controller.model.WorkVolumeFormulaUpdateRequest;
import com.foremen.controller.model.WorkVolumeFormulaUpdateResponse;
import com.foremen.controller.model.mapper.WorkVolumeFormulaControllerMapper;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkVolumeFormulaService;
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;
import com.foremen.service.model.WorkVolumeFormulaServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for a work item's default volume formula (FOR-05-04, Requirement 2, task 18.3).
 *
 * <p>A thin {@link AdminController} implementation — mirroring the minimal
 * {@code WorkMaterialConsumptionController} shape — wiring the {@link WorkVolumeFormulaService},
 * the {@link WorkVolumeFormulaControllerMapper}, and the shared {@link AuditServiceMapper}. It
 * exposes ONLY the generic create/list/read/update/delete/count/metadata/i18n operations
 * inherited as {@code default} methods from {@link AdminController} — no bespoke endpoints.
 *
 * <p><b>ABAC.</b> {@code WorkVolumeFormula} is an owned extension of the work catalog, not a
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
@RequestMapping("/api/work-volume-formulas")
@RequiredArgsConstructor
@PermissionResource("WORK_CATALOG")
public class WorkVolumeFormulaController implements AdminController<
        WorkVolumeFormulaServiceModel,
        WorkVolumeFormulaServiceExtendedModel,
        WorkVolumeFormulaDtoModel,
        WorkVolumeFormulaDtoExtendedModel,
        WorkVolumeFormulaEntity,
        Long,
        WorkVolumeFormulaCreateRequest,
        WorkVolumeFormulaCreateResponse,
        WorkVolumeFormulaUpdateRequest,
        WorkVolumeFormulaUpdateResponse> {

    private final WorkVolumeFormulaService service;
    private final WorkVolumeFormulaControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkVolumeFormulaServiceModel, WorkVolumeFormulaServiceExtendedModel,
            WorkVolumeFormulaDtoModel, WorkVolumeFormulaDtoExtendedModel,
            WorkVolumeFormulaCreateRequest, WorkVolumeFormulaCreateResponse,
            WorkVolumeFormulaUpdateRequest, WorkVolumeFormulaUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkVolumeFormulaServiceModel, WorkVolumeFormulaServiceExtendedModel,
            WorkVolumeFormulaEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
