package com.foremen.service.pricing;

import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.MaterialBatchLookup.BatchKey;
import com.foremen.service.pricing.MaterialRangeResolver.BatchProvider;
import com.foremen.service.pricing.MaterialRangeResolver.BranchRanges;
import com.foremen.service.pricing.MaterialRangeResolver.ConsumptionRowInput;
import com.foremen.service.pricing.MaterialRangeResolver.MoneyRange;
import com.foremen.service.pricing.SeededOfferPackages.OfferPackageInfo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the FOR-04-19 work-catalog pivot: per {@link com.foremen.dao.model.WorkItemEntity} a
 * {@code Map<Long /*offerPackageId*&#47;, PackageCell>} carrying, for every seeded offer package, the
 * THREE prices for that {@code (work, package)} (Requirement 5.1):
 * <ol>
 *   <li><b>(a) labour price</b> — the existing FOR-04-12b {@code WorkPackagePrice} net price for
 *       that work + package, resolved (not recomputed) via {@link EffectivePriceResolver} over the
 *       work item's {@code WorkPrice.packagePrices};</li>
 *   <li><b>(b) construction range</b> and <b>(c) finishing range</b> — the branch money ranges from
 *       {@link MaterialRangeResolver#compute}, each an explicit {@link MoneyRange#ZERO} when its
 *       branch has no consumption for the {@code (work, package)} (Requirement 5.1, 5.2).</li>
 * </ol>
 *
 * <p>The aggregation is built for a WHOLE page of work items at once so it never triggers an N+1
 * (Requirement 5.3 keeps the list paginated over distinct work items — nothing here splits a row):
 * <ul>
 *   <li>the page's consumption rows load in ONE {@code IN} query
 *       ({@link WorkMaterialConsumptionDao#findByWorkItemIdIn});</li>
 *   <li>the page's work-price aggregators load in ONE {@code IN} query
 *       ({@link WorkPriceDao#findByWorkItemIdIn});</li>
 *   <li>the analog batches load via {@link MaterialBatchLookup#load} (one query per branch).</li>
 * </ul>
 *
 * <p>The result contains one {@link PackageCell} per (work item × seeded package) even when the work
 * item has neither a price nor any consumption for a package: the labour price is then {@code null}
 * (unpriced) and both ranges are the explicit {@code 0..0} the frontend renders as {@code 0}.
 */
@Component
public class WorkCatalogAggregationResolver {

    /**
     * The three prices surfaced for one {@code (work item, offer package)} on the work-catalog pivot.
     *
     * @param offerPackageId the seeded offer package this cell is for (the pivot key)
     * @param labourPrice    the FOR-04-12b effective labour net price, or {@code null} when the work
     *                       item is unpriced
     * @param construction   the construction-branch material money range (never {@code null};
     *                       {@code 0..0} when the branch has no consumption)
     * @param finishing      the finishing-branch material money range (never {@code null};
     *                       {@code 0..0} when the branch has no consumption)
     */
    public record PackageCell(Long offerPackageId,
                              BigDecimal labourPrice,
                              MoneyRange construction,
                              MoneyRange finishing) {
    }

    private final WorkMaterialConsumptionDao consumptionDao;
    private final WorkPriceDao workPriceDao;
    private final MaterialBatchLookup materialBatchLookup;
    private final EffectivePriceResolver effectivePriceResolver;
    private final SeededOfferPackages seededOfferPackages;

    public WorkCatalogAggregationResolver(WorkMaterialConsumptionDao consumptionDao,
                                          WorkPriceDao workPriceDao,
                                          MaterialBatchLookup materialBatchLookup,
                                          EffectivePriceResolver effectivePriceResolver,
                                          SeededOfferPackages seededOfferPackages) {
        this.consumptionDao = consumptionDao;
        this.workPriceDao = workPriceDao;
        this.materialBatchLookup = materialBatchLookup;
        this.effectivePriceResolver = effectivePriceResolver;
        this.seededOfferPackages = seededOfferPackages;
    }

    /**
     * Builds the pivot for a page of work items: {@code workItemId -> (offerPackageId -> PackageCell)}.
     *
     * <p>For each work item and each seeded offer package the returned inner map holds a
     * {@link PackageCell} carrying the labour price (or {@code null} when unpriced) and both branch
     * ranges (each {@code 0..0} when the branch has no consumption). Every work item in
     * {@code workItemIds} is present in the outer map (with an entry per seeded package), so the
     * frontend can render every pivot cell.
     *
     * @param workItemIds the ids of the work items on the current page (may be {@code null}/empty)
     * @return the pivot map, keyed by work-item id then offer-package id
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<Long, PackageCell>> resolve(Collection<Long> workItemIds) {
        Map<Long, Map<Long, PackageCell>> result = new LinkedHashMap<>();
        if (workItemIds == null || workItemIds.isEmpty()) {
            return result;
        }

        // De-duplicate + preserve caller order for a deterministic result.
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : workItemIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return result;
        }

        List<OfferPackageInfo> packages = seededOfferPackages.all();

        // ---- ONE IN query: consumption rows for the whole page, grouped (workItemId, offerPackageId).
        List<WorkMaterialConsumptionEntity> rows = consumptionDao.findByWorkItemIdIn(ids);
        Map<Long, Map<Long, List<ConsumptionRowInput>>> consumptionByWorkAndPackage = new LinkedHashMap<>();
        Set<BatchKey> batchKeys = new LinkedHashSet<>();
        for (WorkMaterialConsumptionEntity row : rows) {
            if (row == null || row.getWorkItem() == null || row.getWorkItem().getId() == null
                    || row.getOfferPackage() == null || row.getOfferPackage().getId() == null
                    || row.getBranch() == null) {
                continue;
            }
            Long workItemId = row.getWorkItem().getId();
            Long offerPackageId = row.getOfferPackage().getId();
            Long typeId = materialTypeId(row);
            if (typeId == null) {
                continue; // XOR guaranteed at the write path; skip a malformed row defensively
            }
            consumptionByWorkAndPackage
                    .computeIfAbsent(workItemId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(offerPackageId, k -> new ArrayList<>())
                    .add(new ConsumptionRowInput(row.getBranch(), typeId, row.getNormQty()));
            batchKeys.add(new BatchKey(offerPackageId, typeId, row.getBranch()));
        }

        // ---- Analog batches (one query per branch present) → a pure BatchProvider for the resolver.
        BatchProvider batchProvider =
                MaterialRangeResolver.providerOf(materialBatchLookup.load(batchKeys));

        // ---- ONE IN query: work-price aggregators for the whole page, indexed by workItemId.
        Map<Long, List<WorkPackagePriceEntity>> pricesByWorkItem = new LinkedHashMap<>();
        for (WorkPriceEntity workPrice : workPriceDao.findByWorkItemIdIn(ids)) {
            if (workPrice == null || workPrice.getWorkItem() == null
                    || workPrice.getWorkItem().getId() == null) {
                continue;
            }
            pricesByWorkItem.put(workPrice.getWorkItem().getId(), workPrice.getPackagePrices());
        }

        // ---- Build one PackageCell per (work item × seeded package).
        for (Long workItemId : ids) {
            Map<Long, List<ConsumptionRowInput>> byPackage =
                    consumptionByWorkAndPackage.getOrDefault(workItemId, Map.of());
            List<WorkPackagePriceEntity> packagePrices = pricesByWorkItem.get(workItemId);

            Map<Long, PackageCell> cells = new LinkedHashMap<>();
            for (OfferPackageInfo pkg : packages) {
                Long offerPackageId = pkg.id();

                BigDecimal labourPrice = effectivePriceResolver
                        .resolve(packagePrices, pkg.code())
                        .orElse(null);

                List<ConsumptionRowInput> rowsForPackage =
                        byPackage.getOrDefault(offerPackageId, List.of());
                BranchRanges ranges =
                        MaterialRangeResolver.compute(offerPackageId, rowsForPackage, batchProvider);

                cells.put(offerPackageId, new PackageCell(
                        offerPackageId, labourPrice, ranges.construction(), ranges.finishing()));
            }
            result.put(workItemId, Collections.unmodifiableMap(cells));
        }

        return result;
    }

    /** Returns whichever of the two material-type references is set (XOR at the write path), or null. */
    private static Long materialTypeId(WorkMaterialConsumptionEntity row) {
        if (row.getBranch() == ConsumptionBranch.construction) {
            return row.getConstructionMaterialType() == null
                    ? null : row.getConstructionMaterialType().getId();
        }
        if (row.getBranch() == ConsumptionBranch.finishing) {
            return row.getFinishingMaterialType() == null
                    ? null : row.getFinishingMaterialType().getId();
        }
        return null;
    }
}
