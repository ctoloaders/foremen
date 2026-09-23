package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AssortmentPositionPriceEntity;

/**
 * Plain {@link AdminDao} for {@link AssortmentPositionPriceEntity} (FOR-05-04-UI assortment
 * rework): the per-(position, offer package) min/avg/max price row.
 *
 * <p>No extra queries beyond the generic FOR-04-01 query DSL: the price rows are loaded in bulk
 * via {@link AdminDao#findAll()} and indexed in memory by (position id, package code) by the
 * package editor / zł/m² recompute in {@code AssortmentPositionService}.
 */
@Repository
public interface AssortmentPositionPriceDao extends AdminDao<AssortmentPositionPriceEntity, Long> {
}
