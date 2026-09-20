package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.ConstructionMaterialControllerMapper;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ConstructionMaterialService;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.service.pricing.PriceRangeResolver;
import com.foremen.service.pricing.PriceRangeResolver.PriceRange;
import com.foremen.service.pricing.PriceRangeResolver.PriceRangeKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CRUD controller for the construction-material vertical (FOR-04-17, task 5.3).
 *
 * <p>Mirrors {@code MaterialSellerController} / {@code ConstructionMaterialTypeController}: a thin
 * {@link AdminController} implementation wiring the {@link ConstructionMaterialService}, the
 * {@link ConstructionMaterialControllerMapper} and the shared {@link AuditServiceMapper}. It exposes
 * the generic create/list/read/update/delete/count/metadata/i18n operations inherited as
 * {@code default} methods from {@link AdminController}.
 *
 * <p>The class-level {@link PermissionResource}{@code ("MATERIALS_CONSTRUCTION")} combines with the
 * {@code @PermissionOperation} declared on each inherited CRUD {@code default} method to produce the
 * {@code (resource, operation)} pair the {@code PermissionInterceptor} enforces and the
 * {@code PermissionAnnotationValidator} checks at startup (Requirements 9.5, 9.6).
 *
 * <p>Reference filters {@code type.id}/{@code producer.id}/{@code seller.id}/{@code unit.id}/
 * {@code currency.id}/{@code packages.id} require no extra code here: the inherited list operation
 * delegates to the FOR-04-01 {@code SpecificationBuilder}, which resolves dot-notation paths and
 * transparently turns a collection path (e.g. {@code packages.id}) into a JOIN with
 * {@code distinct} (Requirements 4.1, 4.9).
 *
 * <h2>Computed price range (Requirement 6)</h2>
 * <p>Two read paths surface the computed MIN..MAX {@code retailNet} range keyed by the pair
 * ({@code OfferPackage}, {@code ConstructionMaterialType}), both derived at read time from the same
 * active-priced material set (loaded once per request via
 * {@link ConstructionMaterialDao#findByActiveTrueAndRetailNetNotNull()}) and never persisted:
 * <ul>
 *   <li>{@link #priceRanges(Long, Long)} — {@code GET /price-ranges} with optional
 *       {@code ?packageId=&typeId=}: when both are supplied it returns the single {@link PriceRange}
 *       for that pair, otherwise the full map as a list of {@link PriceRangeEntry} rows. Guarded by a
 *       method-level {@code @RequiresPermission("MATERIALS_CONSTRUCTION", "READ")}, which takes
 *       precedence over the class {@code @PermissionResource} + method {@code @PermissionOperation}
 *       combination for this handler and keeps the controller fully annotated for
 *       {@code PermissionAnnotationValidator} (Requirements 9.5, 9.6).</li>
 *   <li>{@link #find(Pageable, String)} — the inherited list is overridden to also stamp each
 *       returned {@link ConstructionMaterialDtoModel}'s {@code priceRanges} with the ranges for that
 *       row's own {@code (each package, its type)} pairs, computed once per request from the whole
 *       active-priced set, so the UI renders the price-range column without a second round-trip.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/construction-materials")
@RequiredArgsConstructor
@PermissionResource("MATERIALS_CONSTRUCTION")
public class ConstructionMaterialController implements AdminController<
        ConstructionMaterialServiceModel,
        ConstructionMaterialServiceExtendedModel,
        ConstructionMaterialDtoModel,
        ConstructionMaterialDtoExtendedModel,
        ConstructionMaterialEntity,
        Long,
        ConstructionMaterialCreateRequest,
        ConstructionMaterialCreateResponse,
        ConstructionMaterialUpdateRequest,
        ConstructionMaterialUpdateResponse> {

    private final ConstructionMaterialService service;
    private final ConstructionMaterialControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;
    private final ConstructionMaterialDao constructionMaterialDao;
    private final PriceRangeResolver priceRangeResolver;

    @Override
    public ControllerToServiceMapper<ConstructionMaterialServiceModel,
            ConstructionMaterialServiceExtendedModel,
            ConstructionMaterialDtoModel, ConstructionMaterialDtoExtendedModel,
            ConstructionMaterialCreateRequest, ConstructionMaterialCreateResponse,
            ConstructionMaterialUpdateRequest, ConstructionMaterialUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<ConstructionMaterialServiceModel, ConstructionMaterialServiceExtendedModel,
            ConstructionMaterialEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Computed price range endpoint (Requirements 6.1, 6.4, 9.5, 9.6).
     *
     * <p>Loads the active priced construction materials once
     * ({@link ConstructionMaterialDao#findByActiveTrueAndRetailNetNotNull()}, {@code packages} +
     * {@code type} fetched) and delegates the read-time computation to {@link PriceRangeResolver}:
     * <ul>
     *   <li>when BOTH {@code packageId} and {@code typeId} are supplied, returns the single
     *       {@link PriceRange} for that pair (an empty {@code PriceRange(null, null)} when no active
     *       priced material qualifies);</li>
     *   <li>otherwise returns the full map as a list of {@link PriceRangeEntry} rows
     *       {@code {offerPackageId, typeId, min, max}}.</li>
     * </ul>
     * The response type is {@code Object} because the two shapes differ; nothing is ever persisted
     * (Requirement 6.4). The method-level {@code @RequiresPermission} takes precedence for this
     * handler over the class {@code @PermissionResource} default, so the controller stays fully
     * annotated for {@code PermissionAnnotationValidator} (Requirements 9.5, 9.6).
     */
    @GetMapping("/price-ranges")
    @RequiresPermission(resource = "MATERIALS_CONSTRUCTION", operation = "READ")
    public ResponseEntity<?> priceRanges(
            @RequestParam(name = "packageId", required = false) Long packageId,
            @RequestParam(name = "typeId", required = false) Long typeId) {
        List<ConstructionMaterialEntity> materials = constructionMaterialDao.findByActiveTrueAndRetailNetNotNull();

        if (packageId != null && typeId != null) {
            PriceRange range = priceRangeResolver.rangeFor(materials, packageId, typeId);
            return ResponseEntity.ok(range);
        }

        List<PriceRangeEntry> rows = new ArrayList<>();
        for (Map.Entry<PriceRangeKey, PriceRange> entry : priceRangeResolver.compute(materials).entrySet()) {
            PriceRangeKey key = entry.getKey();
            PriceRange range = entry.getValue();
            rows.add(new PriceRangeEntry(
                    key.offerPackageId(), key.constructionMaterialTypeId(), range.min(), range.max()));
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * Overrides the inherited list operation to ALSO populate each returned
     * {@link ConstructionMaterialDtoModel}'s {@code priceRanges} payload, so the UI can render the
     * per-({@code package}, {@code type}) range column without a second round-trip (Requirements 6.1,
     * 6.4).
     *
     * <p>The full price-range map is computed ONCE per request from the whole active-priced material
     * set (not per row, so no N+1), then each row is indexed into it: for a row of type {@code T},
     * for every package {@code P} in that row's {@code packages}, the {@code (P, T)} range is looked
     * up (empty range when the pair has no qualifying material). Because {@link ConstructionMaterialDtoModel}
     * is an immutable record, each row is rebuilt as a copy carrying its resolved {@code priceRanges}.
     */
    @Override
    @GetMapping
    @PermissionOperation("READ")
    public ResponseEntity<Page<ConstructionMaterialDtoModel>> find(
            Pageable pageable,
            @RequestParam(name = "query", required = false) String query) {
        ResponseEntity<Page<ConstructionMaterialDtoModel>> response =
                AdminController.super.find(pageable, query);
        Page<ConstructionMaterialDtoModel> page = response.getBody();
        if (page == null) {
            return response;
        }

        Map<PriceRangeKey, PriceRange> ranges =
                priceRangeResolver.compute(constructionMaterialDao.findByActiveTrueAndRetailNetNotNull());

        return ResponseEntity.ok(page.map(row -> withPriceRanges(row, ranges)));
    }

    /**
     * Returns a copy of {@code row} whose {@code priceRanges} is filled with the range for each
     * {@code (package, row.type)} pair, indexed from the pre-computed {@code ranges} map. A row with
     * no type or no packages yields an empty {@code priceRanges} list.
     */
    private ConstructionMaterialDtoModel withPriceRanges(ConstructionMaterialDtoModel row,
                                                         Map<PriceRangeKey, PriceRange> ranges) {
        List<PriceRangeEntry> rowRanges = new ArrayList<>();
        RefDto type = row.type();
        List<RefDto> packages = row.packages();
        if (type != null && type.id() != null && packages != null) {
            Long typeId = type.id();
            for (RefDto pkg : packages) {
                if (pkg == null || pkg.id() == null) {
                    continue;
                }
                PriceRange range = ranges.getOrDefault(
                        new PriceRangeKey(pkg.id(), typeId), PriceRange.EMPTY);
                rowRanges.add(new PriceRangeEntry(pkg.id(), typeId, range.min(), range.max()));
            }
        }

        return new ConstructionMaterialDtoModel(
                row.id(),
                row.name(),
                row.type(),
                row.producer(),
                row.seller(),
                row.packages(),
                row.unit(),
                row.currency(),
                row.purchasePrice(),
                row.retailGross(),
                row.retailNet(),
                row.website(),
                row.imageUrl(),
                row.active(),
                rowRanges);
    }
}
