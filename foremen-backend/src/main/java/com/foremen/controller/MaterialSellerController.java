package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MaterialSellerControllerMapper;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MaterialSellerService;
import com.foremen.service.model.MaterialSellerServiceExtendedModel;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-sellers")
@RequiredArgsConstructor
@PermissionResource("MATERIAL_SELLERS")
public class MaterialSellerController implements AdminController<
        MaterialSellerServiceModel,
        MaterialSellerServiceExtendedModel,
        MaterialSellerDtoModel,
        MaterialSellerDtoExtendedModel,
        MaterialSellerEntity,
        Long,
        MaterialSellerCreateRequest,
        MaterialSellerCreateResponse,
        MaterialSellerUpdateRequest,
        MaterialSellerUpdateResponse> {

    private final MaterialSellerService service;
    private final MaterialSellerControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<MaterialSellerServiceModel, MaterialSellerServiceExtendedModel,
            MaterialSellerDtoModel, MaterialSellerDtoExtendedModel,
            MaterialSellerCreateRequest, MaterialSellerCreateResponse,
            MaterialSellerUpdateRequest, MaterialSellerUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MaterialSellerServiceModel, MaterialSellerServiceExtendedModel,
            MaterialSellerEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
