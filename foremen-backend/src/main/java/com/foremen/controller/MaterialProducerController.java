package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MaterialProducerControllerMapper;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MaterialProducerService;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-producers")
@RequiredArgsConstructor
@PermissionResource("MATERIAL_PRODUCERS")
public class MaterialProducerController implements AdminController<
        MaterialProducerServiceModel,
        MaterialProducerServiceExtendedModel,
        MaterialProducerDtoModel,
        MaterialProducerDtoExtendedModel,
        MaterialProducerEntity,
        Long,
        MaterialProducerCreateRequest,
        MaterialProducerCreateResponse,
        MaterialProducerUpdateRequest,
        MaterialProducerUpdateResponse> {

    private final MaterialProducerService service;
    private final MaterialProducerControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<MaterialProducerServiceModel, MaterialProducerServiceExtendedModel,
            MaterialProducerDtoModel, MaterialProducerDtoExtendedModel,
            MaterialProducerCreateRequest, MaterialProducerCreateResponse,
            MaterialProducerUpdateRequest, MaterialProducerUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MaterialProducerServiceModel, MaterialProducerServiceExtendedModel,
            MaterialProducerEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
