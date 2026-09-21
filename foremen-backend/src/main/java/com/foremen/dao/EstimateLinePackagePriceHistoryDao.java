package com.foremen.dao;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.EstimateLinePackagePriceHistoryEntity;

/**
 * DAO for the append-only {@link EstimateLinePackagePriceHistoryEntity} change-capture skeleton
 * (FOR-05-03, Requirement 6). Rows are only ever inserted (via {@code save}) — never updated or
 * deleted — per {@code EstimateLinePackagePriceHistoryEntity}'s append-only contract (R6.1, R6.3).
 */
@Repository
public interface EstimateLinePackagePriceHistoryDao
        extends AdminDao<EstimateLinePackagePriceHistoryEntity, Long> {

    /**
     * Returns every history row captured for {@code packagePriceId}, so a per-package price's own
     * change history is queryable (R6.1, R6.3).
     *
     * @param packagePriceId the owning {@code EstimateLinePackagePrice} id
     * @param sort            ordering (callers typically pass {@code Sort.by("changedAt")} or
     *                        {@code Sort.by("id")} to read history in capture order)
     * @return every history row for {@code packagePriceId}, ordered per {@code sort}; never
     *         {@code null}
     */
    List<EstimateLinePackagePriceHistoryEntity> findByPackagePriceId(Long packagePriceId, Sort sort);
}
