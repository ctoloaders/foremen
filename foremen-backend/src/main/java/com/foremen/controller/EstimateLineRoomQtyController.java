package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.EstimateLineRoomQtyControllerMapper;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.EstimateLineRoomQtyService;
import com.foremen.service.model.EstimateLineRoomQtyServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomQtyServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin project-scoped CRUD controller for the {@code EstimateLineRoomQty} vertical (FOR-05-03,
 * Requirement 3), following the {@code RoomController} pattern.
 *
 * <p>It implements {@link AdminController} and supplies only {@link #getMapper()},
 * {@link #getService()}, and {@link #getAuditServiceMapper()}; the generic
 * create/list/read/update/delete/count/metadata/i18n handlers are inherited as {@code default}
 * methods, each already carrying its {@code @PermissionOperation}. The class-level
 * {@link PermissionResource @PermissionResource("ESTIMATE")} combines with those operation
 * annotations to produce the {@code (resource, operation)} pairs enforced by the
 * {@code PermissionInterceptor} and validated at startup by {@code PermissionAnnotationValidator}
 * (Requirements 9.3, 10.1). The value {@code "ESTIMATE"} matches the seeded resource {@code code}.
 */
@RestController
@RequestMapping("/api/estimate-line-room-qty")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateLineRoomQtyController implements AdminController<
        EstimateLineRoomQtyServiceModel,
        EstimateLineRoomQtyServiceExtendedModel,
        EstimateLineRoomQtyDtoModel,
        EstimateLineRoomQtyDtoExtendedModel,
        EstimateLineRoomQtyEntity,
        Long,
        EstimateLineRoomQtyCreateRequest,
        EstimateLineRoomQtyCreateResponse,
        EstimateLineRoomQtyUpdateRequest,
        EstimateLineRoomQtyUpdateResponse> {

    private final EstimateLineRoomQtyService service;
    private final EstimateLineRoomQtyControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<EstimateLineRoomQtyServiceModel, EstimateLineRoomQtyServiceExtendedModel,
            EstimateLineRoomQtyDtoModel, EstimateLineRoomQtyDtoExtendedModel,
            EstimateLineRoomQtyCreateRequest, EstimateLineRoomQtyCreateResponse,
            EstimateLineRoomQtyUpdateRequest, EstimateLineRoomQtyUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<EstimateLineRoomQtyServiceModel, EstimateLineRoomQtyServiceExtendedModel,
            EstimateLineRoomQtyEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
