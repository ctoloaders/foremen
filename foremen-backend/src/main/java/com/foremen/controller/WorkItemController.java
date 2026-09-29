package com.foremen.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.MetadataResponse;
import com.foremen.controller.model.WorkItemCreateRequest;
import com.foremen.controller.model.WorkItemCreateResponse;
import com.foremen.controller.model.WorkItemDtoExtendedModel;
import com.foremen.controller.model.WorkItemDtoModel;
import com.foremen.controller.model.WorkItemRoomTypesRequest;
import com.foremen.controller.model.WorkItemRoomTypesResponse;
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

    /**
     * Reads a work item's Room_Type_Attachment (FOR-05-05, R10.5) — the ids of the room types the
     * work attaches to on apply. An empty list means the work has no attachment and attaches to ALL
     * rooms (R10.3).
     *
     * <p>Reuses the shipped {@code WORK_CATALOG} ABAC resource (no new resource / seed changeset).
     * The method-level {@link PermissionOperation @PermissionOperation("READ")} combines with the
     * class {@link PermissionResource @PermissionResource("WORK_CATALOG")} to form the
     * {@code (WORK_CATALOG, READ)} pair the {@code PermissionInterceptor} enforces, keeping the
     * controller fully annotated for {@code PermissionAnnotationValidator}. Returns
     * {@code 404 error.entity.not.found} when the work item does not exist.
     */
    @GetMapping("/{id}/room-types")
    @PermissionOperation("READ")
    public ResponseEntity<WorkItemRoomTypesResponse> getRoomTypes(@PathVariable Long id) {
        return ResponseEntity.ok(new WorkItemRoomTypesResponse(service.getRoomTypeIds(id)));
    }

    /**
     * Replaces a work item's Room_Type_Attachment (FOR-05-05, R10.5). The request body is a full
     * REPLACE of the attached room-type ids: an empty/null list clears the attachment (the work then
     * attaches to ALL rooms on apply, R10.3); otherwise the work attaches only to rooms whose type is
     * in the set (R10.2). Returns the resulting id list.
     *
     * <p>The method-level {@link PermissionOperation @PermissionOperation("UPDATE")} combines with the
     * class {@link PermissionResource @PermissionResource("WORK_CATALOG")} to form the
     * {@code (WORK_CATALOG, UPDATE)} pair. Returns {@code 404 error.entity.not.found} when the work
     * item or any referenced room type does not exist.
     */
    @PutMapping("/{id}/room-types")
    @PermissionOperation("UPDATE")
    public ResponseEntity<WorkItemRoomTypesResponse> setRoomTypes(
            @PathVariable Long id, @RequestBody WorkItemRoomTypesRequest request) {
        List<Long> resulting = service.setRoomTypeIds(id, request.roomTypeIds());
        return ResponseEntity.ok(new WorkItemRoomTypesResponse(resulting));
    }
}
