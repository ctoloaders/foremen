package com.foremen.controller;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.MetadataResponse;
import com.foremen.controller.model.WorkPriceCreateRequest;
import com.foremen.controller.model.WorkPriceCreateResponse;
import com.foremen.controller.model.WorkPriceDtoExtendedModel;
import com.foremen.controller.model.WorkPriceDtoModel;
import com.foremen.controller.model.WorkPriceUpdateRequest;
import com.foremen.controller.model.WorkPriceUpdateResponse;
import com.foremen.controller.model.mapper.WorkPriceControllerMapper;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkPriceService;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.util.EntityMetadataResolver;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/work-prices")
@RequiredArgsConstructor
@PermissionResource("WORK_PRICES")
public class WorkPriceController implements AdminController<
        WorkPriceServiceModel,
        WorkPriceServiceExtendedModel,
        WorkPriceDtoModel,
        WorkPriceDtoExtendedModel,
        WorkPriceEntity,
        Long,
        WorkPriceCreateRequest,
        WorkPriceCreateResponse,
        WorkPriceUpdateRequest,
        WorkPriceUpdateResponse> {

    private final WorkPriceService service;
    private final WorkPriceControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkPriceServiceModel, WorkPriceServiceExtendedModel,
            WorkPriceDtoModel, WorkPriceDtoExtendedModel,
            WorkPriceCreateRequest, WorkPriceCreateResponse,
            WorkPriceUpdateRequest, WorkPriceUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkPriceServiceModel, WorkPriceServiceExtendedModel,
            WorkPriceEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Metadata for the single-price {@code WorkPrice} row (FOR-05-04, Requirement 1.3). No per-package
     * pivot fields — the generic {@link EntityMetadataResolver} descriptors for {@code currency}/
     * {@code netPrice} suffice on their own.
     */
    @Override
    @GetMapping("/metadata")
    @PermissionOperation("READ")
    public ResponseEntity<MetadataResponse> getMetadata() {
        MetadataResponse base = EntityMetadataResolver.resolve(WorkPriceEntity.class);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(base);
    }
}
