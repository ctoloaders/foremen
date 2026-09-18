package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MaterialTypeControllerMapper;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MaterialTypeService;
import com.foremen.service.model.MaterialTypeServiceExtendedModel;
import com.foremen.service.model.MaterialTypeServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-types")
@RequiredArgsConstructor
@PermissionResource("MATERIAL_TYPES")
public class MaterialTypeController implements AdminController<
        MaterialTypeServiceModel,
        MaterialTypeServiceExtendedModel,
        MaterialTypeDtoModel,
        MaterialTypeDtoExtendedModel,
        MaterialTypeEntity,
        Long,
        MaterialTypeCreateRequest,
        MaterialTypeCreateResponse,
        MaterialTypeUpdateRequest,
        MaterialTypeUpdateResponse> {

    private final MaterialTypeService service;
    private final MaterialTypeControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<MaterialTypeServiceModel, MaterialTypeServiceExtendedModel,
            MaterialTypeDtoModel, MaterialTypeDtoExtendedModel,
            MaterialTypeCreateRequest, MaterialTypeCreateResponse,
            MaterialTypeUpdateRequest, MaterialTypeUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MaterialTypeServiceModel, MaterialTypeServiceExtendedModel,
            MaterialTypeEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
