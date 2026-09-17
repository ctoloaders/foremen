package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.DeliveryStatusControllerMapper;
import com.foremen.dao.model.DeliveryStatusEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.DeliveryStatusService;
import com.foremen.service.model.DeliveryStatusServiceExtendedModel;
import com.foremen.service.model.DeliveryStatusServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/delivery-statuses")
@RequiredArgsConstructor
@PermissionResource("DELIVERY_STATUSES")
public class DeliveryStatusController implements AdminController<
        DeliveryStatusServiceModel,
        DeliveryStatusServiceExtendedModel,
        DeliveryStatusDtoModel,
        DeliveryStatusDtoExtendedModel,
        DeliveryStatusEntity,
        Long,
        DeliveryStatusCreateRequest,
        DeliveryStatusCreateResponse,
        DeliveryStatusUpdateRequest,
        DeliveryStatusUpdateResponse> {

    private final DeliveryStatusService service;
    private final DeliveryStatusControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<DeliveryStatusServiceModel, DeliveryStatusServiceExtendedModel,
            DeliveryStatusDtoModel, DeliveryStatusDtoExtendedModel,
            DeliveryStatusCreateRequest, DeliveryStatusCreateResponse,
            DeliveryStatusUpdateRequest, DeliveryStatusUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<DeliveryStatusServiceModel, DeliveryStatusServiceExtendedModel,
            DeliveryStatusEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
