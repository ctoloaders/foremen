package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkCategoryControllerMapper;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkCategoryService;
import com.foremen.service.model.WorkCategoryServiceExtendedModel;
import com.foremen.service.model.WorkCategoryServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-categories")
@RequiredArgsConstructor
@PermissionResource("WORK_CATEGORIES")
public class WorkCategoryController implements AdminController<
        WorkCategoryServiceModel,
        WorkCategoryServiceExtendedModel,
        WorkCategoryDtoModel,
        WorkCategoryDtoExtendedModel,
        WorkCategoryEntity,
        Long,
        WorkCategoryCreateRequest,
        WorkCategoryCreateResponse,
        WorkCategoryUpdateRequest,
        WorkCategoryUpdateResponse> {

    private final WorkCategoryService service;
    private final WorkCategoryControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkCategoryServiceModel, WorkCategoryServiceExtendedModel,
            WorkCategoryDtoModel, WorkCategoryDtoExtendedModel,
            WorkCategoryCreateRequest, WorkCategoryCreateResponse,
            WorkCategoryUpdateRequest, WorkCategoryUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkCategoryServiceModel, WorkCategoryServiceExtendedModel,
            WorkCategoryEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
