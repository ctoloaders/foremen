package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.ProjectMaterialCreateRequest;
import com.foremen.controller.model.ProjectMaterialCreateResponse;
import com.foremen.controller.model.ProjectMaterialDtoExtendedModel;
import com.foremen.controller.model.ProjectMaterialDtoModel;
import com.foremen.controller.model.ProjectMaterialUpdateRequest;
import com.foremen.controller.model.ProjectMaterialUpdateResponse;
import com.foremen.controller.model.mapper.ProjectMaterialControllerMapper;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ProjectMaterialService;
import com.foremen.service.model.ProjectMaterialServiceExtendedModel;
import com.foremen.service.model.ProjectMaterialServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD controller for Project_Materials (FOR-11-00 R3). Only the inherited, fully annotated CRUD
 * defaults are exposed; the class-level {@link PermissionResource} combines with their
 * {@code @PermissionOperation}. The {@code projectId} list filter uses the generic list query
 * (field {@code project.id}).
 */
@RestController
@RequestMapping("/api/project-materials")
@RequiredArgsConstructor
@PermissionResource("PROJECT_MATERIALS")
public class ProjectMaterialController implements AdminController<
        ProjectMaterialServiceModel,
        ProjectMaterialServiceExtendedModel,
        ProjectMaterialDtoModel,
        ProjectMaterialDtoExtendedModel,
        FinishingMaterialEntity,
        Long,
        ProjectMaterialCreateRequest,
        ProjectMaterialCreateResponse,
        ProjectMaterialUpdateRequest,
        ProjectMaterialUpdateResponse> {

    private final ProjectMaterialService service;
    private final ProjectMaterialControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<ProjectMaterialServiceModel, ProjectMaterialServiceExtendedModel,
            ProjectMaterialDtoModel, ProjectMaterialDtoExtendedModel,
            ProjectMaterialCreateRequest, ProjectMaterialCreateResponse,
            ProjectMaterialUpdateRequest, ProjectMaterialUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<ProjectMaterialServiceModel, ProjectMaterialServiceExtendedModel,
            FinishingMaterialEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
