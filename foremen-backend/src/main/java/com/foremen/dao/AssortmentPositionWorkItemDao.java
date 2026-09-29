package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AssortmentPositionWorkItemEntity;

/**
 * Repository for {@link AssortmentPositionWorkItemEntity} (FOR-05-05 Wave 1b, #8): the PER-package
 * assortment-position -> work-item links.
 *
 * <p>A plain {@link JpaRepository} keyed on the composite
 * {@link AssortmentPositionWorkItemEntity.PositionPackageId} (this join is not a managed admin CRUD
 * resource, so it does NOT extend {@code AdminDao}). Reads batch-load the links for a set of
 * positions ({@link #findByPosition_IdIn(Collection)}) so the editor read and the merge avoid an
 * N+1, and the dedicated per-package-links write path resolves a single position's links via
 * {@link #findByPosition_Id(Long)}.
 */
@Repository
public interface AssortmentPositionWorkItemDao
        extends JpaRepository<AssortmentPositionWorkItemEntity,
        AssortmentPositionWorkItemEntity.PositionPackageId> {

    /** All per-package work links for the given assortment position ids (batch, no N+1). */
    List<AssortmentPositionWorkItemEntity> findByPosition_IdIn(Collection<Long> positionIds);

    /** All per-package work links for a single assortment position. */
    List<AssortmentPositionWorkItemEntity> findByPosition_Id(Long positionId);
}
