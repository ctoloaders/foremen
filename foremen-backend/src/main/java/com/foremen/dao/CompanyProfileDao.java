package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.CompanyProfileEntity;

/**
 * DAO for {@link CompanyProfileEntity} (FOR-05-08, Requirement 3.3 / design decision 7), following
 * the {@code OfferDiscountDao} convention: the generic {@link AdminDao} CRUD surface only.
 *
 * <p>The "single-row" intent is a service-layer invariant — {@code CompanyRequisitesResolver} reads
 * the one row — so this repository exposes just the standard CRUD surface and no bespoke finders.
 */
@Repository
public interface CompanyProfileDao extends AdminDao<CompanyProfileEntity, Long> {
}
