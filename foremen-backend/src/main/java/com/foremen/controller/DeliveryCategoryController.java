package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.DeliveryCategoryControllerMapper;
import com.foremen.dao.model.DeliveryCategoryEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.DeliveryCategoryService;
import com.foremen.service.model.DeliveryCategoryServiceExtendedModel;
import com.foremen.service.model.DeliveryCategoryServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/delivery-categories")
@RequiredArgsConstructor
@PermissionResource("DELIVERY_CATEGORIES")
public class DeliveryCategoryController implements AdminController<
        DeliveryCategoryServiceModel,
        DeliveryCategoryServiceExtendedModel,
        DeliveryCategoryDtoModel,
        DeliveryCategoryDtoExtendedModel,
        DeliveryCategoryEntity,
        Long,
        DeliveryCategoryCreateRequest,
        DeliveryCategoryCreateResponse,
        DeliveryCategoryUpdateRequest,
        DeliveryCategoryUpdateResponse> {

    private final DeliveryCategoryService service;
    private final DeliveryCategoryControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<DeliveryCategoryServiceModel, DeliveryCategoryServiceExtendedModel,
            DeliveryCategoryDtoModel, DeliveryCategoryDtoExtendedModel,
            DeliveryCategoryCreateRequest, DeliveryCategoryCreateResponse,
            DeliveryCategoryUpdateRequest, DeliveryCategoryUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<DeliveryCategoryServiceModel, DeliveryCategoryServiceExtendedModel,
            DeliveryCategoryEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
