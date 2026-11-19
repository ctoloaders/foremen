package com.foremen.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.StandardWorkSetItemCreateRequest;
import com.foremen.controller.model.StandardWorkSetItemCreateResponse;
import com.foremen.controller.model.StandardWorkSetItemDtoExtendedModel;
import com.foremen.controller.model.StandardWorkSetItemDtoModel;
import com.foremen.controller.model.StandardWorkSetItemUpdateRequest;
import com.foremen.controller.model.StandardWorkSetItemUpdateResponse;
import com.foremen.controller.model.mapper.StandardWorkSetItemControllerMapper;
import com.foremen.dao.model.StandardWorkSetItemEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.StandardWorkSetItemService;
import com.foremen.service.model.StandardWorkSetItemServiceExtendedModel;
import com.foremen.service.model.StandardWorkSetItemServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import lombok.RequiredArgsConstructor;

/**
 * CRUD controller of the standard work set by room type (FOR-04-11 additional tasks 6-8), guarded by
 * the {@code STANDARD_WORK_SETS} resource (seeded by changeset 264). Only the inherited, fully
 * annotated generic operations are exposed.
 */
@RestController
@RequestMapping("/api/standard-work-sets")
@RequiredArgsConstructor
@PermissionResource("STANDARD_WORK_SETS")
public class StandardWorkSetItemController implements AdminController<
        StandardWorkSetItemServiceModel,
        StandardWorkSetItemServiceExtendedModel,
        StandardWorkSetItemDtoModel,
        StandardWorkSetItemDtoExtendedModel,
        StandardWorkSetItemEntity,
        Long,
        StandardWorkSetItemCreateRequest,
        StandardWorkSetItemCreateResponse,
        StandardWorkSetItemUpdateRequest,
        StandardWorkSetItemUpdateResponse> {

    private final StandardWorkSetItemService service;
    private final StandardWorkSetItemControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<StandardWorkSetItemServiceModel, StandardWorkSetItemServiceExtendedModel,
            StandardWorkSetItemDtoModel, StandardWorkSetItemDtoExtendedModel,
            StandardWorkSetItemCreateRequest, StandardWorkSetItemCreateResponse,
            StandardWorkSetItemUpdateRequest, StandardWorkSetItemUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<StandardWorkSetItemServiceModel, StandardWorkSetItemServiceExtendedModel,
            StandardWorkSetItemEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
