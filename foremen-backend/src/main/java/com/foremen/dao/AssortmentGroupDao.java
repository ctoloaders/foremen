package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AssortmentGroupEntity;

/**
 * Plain {@link AdminDao} for {@link AssortmentGroupEntity} (FOR-05-04, Requirement 6.1): a
 * curated grouping of finishing/fixture line items used by the package zł/m² pricing model. No
 * extra queries — the generic FOR-04-01 query DSL is sufficient for list/lookup, mirroring
 * {@code WorkVolumeFormulaDao}.
 */
@Repository
public interface AssortmentGroupDao extends AdminDao<AssortmentGroupEntity, Long> {
}
