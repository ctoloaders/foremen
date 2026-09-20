package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.ConstructionMaterialTypeControllerMapper;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ConstructionMaterialTypeService;
import com.foremen.service.model.ConstructionMaterialTypeServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialTypeServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/construction-material-types")
@RequiredArgsConstructor
@PermissionResource("CONSTRUCTION_MATERIAL_TYPES")
public class ConstructionMaterialTypeController implements AdminController<
        ConstructionMaterialTypeServiceModel,
        ConstructionMaterialTypeServiceExtendedModel,
        ConstructionMaterialTypeDtoModel,
        ConstructionMaterialTypeDtoExtendedModel,
        ConstructionMaterialTypeEntity,
        Long,
        ConstructionMaterialTypeCreateRequest,
        ConstructionMaterialTypeCreateResponse,
        ConstructionMaterialTypeUpdateRequest,
        ConstructionMaterialTypeUpdateResponse> {

    private final ConstructionMaterialTypeService service;
    private final ConstructionMaterialTypeControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<ConstructionMaterialTypeServiceModel,
            ConstructionMaterialTypeServiceExtendedModel,
            ConstructionMaterialTypeDtoModel, ConstructionMaterialTypeDtoExtendedModel,
            ConstructionMaterialTypeCreateRequest, ConstructionMaterialTypeCreateResponse,
            ConstructionMaterialTypeUpdateRequest, ConstructionMaterialTypeUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<ConstructionMaterialTypeServiceModel, ConstructionMaterialTypeServiceExtendedModel,
            ConstructionMaterialTypeEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
