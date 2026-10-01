package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.OfferNegotiationRoundEntity;

/**
 * DAO for {@link OfferNegotiationRoundEntity} (FOR-05-07, Requirements 4.1, 4.6), following the
 * {@code OfferDao}/{@code OfferDiscountDao} finder convention: the generic {@link AdminDao} CRUD
 * surface backing the negotiation-round mutators in {@code NegotiationService}.
 *
 * <p>A round is a collection child of its {@link com.foremen.dao.model.OfferEntity} (owner FK
 * {@code offer_id}, ON DELETE CASCADE) and resolves to a project through {@code offer.project.id};
 * the thread is normally reached through the parent offer's {@code negotiationRounds} collection, so
 * this repository exposes only the standard CRUD surface for persisting a round row and no bespoke
 * finders (rounds are never deleted, R4.6).
 */
@Repository
public interface OfferNegotiationRoundDao extends AdminDao<OfferNegotiationRoundEntity, Long> {
}
