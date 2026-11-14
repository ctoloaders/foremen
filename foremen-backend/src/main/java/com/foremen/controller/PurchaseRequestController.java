package com.foremen.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.AttachPositionsRequest;
import com.foremen.controller.model.PurchaseRequestCreateRequest;
import com.foremen.controller.model.PurchaseRequestCreateResponse;
import com.foremen.controller.model.PurchaseRequestDtoExtendedModel;
import com.foremen.controller.model.PurchaseRequestDtoModel;
import com.foremen.controller.model.PurchaseRequestUpdateRequest;
import com.foremen.controller.model.PurchaseRequestUpdateResponse;
import com.foremen.controller.model.mapper.PurchaseRequestControllerMapper;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.PurchaseRequestService;
import com.foremen.service.model.PurchaseRequestServiceExtendedModel;
import com.foremen.service.model.PurchaseRequestServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for purchase requests (FOR-11-00 R11) plus attach/detach of positions. The inherited
 * CRUD defaults carry their own {@code @PermissionOperation}; the two extra handlers declare
 * {@code @RequiresPermission(PURCHASE_REQUESTS, UPDATE)}.
 */
@RestController
@RequestMapping("/api/purchase-requests")
@RequiredArgsConstructor
@PermissionResource("PURCHASE_REQUESTS")
public class PurchaseRequestController implements AdminController<
        PurchaseRequestServiceModel,
        PurchaseRequestServiceExtendedModel,
        PurchaseRequestDtoModel,
        PurchaseRequestDtoExtendedModel,
        PurchaseRequestEntity,
        Long,
        PurchaseRequestCreateRequest,
        PurchaseRequestCreateResponse,
        PurchaseRequestUpdateRequest,
        PurchaseRequestUpdateResponse> {

    private static final String RESOURCE = "PURCHASE_REQUESTS";

    private final PurchaseRequestService service;
    private final PurchaseRequestControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<PurchaseRequestServiceModel, PurchaseRequestServiceExtendedModel,
            PurchaseRequestDtoModel, PurchaseRequestDtoExtendedModel,
            PurchaseRequestCreateRequest, PurchaseRequestCreateResponse,
            PurchaseRequestUpdateRequest, PurchaseRequestUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<PurchaseRequestServiceModel, PurchaseRequestServiceExtendedModel,
            PurchaseRequestEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /** Paginated list; documents the {@code hideDelivered} flag that the service reads from the request (R22). */
    @Override
    @GetMapping
    @PermissionOperation("READ")
    @Operation(summary = "List purchase requests",
            parameters = @Parameter(name = "hideDelivered", in = ParameterIn.QUERY,
                    description = "When true, requests whose derived status is COMPLETED are excluded (requests "
                            + "without positions or with any non-terminal position stay). Composes with query, "
                            + "sort, pagination and project scoping. Default false.",
                    schema = @Schema(type = "boolean", defaultValue = "false")))
    public ResponseEntity<Page<PurchaseRequestDtoModel>> find(
            Pageable pageable, @RequestParam(name = "query", required = false) String query) {
        return AdminController.super.find(pageable, query);
    }

    /** Attaches positions to the request (R11.4). */
    @PostMapping("/{id}/positions")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<PurchaseRequestDtoExtendedModel> attachPositions(
            @PathVariable Long id, @Valid @RequestBody AttachPositionsRequest request) {
        return ResponseEntity.ok(controllerMapper.toExtendedDto(service.attachPositions(id, request.positionIds())));
    }

    /** Detaches a position while it is still PLANNED / PROPOSED / ACCEPTED (R11.5). */
    @DeleteMapping("/{id}/positions/{positionId}")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<PurchaseRequestDtoExtendedModel> detachPosition(
            @PathVariable Long id, @PathVariable Long positionId) {
        return ResponseEntity.ok(controllerMapper.toExtendedDto(service.detachPosition(id, positionId)));
    }
}
