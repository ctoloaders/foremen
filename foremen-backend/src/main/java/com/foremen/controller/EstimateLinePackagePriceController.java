package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.EstimateLinePackagePriceControllerMapper;
import com.foremen.dao.model.EstimateLinePackagePriceEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.EstimateLinePackagePriceService;
import com.foremen.service.model.EstimateLinePackagePriceHistoryServiceModel;
import com.foremen.service.model.EstimateLinePackagePriceServiceExtendedModel;
import com.foremen.service.model.EstimateLinePackagePriceServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin project-scoped CRUD controller for the {@code EstimateLinePackagePrice} vertical
 * (FOR-05-03, Requirements 4, 5, 6), following the {@code RoomController} pattern.
 *
 * <p>It implements {@link AdminController} and supplies only {@link #getMapper()},
 * {@link #getService()}, and {@link #getAuditServiceMapper()}; the generic
 * create/list/read/update/delete/count/metadata/i18n handlers are inherited as {@code default}
 * methods, each already carrying its {@code @PermissionOperation}. The class-level
 * {@link PermissionResource @PermissionResource("ESTIMATE")} combines with those operation
 * annotations to produce the {@code (resource, operation)} pairs enforced by the
 * {@code PermissionInterceptor} and validated at startup by {@code PermissionAnnotationValidator}
 * (Requirements 9.3, 10.1). The value {@code "ESTIMATE"} matches the seeded resource {@code code}.
 *
 * <h2>Read-only history endpoint (Requirement 6.3)</h2>
 * <p>{@link #history(Long)} exposes {@link EstimateLinePackagePriceService#getHistory(Long)} at
 * {@code GET /api/estimate-line-package-prices/{id}/history}. Because this bespoke handler is not
 * one of the inherited CRUD {@code default} methods, it carries no automatic
 * {@code @PermissionOperation} — it is annotated with a method-level
 * {@code @RequiresPermission(resource = "ESTIMATE", operation = "READ")}, following the
 * {@code ConstructionMaterialController#priceRanges} pattern for a bespoke non-CRUD read handler.
 * {@code @RequiresPermission} takes precedence over the class {@code @PermissionResource} default
 * for this handler and keeps the controller fully annotated so
 * {@code PermissionAnnotationValidator} does not fail application startup (Requirements 9.3, 6.3).
 */
@RestController
@RequestMapping("/api/estimate-line-package-prices")
@RequiredArgsConstructor
@PermissionResource("ESTIMATE")
public class EstimateLinePackagePriceController implements AdminController<
        EstimateLinePackagePriceServiceModel,
        EstimateLinePackagePriceServiceExtendedModel,
        EstimateLinePackagePriceDtoModel,
        EstimateLinePackagePriceDtoExtendedModel,
        EstimateLinePackagePriceEntity,
        Long,
        EstimateLinePackagePriceCreateRequest,
        EstimateLinePackagePriceCreateResponse,
        EstimateLinePackagePriceUpdateRequest,
        EstimateLinePackagePriceUpdateResponse> {

    private final EstimateLinePackagePriceService service;
    private final EstimateLinePackagePriceControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<EstimateLinePackagePriceServiceModel, EstimateLinePackagePriceServiceExtendedModel,
            EstimateLinePackagePriceDtoModel, EstimateLinePackagePriceDtoExtendedModel,
            EstimateLinePackagePriceCreateRequest, EstimateLinePackagePriceCreateResponse,
            EstimateLinePackagePriceUpdateRequest, EstimateLinePackagePriceUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<EstimateLinePackagePriceServiceModel, EstimateLinePackagePriceServiceExtendedModel,
            EstimateLinePackagePriceEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Read-only change-history endpoint for a per-package project price (Requirement 6.3).
     *
     * <p>Delegates to {@link EstimateLinePackagePriceService#getHistory(Long)}, which already
     * asserts project access for {@code id} before returning every history row for it,
     * oldest-first. Nothing is ever persisted here. Guarded by a method-level
     * {@code @RequiresPermission(resource = "ESTIMATE", operation = "READ")} since this handler is
     * not an inherited CRUD {@code default} method (Requirements 9.3, 6.3).
     */
    @GetMapping("/{id}/history")
    @RequiresPermission(resource = "ESTIMATE", operation = "READ")
    public ResponseEntity<List<EstimateLinePackagePriceHistoryDto>> history(@PathVariable Long id) {
        List<EstimateLinePackagePriceHistoryServiceModel> history = service.getHistory(id);
        List<EstimateLinePackagePriceHistoryDto> dtos = history.stream()
                .map(this::toHistoryDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    private EstimateLinePackagePriceHistoryDto toHistoryDto(EstimateLinePackagePriceHistoryServiceModel source) {
        return new EstimateLinePackagePriceHistoryDto(
                source.getId(),
                source.getPackagePriceId(),
                source.getOriginalUnitPrice(),
                source.getDiscountKind(),
                source.getDiscountValue(),
                source.getUnitPrice(),
                source.getChangedBy(),
                source.getChangedAt());
    }
}
