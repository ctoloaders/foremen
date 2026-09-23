package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.OfferPackageEntity;

@Repository
public interface OfferPackageDao extends AdminDao<OfferPackageEntity, Long> {

    /**
     * Looks up an offer package by its unique {@code code} (e.g. {@code "budget"}). Used by the
     * package-assortment editor/save flow to resolve and persist the denormalized zł/m² cache
     * (FOR-05-04-UI).
     */
    Optional<OfferPackageEntity> findByCode(String code);
}
