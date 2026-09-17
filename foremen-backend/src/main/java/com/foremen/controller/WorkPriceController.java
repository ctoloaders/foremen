package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkPriceControllerMapper;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkPriceService;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
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
    private final SeededOfferPackages seededOfferPackages;
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
     * Metadata override that augments the generic {@link WorkPriceEntity} descriptors with one
     * synthetic <em>pivot</em> field per seeded offer package (FOR-04-12b, Requirement 4.7).
     *
     * <p>The generic {@link EntityMetadataResolver} already advertises the {@code packagePrices}
     * collection, but that is not the pivot contract the frontend needs: it needs one descriptor per
     * seeded package, keyed by the numeric {@code offerPackage.id}, so it can build the pivot
     * filter/sort controls and the {@code Cena {label}} column header. For each seeded package this
     * appends a field named {@code prices.{id}.netPrice} — the exact {@code Pivot_Key} the frontend
     * sends back — with {@code dataType = NUMBER}, {@code sortable}/{@code filterable} true, carrying
     * the package's numeric {@code id} and its localized {@code nameRU}/{@code namePL} label.
     *
     * <p>Filtering flows through {@code ?query=} (branched inside {@code SpecificationBuilder}) and
     * sort wiring lives on the service, so no {@code addCustomQueryCondition} rewrite or
     * {@code find}/{@code findExtended} override is needed here.
     */
    @Override
    @GetMapping("/metadata")
    @PermissionOperation("READ")
    public ResponseEntity<MetadataResponse> getMetadata() {
        MetadataResponse base = EntityMetadataResolver.resolve(WorkPriceEntity.class);

        List<MetadataResponse.FieldInfo> fields = new ArrayList<>(base.fields());
        for (SeededOfferPackages.OfferPackageInfo pkg : seededOfferPackages.all()) {
            fields.add(new MetadataResponse.FieldInfo(
                    "prices." + pkg.id() + ".netPrice",
                    MetadataResponse.DataType.NUMBER,
                    false,
                    null,
                    null,
                    new MetadataResponse.PivotInfo(
                            pkg.id(), pkg.nameRU(), pkg.namePL(), true, true)));
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(new MetadataResponse(fields));
    }
}
