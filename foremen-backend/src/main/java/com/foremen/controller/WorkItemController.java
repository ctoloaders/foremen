package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkItemControllerMapper;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkItemService;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-items")
@RequiredArgsConstructor
@PermissionResource("WORK_CATALOG")
public class WorkItemController implements AdminController<
        WorkItemServiceModel,
        WorkItemServiceExtendedModel,
        WorkItemDtoModel,
        WorkItemDtoExtendedModel,
        WorkItemEntity,
        Long,
        WorkItemCreateRequest,
        WorkItemCreateResponse,
        WorkItemUpdateRequest,
        WorkItemUpdateResponse> {

    private final WorkItemService service;
    private final WorkItemControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkItemServiceModel, WorkItemServiceExtendedModel,
            WorkItemDtoModel, WorkItemDtoExtendedModel,
            WorkItemCreateRequest, WorkItemCreateResponse,
            WorkItemUpdateRequest, WorkItemUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkItemServiceModel, WorkItemServiceExtendedModel,
            WorkItemEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
