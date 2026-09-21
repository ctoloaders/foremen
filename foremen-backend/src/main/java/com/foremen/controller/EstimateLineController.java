package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.EstimateLineCreateRequest;
import com.foremen.controller.model.EstimateLineCreateResponse;
import com.foremen.controller.model.EstimateLineDtoExtendedModel;
import com.foremen.controller.model.EstimateLineDtoModel;
import com.foremen.controller.model.EstimateLineUpdateRequest;
import com.foremen.controller.model.EstimateLineUpdateResponse;
import com.foremen.controller.model.mapper.EstimateLineControllerMapper;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.EstimateLineService;
import com.foremen.service.model.EstimateLineServiceExtendedModel;
import com.foremen.service.model.EstimateLineServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin project-scoped CRUD controller for the estimate-line vertical (FOR-05-03, Requirement 2),
 * following the FOR-04 {@code RoomController} pattern.
 *
 * <p>It implements {@link AdminController} and supplies only {@link #getMapper()},
 * {@link #getService()}, and {@link #getAuditServiceMapper()}; the generic create/list/read/update/
 * delete/count/metadata/i18n handlers are inherited as {@code default} methods, each already
 * carrying its {@code @PermissionOperation}. The class-level
 * {@link PermissionResource @PermissionResource("ESTIMATE")} combines with those operation
 * annotations to produce the {@code (resource, operation)} pairs enforced by the
 * {@code PermissionInterceptor} and validated at startup by {@code PermissionAnnotationValidator}
 * (Requirement 9.3). Lines share the {@code ESTIMATE} resource with the parent estimate rather than
 * a separate resource code — the design's ABAC section defines a single {@code ESTIMATE} resource
 * covering the estimate, its lines, room quantities, and per-package prices.
 */
@RestController
@RequestMapping("/api/estimate-lines")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateLineController implements AdminController<
        EstimateLineServiceModel,
        EstimateLineServiceExtendedModel,
        EstimateLineDtoModel,
        EstimateLineDtoExtendedModel,
        EstimateLineEntity,
        Long,
        EstimateLineCreateRequest,
        EstimateLineCreateResponse,
        EstimateLineUpdateRequest,
        EstimateLineUpdateResponse> {

    private final EstimateLineService service;
    private final EstimateLineControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<EstimateLineServiceModel, EstimateLineServiceExtendedModel,
            EstimateLineDtoModel, EstimateLineDtoExtendedModel,
            EstimateLineCreateRequest, EstimateLineCreateResponse,
            EstimateLineUpdateRequest, EstimateLineUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<EstimateLineServiceModel, EstimateLineServiceExtendedModel,
            EstimateLineEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
