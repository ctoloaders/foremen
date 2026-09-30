package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkerTypeControllerMapper;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkerTypeService;
import com.foremen.service.model.WorkerTypeServiceExtendedModel;
import com.foremen.service.model.WorkerTypeServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FOR-05-06 — WorkerType cost-tier dictionary CRUD, guarded by the ABAC resource {@code WORKER_TYPES}.
 * The class-level {@link PermissionResource} combines with the {@code @PermissionOperation} declared on
 * each inherited {@link AdminController} CRUD default method to form the {@code (resource, operation)}
 * pair the permission interceptor enforces (fully annotated → {@code PermissionAnnotationValidator}
 * classifies this controller COMPLETE at startup).
 */
@RestController
@RequestMapping("/api/worker-types")
@RequiredArgsConstructor
@PermissionResource("WORKER_TYPES")
public class WorkerTypeController implements AdminController<
        WorkerTypeServiceModel,
        WorkerTypeServiceExtendedModel,
        WorkerTypeDtoModel,
        WorkerTypeDtoExtendedModel,
        WorkerTypeEntity,
        Long,
        WorkerTypeCreateRequest,
        WorkerTypeCreateResponse,
        WorkerTypeUpdateRequest,
        WorkerTypeUpdateResponse> {

    private final WorkerTypeService service;
    private final WorkerTypeControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkerTypeServiceModel, WorkerTypeServiceExtendedModel,
            WorkerTypeDtoModel, WorkerTypeDtoExtendedModel,
            WorkerTypeCreateRequest, WorkerTypeCreateResponse,
            WorkerTypeUpdateRequest, WorkerTypeUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkerTypeServiceModel, WorkerTypeServiceExtendedModel,
            WorkerTypeEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
