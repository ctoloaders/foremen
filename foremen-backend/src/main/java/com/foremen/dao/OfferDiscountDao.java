package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.OfferDiscountEntity;

/**
 * DAO for {@link OfferDiscountEntity} (FOR-05-07, Requirements 2.1, 2.2), following the
 * {@code OfferDao} finder convention: the generic {@link AdminDao} CRUD surface backing the discount
 * write/validation/totals-recompute paths in {@code OfferDiscountService}.
 *
 * <p>An {@code OfferDiscount} is a collection child of its {@link com.foremen.dao.model.OfferEntity}
 * (owner FK {@code offer_id}, ON DELETE CASCADE) and resolves to a project through
 * {@code offer.project.id}; direct discount reads are normally reached through the parent offer's
 * {@code discounts} collection, so this repository exposes only the standard CRUD surface for
 * persisting/removing a discount row and no bespoke finders (added later if a need appears).
 */
@Repository
public interface OfferDiscountDao extends AdminDao<OfferDiscountEntity, Long> {
}
