package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MaterialCategoryControllerMapper;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MaterialCategoryService;
import com.foremen.service.model.MaterialCategoryServiceExtendedModel;
import com.foremen.service.model.MaterialCategoryServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-categories")
@RequiredArgsConstructor
@PermissionResource("MATERIAL_CATEGORIES")
public class MaterialCategoryController implements AdminController<
        MaterialCategoryServiceModel,
        MaterialCategoryServiceExtendedModel,
        MaterialCategoryDtoModel,
        MaterialCategoryDtoExtendedModel,
        MaterialCategoryEntity,
        Long,
        MaterialCategoryCreateRequest,
        MaterialCategoryCreateResponse,
        MaterialCategoryUpdateRequest,
        MaterialCategoryUpdateResponse> {

    private final MaterialCategoryService service;
    private final MaterialCategoryControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<MaterialCategoryServiceModel, MaterialCategoryServiceExtendedModel,
            MaterialCategoryDtoModel, MaterialCategoryDtoExtendedModel,
            MaterialCategoryCreateRequest, MaterialCategoryCreateResponse,
            MaterialCategoryUpdateRequest, MaterialCategoryUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MaterialCategoryServiceModel, MaterialCategoryServiceExtendedModel,
            MaterialCategoryEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
