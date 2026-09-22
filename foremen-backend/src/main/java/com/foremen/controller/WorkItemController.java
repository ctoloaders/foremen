package com.foremen.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.MetadataResponse;
import com.foremen.controller.model.WorkItemCreateRequest;
import com.foremen.controller.model.WorkItemCreateResponse;
import com.foremen.controller.model.WorkItemDtoExtendedModel;
import com.foremen.controller.model.WorkItemDtoModel;
import com.foremen.controller.model.WorkItemUpdateRequest;
import com.foremen.controller.model.WorkItemUpdateResponse;
import com.foremen.controller.model.mapper.WorkItemControllerMapper;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkItemService;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.util.EntityMetadataResolver;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/work-items")
@RequiredArgsConstructor
@PermissionResource("WORK_CATALOG")
public class WorkItemController implements AdminController<
        WorkItemServiceModel,
        WorkItemServiceExtendedModel,
        WorkItemDtoModel,
        WorkItemDtoExtendedModel,
        WorkItemEntity,
        Long,
        WorkItemCreateRequest,
        WorkItemCreateResponse,
        WorkItemUpdateRequest,
        WorkItemUpdateResponse> {

    private final WorkItemService service;
    private final WorkItemControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkItemServiceModel, WorkItemServiceExtendedModel,
            WorkItemDtoModel, WorkItemDtoExtendedModel,
            WorkItemCreateRequest, WorkItemCreateResponse,
            WorkItemUpdateRequest, WorkItemUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkItemServiceModel, WorkItemServiceExtendedModel,
            WorkItemEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Metadata override that augments the generic {@link WorkItemEntity} descriptors with the
     * synthetic {@code costCell} field (FOR-04-19, Requirement 5.6; collapsed to a package-less
     * shape by FOR-05-04 Requirement 7.1).
     *
     * <p>Mirrors the FOR-04-12b {@link WorkPriceController#getMetadata()} descriptor, but
     * {@code costCell} carries the THREE cost parts for the work item rather than a single net
     * price: the single labour price, the construction material range, and the finishing material
     * range (the branch money ranges of {@code MaterialRangeResolver}). There is no longer a
     * per-package pivot — one {@link MetadataResponse.FieldInfo} with three nested children
     * ({@code labourPrice}, {@code construction}, {@code finishing}) advertises the row's single
     * cost cell.
     */
    @Override
    @GetMapping("/metadata")
    @PermissionOperation("READ")
    public ResponseEntity<MetadataResponse> getMetadata() {
        MetadataResponse base = EntityMetadataResolver.resolve(WorkItemEntity.class);

        List<MetadataResponse.FieldInfo> fields = new ArrayList<>(base.fields());
        List<MetadataResponse.FieldInfo> costParts = List.of(
                new MetadataResponse.FieldInfo(
                        "labourPrice", MetadataResponse.DataType.NUMBER, false, null),
                new MetadataResponse.FieldInfo(
                        "construction", MetadataResponse.DataType.NUMBER, false, null),
                new MetadataResponse.FieldInfo(
                        "finishing", MetadataResponse.DataType.NUMBER, false, null));
        fields.add(new MetadataResponse.FieldInfo(
                "costCell", MetadataResponse.DataType.NUMBER, false, costParts));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(new MetadataResponse(fields));
    }
}
