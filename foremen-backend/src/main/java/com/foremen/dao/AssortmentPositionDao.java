package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AssortmentPositionEntity;

/**
 * Plain {@link AdminDao} for {@link AssortmentPositionEntity} (FOR-05-04-UI assortment rework): a
 * GLOBAL, per-group, material-type-backed assortment position shared across all offer packages.
 *
 * <p>No extra queries beyond the generic FOR-04-01 query DSL: the catalog is small (a curated,
 * twice-yearly-reviewed assortment), so {@link AdminDao#findAll()} is the natural way to load
 * every position for the package zł/m² computation and the grouped editor — the service groups
 * the loaded rows by assortment group id itself.
 */
@Repository
public interface AssortmentPositionDao extends AdminDao<AssortmentPositionEntity, Long> {
}
