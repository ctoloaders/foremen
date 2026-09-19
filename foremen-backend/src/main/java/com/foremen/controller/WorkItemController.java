package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkItemControllerMapper;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkItemService;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.service.pricing.SeededOfferPackages;
import com.foremen.util.EntityMetadataResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private final SeededOfferPackages seededOfferPackages;
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
     * Metadata override that augments the generic {@link WorkItemEntity} descriptors with one
     * synthetic <em>pivot</em> field per seeded offer package (FOR-04-19, Requirement 5.6).
     *
     * <p>Mirrors the FOR-04-12b {@link WorkPriceController#getMetadata()} pivot, but each descriptor
     * here carries the THREE prices for that {@code (work, package)} rather than a single net price:
     * the FOR-04-12b labour price, the construction material range, and the finishing material range
     * (the branch money ranges of {@code MaterialRangeResolver}). The pivot map surfaced on the row
     * DTO ({@link WorkItemDtoModel#packagePivot()}) is keyed by the numeric {@code offerPackage.id},
     * so each descriptor is named {@code packagePivot.{id}} — the exact key the frontend reads — with
     * a {@link MetadataResponse.PivotInfo} carrying the numeric id and the package's localized
     * {@code nameRU}/{@code namePL} label, and three nested {@link MetadataResponse.FieldInfo}
     * children advertising the three prices ({@code labourPrice}, {@code construction},
     * {@code finishing}) so the frontend can render the per-package column and its three sub-values.
     */
    @Override
    @GetMapping("/metadata")
    @PermissionOperation("READ")
    public ResponseEntity<MetadataResponse> getMetadata() {
        MetadataResponse base = EntityMetadataResolver.resolve(WorkItemEntity.class);

        List<MetadataResponse.FieldInfo> fields = new ArrayList<>(base.fields());
        for (SeededOfferPackages.OfferPackageInfo pkg : seededOfferPackages.all()) {
            List<MetadataResponse.FieldInfo> prices = List.of(
                    new MetadataResponse.FieldInfo(
                            "labourPrice", MetadataResponse.DataType.NUMBER, false, null),
                    new MetadataResponse.FieldInfo(
                            "construction", MetadataResponse.DataType.NUMBER, false, null),
                    new MetadataResponse.FieldInfo(
                            "finishing", MetadataResponse.DataType.NUMBER, false, null));
            fields.add(new MetadataResponse.FieldInfo(
                    "packagePivot." + pkg.id(),
                    MetadataResponse.DataType.NUMBER,
                    false,
                    prices,
                    null,
                    new MetadataResponse.PivotInfo(
                            pkg.id(), pkg.nameRU(), pkg.namePL(), true, true)));
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(new MetadataResponse(fields));
    }
}
