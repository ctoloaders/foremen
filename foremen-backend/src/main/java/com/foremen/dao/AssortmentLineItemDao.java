package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AssortmentLineItemEntity;

/**
 * Plain {@link AdminDao} for {@link AssortmentLineItemEntity} (FOR-05-04, Requirement 6.1, 6.2):
 * one curated catalog row within an {@code AssortmentGroup}, carrying min/avg/max price per
 * {@code OfferPackage} and a quantity assumed against the 50 m² reference apartment.
 *
 * <p>No extra queries beyond the generic FOR-04-01 query DSL: the catalog is small enough (a
 * curated, twice-yearly-reviewed assortment, not a high-volume table) that
 * {@link AdminDao#findAll()} is the natural way to load every line item for the package zł/m²
 * computation (Requirement 6.3, 6.4, 6.5) — the resolver groups the loaded rows by assortment
 * group id itself (see {@code AssortmentLineItemService#computePackageZlM2}).
 */
@Repository
public interface AssortmentLineItemDao extends AdminDao<AssortmentLineItemEntity, Long> {
}
