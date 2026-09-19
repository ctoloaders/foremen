package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkMaterialConsumptionControllerMapper;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkMaterialConsumptionService;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.WorkMaterialConsumptionServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD controller for the work-material-consumption vertical (FOR-04-19, task 3.4).
 *
 * <p>A thin {@link AdminController} implementation — mirroring the minimal
 * {@code FinishingMaterialController} shape — wiring the {@link WorkMaterialConsumptionService}, the
 * {@link WorkMaterialConsumptionControllerMapper} and the shared {@link AuditServiceMapper}. It
 * exposes ONLY the generic create/list/read/update/delete/count/metadata/i18n operations inherited as
 * {@code default} methods from {@link AdminController}. There are NO custom endpoints — the
 * work-catalog drill-in rides the generic list handler via the FOR-04-01 query DSL
 * ({@code workItem.id==} AND {@code offerPackage.id==} with a large {@code size}), so no bespoke
 * handler is added (Requirements 3.1, 5.5).
 *
 * <p>The class-level {@link PermissionResource}{@code ("WORK_MATERIAL_CONSUMPTION")} — whose value
 * matches the seeded resource {@code code} (changeset 068) — combines with the
 * {@code @PermissionOperation} declared on each inherited CRUD {@code default} method to produce the
 * {@code (resource, operation)} pair the {@code PermissionInterceptor} enforces and the
 * {@code PermissionAnnotationValidator} checks at startup. Because every in-scope handler is inherited
 * fully annotated and no bespoke handler is added, the controller is fully annotated and passes
 * startup validation (Requirements 6.4, 6.5).
 *
 * <p>Reference filters {@code workItem.id}/{@code offerPackage.id}/{@code materialUnit.id}/
 * {@code constructionMaterialType.id}/{@code finishingMaterialType.id} (reference paths) and
 * {@code branch} (scalar enum path) require no extra code here: the inherited list operation
 * delegates to the FOR-04-01 {@code SpecificationBuilder}, which resolves dot-notation reference
 * paths and scalar enum paths transparently — no custom resolver is needed (Requirement 3.7).
 */
@RestController
@RequestMapping("/api/work-material-consumptions")
@RequiredArgsConstructor
@PermissionResource("WORK_MATERIAL_CONSUMPTION")
public class WorkMaterialConsumptionController implements AdminController<
        WorkMaterialConsumptionServiceModel,
        WorkMaterialConsumptionServiceExtendedModel,
        WorkMaterialConsumptionDtoModel,
        WorkMaterialConsumptionDtoExtendedModel,
        WorkMaterialConsumptionEntity,
        Long,
        WorkMaterialConsumptionCreateRequest,
        WorkMaterialConsumptionCreateResponse,
        WorkMaterialConsumptionUpdateRequest,
        WorkMaterialConsumptionUpdateResponse> {

    private final WorkMaterialConsumptionService service;
    private final WorkMaterialConsumptionControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkMaterialConsumptionServiceModel, WorkMaterialConsumptionServiceExtendedModel,
            WorkMaterialConsumptionDtoModel, WorkMaterialConsumptionDtoExtendedModel,
            WorkMaterialConsumptionCreateRequest, WorkMaterialConsumptionCreateResponse,
            WorkMaterialConsumptionUpdateRequest, WorkMaterialConsumptionUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkMaterialConsumptionServiceModel, WorkMaterialConsumptionServiceExtendedModel,
            WorkMaterialConsumptionEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
