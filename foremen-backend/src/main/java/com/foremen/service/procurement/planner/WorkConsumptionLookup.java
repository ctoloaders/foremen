package com.foremen.service.procurement.planner;

import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkMaterialConsumptionDao.TypeCategoryRow;
import com.foremen.dao.model.EstimateLineEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batched loaders of the material type to work category maps used by {@link WorkCategoryResolver}
 * (FOR-11-01 R5.2, R5.3). Finishing branch only.
 */
@Component
@RequiredArgsConstructor
public class WorkConsumptionLookup {

    private final WorkMaterialConsumptionDao consumptionDao;
    private final EstimateLineDao estimateLineDao;

    /** Tier 1: types consumed by the works of the project's estimate, with their categories. */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> estimateCategoriesByType(Long projectId) {
        if (projectId == null) {
            return Map.of();
        }
        Set<Long> workItemIds = new HashSet<>();
        for (EstimateLineEntity line : estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId)) {
            if (line.getWorkItem() != null && line.getWorkItem().getId() != null) {
                workItemIds.add(line.getWorkItem().getId());
            }
        }
        if (workItemIds.isEmpty()) {
            return Map.of();
        }
        return group(consumptionDao.findFinishingTypeCategoryPairsByWorkItemIdIn(workItemIds));
    }

    /** Tier 1 from already known work item ids (one query, no estimate load). */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> categoriesByTypeForWorkItems(Collection<Long> workItemIds) {
        if (workItemIds == null || workItemIds.isEmpty()) {
            return Map.of();
        }
        return group(consumptionDao.findFinishingTypeCategoryPairsByWorkItemIdIn(workItemIds));
    }

    /**
     * Tier 2: catalog-wide categories, queried only for the types that tier 1 left without a result.
     * {@code unresolvedTypeIds} must already exclude the types resolved by tier 1.
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> catalogCategoriesByType(Collection<Long> unresolvedTypeIds) {
        if (unresolvedTypeIds == null || unresolvedTypeIds.isEmpty()) {
            return Map.of();
        }
        return group(consumptionDao.findFinishingTypeCategoryPairsByTypeIdIn(new HashSet<>(unresolvedTypeIds)));
    }

    private static Map<Long, Set<Long>> group(List<TypeCategoryRow> rows) {
        Map<Long, Set<Long>> result = new HashMap<>();
        for (TypeCategoryRow row : rows) {
            if (row.typeId() != null && row.categoryId() != null) {
                result.computeIfAbsent(row.typeId(), k -> new HashSet<>()).add(row.categoryId());
            }
        }
        return result;
    }
}
