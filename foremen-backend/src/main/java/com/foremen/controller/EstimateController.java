package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.EstimateCreateRequest;
import com.foremen.controller.model.EstimateCreateResponse;
import com.foremen.controller.model.EstimateDtoExtendedModel;
import com.foremen.controller.model.EstimateDtoModel;
import com.foremen.controller.model.EstimateUpdateRequest;
import com.foremen.controller.model.EstimateUpdateResponse;
import com.foremen.controller.model.mapper.EstimateControllerMapper;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.EstimateService;
import com.foremen.service.model.EstimateServiceExtendedModel;
import com.foremen.service.model.EstimateServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin project-scoped CRUD controller for the estimate vertical (FOR-05-03, Requirement 1),
 * following the FOR-04 {@code RoomController} pattern.
 *
 * <p>It implements {@link AdminController} and supplies only {@link #getMapper()},
 * {@link #getService()}, and {@link #getAuditServiceMapper()}; the generic create/list/read/update/
 * delete/count/metadata/i18n handlers are inherited as {@code default} methods, each already
 * carrying its {@code @PermissionOperation}. The class-level
 * {@link PermissionResource @PermissionResource("ESTIMATE")} combines with those operation
 * annotations to produce the {@code (resource, operation)} pairs enforced by the
 * {@code PermissionInterceptor} and validated at startup by {@code PermissionAnnotationValidator}
 * (Requirement 9.3). The value {@code "ESTIMATE"} matches the seeded resource {@code code}.
 *
 * <p><b>Get-or-create endpoint (R1.5).</b> {@link #getOrCreateForProject(Long)} exposes
 * {@code EstimateService#getOrCreateForProject(Long)} at {@code GET /api/estimates/project/{projectId}}
 * — a REST-conventional sub-resource path chosen because the design does not dictate an exact route
 * for this operation. It is annotated with a method-level
 * {@link RequiresPermission @RequiresPermission(resource = "ESTIMATE", operation = "READ")}, which
 * takes precedence over the class {@code @PermissionResource} + inherited-method
 * {@code @PermissionOperation} combination (mirroring {@code ConstructionMaterialController
 * #priceRanges}): the call is conceptually a read for the caller (it resolves the project's existing
 * estimate, transparently creating one on first access) rather than an explicit
 * {@code POST}-only create, and keeps the controller fully annotated so the app still starts.
 */
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateController implements AdminController<
        EstimateServiceModel,
        EstimateServiceExtendedModel,
        EstimateDtoModel,
        EstimateDtoExtendedModel,
        EstimateEntity,
        Long,
        EstimateCreateRequest,
        EstimateCreateResponse,
        EstimateUpdateRequest,
        EstimateUpdateResponse> {

    private final EstimateService service;
    private final EstimateControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<EstimateServiceModel, EstimateServiceExtendedModel,
            EstimateDtoModel, EstimateDtoExtendedModel,
            EstimateCreateRequest, EstimateCreateResponse,
            EstimateUpdateRequest, EstimateUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<EstimateServiceModel, EstimateServiceExtendedModel,
            EstimateEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Returns the given project's single estimate, creating exactly one (defaulted to PLN/DRAFT) if
     * absent (R1.5, R1.6). Delegates to {@link EstimateService#getOrCreateForProject(Long)}, which
     * reuses the same {@code findByProjectId} lookup the create-path uniqueness guard uses, so a
     * repeated call resolves to the same estimate rather than racing a duplicate.
     */
    @GetMapping("/project/{projectId}")
    @RequiresPermission(resource = "ESTIMATE", operation = "READ")
    public ResponseEntity<EstimateDtoExtendedModel> getOrCreateForProject(@PathVariable Long projectId) {
        EstimateServiceExtendedModel model = service.getOrCreateForProject(projectId);
        return ResponseEntity.ok(getMapper().toExtendedDto(model));
    }
}
