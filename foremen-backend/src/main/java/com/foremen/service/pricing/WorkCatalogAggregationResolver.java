package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.MaterialBatchLookup.BatchKey;
import com.foremen.service.pricing.MaterialRangeResolver.BatchProvider;
import com.foremen.service.pricing.MaterialRangeResolver.BranchRanges;
import com.foremen.service.pricing.MaterialRangeResolver.ConsumptionRowInput;
import com.foremen.service.pricing.MaterialRangeResolver.MoneyRange;

/**
 * Assembles the FOR-04-19 work-catalog aggregation, collapsed to a package-less shape by FOR-05-04
 * (Requirement 7.1): per {@link com.foremen.dao.model.WorkItemEntity} a single {@link WorkCostCell}
 * carrying the THREE parts of a work item's total estimate cost (Requirement 7.4):
 * <ol>
 *   <li><b>(a) labour price</b> — the work item's single {@code WorkPrice.netPrice} (FOR-05-04,
 *       Requirement 1), read directly with no per-package resolution;</li>
 *   <li><b>(b) construction range</b> and <b>(c) finishing range</b> — the branch money ranges from
 *       {@link MaterialRangeResolver#compute}, each an explicit {@link MoneyRange#ZERO} when its
 *       branch has no consumption for the work item (Requirement 7.5 — no fabricated fallback; a
 *       branch is either norm-covered or explicitly unpriced).</li>
 * </ol>
 *
 * <p>Neither {@code WorkMaterialConsumption} nor {@code WorkPrice} carry an offer-package dimension
 * any more (FOR-05-04 Requirements 1.1, 7.2), so there is exactly ONE labour price and ONE pair of
 * branch ranges per work item — the former per-seeded-package pivot is gone.
 *
 * <p>The aggregation is built for a WHOLE page of work items at once so it never triggers an N+1
 * (Requirement 5.3 keeps the list paginated over distinct work items — nothing here splits a row):
 * <ul>
 *   <li>the page's consumption rows load in ONE {@code IN} query
 *       ({@link WorkMaterialConsumptionDao#findByWorkItemIdIn});</li>
 *   <li>the page's work-price rows load in ONE {@code IN} query
 *       ({@link WorkPriceDao#findByWorkItemIdIn});</li>
 *   <li>the analog batches load via {@link MaterialBatchLookup#load} (one query per branch).</li>
 * </ul>
 *
 * <p>The result contains one {@link WorkCostCell} per work item even when the work item has neither
 * a price nor any consumption: the labour price is then {@code null} (unpriced) and both ranges are
 * the explicit {@code 0..0} the frontend renders as {@code 0}.
 */
@Component
public class WorkCatalogAggregationResolver {

    /**
     * The three cost parts surfaced for one work item on the work-catalog view (Requirement 7.4).
     *
     * @param labourPrice  the work item's single net price, or {@code null} when the work item is
     *                     unpriced
     * @param construction the construction-branch material money range (never {@code null};
     *                     {@code 0..0} when the branch has no consumption)
     * @param finishing    the finishing-branch material money range (never {@code null};
     *                     {@code 0..0} when the branch has no consumption)
     */
    public record WorkCostCell(BigDecimal labourPrice, MoneyRange construction, MoneyRange finishing) {
    }

    private final WorkMaterialConsumptionDao consumptionDao;
    private final WorkPriceDao workPriceDao;
    private final MaterialBatchLookup materialBatchLookup;

    public WorkCatalogAggregationResolver(WorkMaterialConsumptionDao consumptionDao,
                                          WorkPriceDao workPriceDao,
                                          MaterialBatchLookup materialBatchLookup) {
        this.consumptionDao = consumptionDao;
        this.workPriceDao = workPriceDao;
        this.materialBatchLookup = materialBatchLookup;
    }

    /**
     * Builds the per-work-item cost cell for a page of work items: {@code workItemId -> WorkCostCell}.
     *
     * <p>Every work item in {@code workItemIds} is present in the returned map (labour price
     * {@code null} and both ranges {@code 0..0} when the work item has neither a price nor any
     * consumption), so the frontend can render every row.
     *
     * @param workItemIds the ids of the work items on the current page (may be {@code null}/empty)
     * @return the map, keyed by work-item id
     */
    @Transactional(readOnly = true)
    public Map<Long, WorkCostCell> resolve(Collection<Long> workItemIds) {
        Map<Long, WorkCostCell> result = new LinkedHashMap<>();
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

        // ---- ONE IN query: consumption rows for the whole page, grouped by workItemId (no
        // offerPackage dimension left on the entity, R7.2).
        List<WorkMaterialConsumptionEntity> rows = consumptionDao.findByWorkItemIdIn(ids);
        Map<Long, List<ConsumptionRowInput>> consumptionByWork = new LinkedHashMap<>();
        for (WorkMaterialConsumptionEntity row : rows) {
            if (row == null || row.getWorkItem() == null || row.getWorkItem().getId() == null
                    || row.getBranch() == null) {
                continue;
            }
            Long workItemId = row.getWorkItem().getId();
            Long typeId = materialTypeId(row);
            if (typeId == null) {
                continue; // XOR guaranteed at the write path; skip a malformed row defensively
            }
            consumptionByWork
                    .computeIfAbsent(workItemId, k -> new ArrayList<>())
                    .add(new ConsumptionRowInput(row.getBranch(), typeId, row.getNormQty()));
        }

        // The analog-batch keys are built across every (type, branch) pair seen in the page's
        // consumption rows — no package axis (R7.2).
        Set<BatchKey> batchKeys = new LinkedHashSet<>();
        for (List<ConsumptionRowInput> workRows : consumptionByWork.values()) {
            for (ConsumptionRowInput rowInput : workRows) {
                batchKeys.add(new BatchKey(rowInput.materialTypeId(), rowInput.branch()));
            }
        }

        // ---- Analog batches (one query per branch present) → a pure BatchProvider for the resolver.
        BatchProvider batchProvider =
                MaterialRangeResolver.providerOf(materialBatchLookup.load(batchKeys));

        // ---- ONE IN query: single work-price rows for the whole page, indexed by workItemId.
        Map<Long, BigDecimal> labourPriceByWorkItem = new LinkedHashMap<>();
        for (WorkPriceEntity workPrice : workPriceDao.findByWorkItemIdIn(ids)) {
            if (workPrice == null || workPrice.getWorkItem() == null
                    || workPrice.getWorkItem().getId() == null) {
                continue;
            }
            labourPriceByWorkItem.put(workPrice.getWorkItem().getId(), workPrice.getNetPrice());
        }

        // ---- Build one WorkCostCell per work item.
        for (Long workItemId : ids) {
            List<ConsumptionRowInput> rowsForWork = consumptionByWork.getOrDefault(workItemId, List.of());
            BigDecimal labourPrice = labourPriceByWorkItem.get(workItemId);

            BranchRanges ranges = MaterialRangeResolver.compute(rowsForWork, batchProvider);

            result.put(workItemId, new WorkCostCell(labourPrice, ranges.construction(), ranges.finishing()));
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
