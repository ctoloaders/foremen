package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MaterialControllerMapper;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MaterialService;
import com.foremen.service.model.MaterialServiceExtendedModel;
import com.foremen.service.model.MaterialServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
@PermissionResource("MATERIALS")
public class MaterialController implements AdminController<
        MaterialServiceModel,
        MaterialServiceExtendedModel,
        MaterialDtoModel,
        MaterialDtoExtendedModel,
        MaterialEntity,
        Long,
        MaterialCreateRequest,
        MaterialCreateResponse,
        MaterialUpdateRequest,
        MaterialUpdateResponse> {

    private final MaterialService service;
    private final MaterialControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<MaterialServiceModel, MaterialServiceExtendedModel,
            MaterialDtoModel, MaterialDtoExtendedModel,
            MaterialCreateRequest, MaterialCreateResponse,
            MaterialUpdateRequest, MaterialUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MaterialServiceModel, MaterialServiceExtendedModel,
            MaterialEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
