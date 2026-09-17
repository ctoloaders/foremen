package com.foremen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import com.foremen.service.model.mapper.WorkPriceServiceMapper;
import com.foremen.service.query.CustomQueryResolver;
import com.foremen.service.query.CustomQueryResolverRegistry;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Work-prices catalog service. The row/paging unit is the {@link WorkPriceEntity} aggregator, so the
 * inherited read machinery is reused unchanged for filtering and paging.
 *
 * <p>Sort is the one exception. A pivot sort arrives as the standard Spring Data
 * {@code ?sort=prices.{packageId}.{field},dir} and reaches the inherited read as a {@link Sort.Order}
 * whose property is the synthetic {@code prices.{packageId}.{field}} — which Spring Data JPA would
 * reject against the entity metamodel. So this service overrides {@link #find(Pageable, String)} and
 * {@link #findExtended(Pageable, String)} to contribute <b>only the wiring</b> for a synthetic sort
 * (FOR-04-12b, "Custom_Predicate_Flow" / sort resolution):
 *
 * <ol>
 *   <li><b>Detect</b> orders whose first path segment is a registered custom-query property for
 *       {@link WorkPriceEntity} (via {@link CustomQueryResolverRegistry}).</li>
 *   <li><b>Partition</b> the {@link Pageable} so it carries only the ordinary orders — nothing
 *       synthetic reaches the metamodel.</li>
 *   <li><b>Delegate</b> each synthetic order to the same registered
 *       {@link CustomQueryResolver#toSort(Sort.Order) resolver.toSort(order)}, which parses/validates
 *       the key, JOINs {@code packagePrices}, applies the {@code orderBy} as a side effect, and returns
 *       the {@code offerPackage.id == {packageId}} discriminator.</li>
 *   <li><b>Compose</b> each returned discriminator onto {@link #buildFinalSpecification(String)}.</li>
 *   <li><b>Delegate</b> to the inherited read: {@code findAll(spec, cleanedPageable)} then map.</li>
 * </ol>
 *
 * <p>No parse/join/orderBy logic lives here — those specifics are owned by the resolver so filter and
 * sort cannot drift. When there is no synthetic order the overrides fall straight through to the
 * inherited {@code super}-equivalent read.
 */
@Service
@RequiredArgsConstructor
@Getter
public class WorkPriceService implements AdminService<
        WorkPriceServiceModel, WorkPriceServiceExtendedModel, WorkPriceEntity, Long> {

    private final WorkPriceDao dao;
    private final WorkPriceServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final CustomQueryResolverRegistry customQueryResolverRegistry;
    private final Class<WorkPriceEntity> daoModelClass = WorkPriceEntity.class;

    @Override
    public Page<WorkPriceServiceModel> find(Pageable pageable, String rawQuery) {
        PivotSortWiring wiring = wireSyntheticSort(pageable, rawQuery);
        if (wiring == null) {
            return AdminService.super.find(pageable, rawQuery);
        }
        return getReadDao().findAll(wiring.spec(), wiring.pageable())
                .map(getMapper()::toServiceModel);
    }

    @Override
    public Page<WorkPriceServiceExtendedModel> findExtended(Pageable pageable, String rawQuery) {
        PivotSortWiring wiring = wireSyntheticSort(pageable, rawQuery);
        if (wiring == null) {
            return AdminService.super.findExtended(pageable, rawQuery);
        }
        return getReadDao().findAll(wiring.spec(), wiring.pageable())
                .map(getMapper()::toServiceExtendedModel);
    }

    /** The cleaned {@link Pageable} (synthetic orders removed) plus the discriminator-composed spec. */
    private record PivotSortWiring(Pageable pageable, Specification<WorkPriceEntity> spec) {
    }

    /**
     * Detects synthetic pivot sort orders and, when present, partitions them out of the
     * {@link Pageable} and composes each resolver discriminator onto the final specification. Returns
     * {@code null} when the request carries no synthetic order, so the caller can fall through to the
     * inherited read unchanged.
     */
    private PivotSortWiring wireSyntheticSort(Pageable pageable, String rawQuery) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return null;
        }

        List<Sort.Order> ordinaryOrders = new ArrayList<>();
        List<Sort.Order> syntheticOrders = new ArrayList<>();
        for (Sort.Order order : sort) {
            if (customQueryResolverRegistry.isRegistered(getDaoModelClass(), leadingSegment(order.getProperty()))) {
                syntheticOrders.add(order);
            } else {
                ordinaryOrders.add(order);
            }
        }

        if (syntheticOrders.isEmpty()) {
            return null;
        }

        Sort cleanedSort = ordinaryOrders.isEmpty() ? Sort.unsorted() : Sort.by(ordinaryOrders);
        Pageable cleanedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                processSort(cleanedSort));

        Specification<WorkPriceEntity> spec = buildFinalSpecification(rawQuery);
        for (Sort.Order syntheticOrder : syntheticOrders) {
            CustomQueryResolver<WorkPriceEntity> resolver = customQueryResolverRegistry
                    .find(getDaoModelClass(), leadingSegment(syntheticOrder.getProperty()))
                    .orElseThrow(); // presence already confirmed by isRegistered above
            spec = Specification.where(spec).and(resolver.toSort(syntheticOrder));
        }

        return new PivotSortWiring(cleanedPageable, spec);
    }

    /** The first dot-separated segment of a sort property (the whole property when there is no dot). */
    private static String leadingSegment(String property) {
        int dot = property.indexOf('.');
        return dot < 0 ? property : property.substring(0, dot);
    }

    // ---------------------------------------------------------------------------------------------
    // Audit snapshot override (FOR-04-12b).
    //
    // The generic AdminService audit path serializes the whole JPA entity with a shared ObjectMapper.
    // For WorkPriceEntity that recurses forever: WorkPriceEntity.packagePrices -> WorkPackagePriceEntity
    // .workPrice -> back to WorkPriceEntity ... which throws and stores the useless fallback
    //   {"error":"serialization_failed","class":"WorkPriceEntity"}.
    //
    // Instead, this service overrides ONLY the single-entity serialization seam (serializeEntity) so the
    // inherited create/update/delete transactional bodies run unchanged and route snapshot construction
    // through here. serializeUpdateAfterSnapshot is left at its AdminService default (serializeEntity(after)).
    //
    // Snapshot representation: a FLAT, SYMMETRIC map used for EVERY state (create-after, delete-before,
    // update-before AND update-after). Top-level keys are the entity scalars plus the price map EXPANDED
    // into flat top-level keys, keyed by offer-package CODE with value = that member's netPrice:
    //
    //   { "id": 1, "workItemId": 42, "budget": 9.90, "norm": 9.90, "lux": 9.90 }
    //
    // No nested packagePrices array, no currencyCode/offerPackageId, no back-reference (cycle-free).
    // Because before and after share this flat shape, the generic top-level key-by-key diff in the audit
    // UI (compute-diff.ts) naturally yields one changed row per package whose price differs (e.g.
    // "budget: 9.90 -> 9.91") and leaves id/workItemId/unchanged packages as unchanged. The service no
    // longer computes any diff itself — that is the UI's job.
    // ---------------------------------------------------------------------------------------------

    /**
     * Cycle-free, flat single-entity snapshot seam (used for CREATE-after, DELETE-before, and both the
     * before and after of an UPDATE via the inherited {@code serializeUpdateAfterSnapshot} default).
     * Overrides the generic whole-entity serialization that would recurse through the
     * {@code packagePrices <-> workPrice} back-reference.
     */
    @Override
    public String serializeEntity(WorkPriceEntity entity) {
        if (entity == null) {
            return null;
        }
        return serializeSnapshot(buildSnapshot(entity));
    }

    /**
     * Builds a flat, cycle-free snapshot of a {@link WorkPriceEntity}: {@code id}, {@code workItemId},
     * then one top-level key per package using {@code offerPackage.getCode()} with value =
     * {@code netPrice}. No nested arrays, no currency/package ids, no back-reference. If two members
     * share a code, last wins.
     */
    private Map<String, Object> buildSnapshot(WorkPriceEntity entity) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", entity.getId());
        snap.put("workItemId", entity.getWorkItem() != null ? entity.getWorkItem().getId() : null);

        List<WorkPackagePriceEntity> members = entity.getPackagePrices();
        if (members != null) {
            for (WorkPackagePriceEntity member : members) {
                if (member == null) {
                    continue;
                }
                OfferPackageEntity pkg = member.getOfferPackage();
                if (pkg != null && pkg.getCode() != null) {
                    snap.put(pkg.getCode(), member.getNetPrice());
                }
            }
        }
        return snap;
    }

    /**
     * Serializes a flat, cycle-free snapshot map with the shared audit mapper, mirroring the try/catch
     * fallback pattern used by {@code RoleService}. {@code null} in yields {@code null} out (so a
     * CREATE's before / a DELETE's after stay null).
     */
    private String serializeSnapshot(Map<String, Object> snap) {
        if (snap == null) {
            return null;
        }
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"WorkPriceEntity\"}";
        }
    }
}
