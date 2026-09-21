package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.EstimateLinePackagePriceEntity;

/**
 * DAO for {@link EstimateLinePackagePriceEntity} (FOR-05-03, Requirements 4, 5, 6). The generic
 * {@link AdminDao} CRUD surface is sufficient here: {@code EstimateLineService} persists each
 * transient row returned by {@code PackagePriceSnapshotService.snapshotForLine} via the inherited
 * {@code save}, and a line's per-package prices are otherwise read through the generic list
 * endpoint (filtered by {@code lineId}).
 */
@Repository
public interface EstimateLinePackagePriceDao extends AdminDao<EstimateLinePackagePriceEntity, Long> {
}
