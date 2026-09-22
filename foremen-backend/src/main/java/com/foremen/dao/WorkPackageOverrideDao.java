package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkPackageOverrideEntity;

/**
 * Plain {@link AdminDao} for {@link WorkPackageOverrideEntity} (FOR-05-04, task 18.3): a
 * {@code (WorkItem, OfferPackage)} pair's package membership flag and optional override formula.
 * The generic FOR-04-01 query DSL (e.g. {@code workItem.id==}, {@code offerPackage.id==}) is
 * sufficient for list/lookup, mirroring {@code WorkMaterialConsumptionDao}.
 *
 * <p>{@link #findByWorkItemIdAndOfferPackageId} and {@link #findAllWithWorkItemAndPackage}
 * additionally support the catalog-wide cycle check on save (task 20.1, R3.3) and package-context
 * formula resolution (task 20.1, R4.2, R5.3).
 */
@Repository
public interface WorkPackageOverrideDao extends AdminDao<WorkPackageOverrideEntity, Long> {

    @Query("SELECT o FROM WorkPackageOverrideEntity o "
            + "WHERE o.workItem.id = :workItemId AND o.offerPackage.id = :offerPackageId")
    Optional<WorkPackageOverrideEntity> findByWorkItemIdAndOfferPackageId(
            @Param("workItemId") Long workItemId, @Param("offerPackageId") Long offerPackageId);

    @Query("SELECT o FROM WorkPackageOverrideEntity o JOIN FETCH o.workItem JOIN FETCH o.offerPackage")
    List<WorkPackageOverrideEntity> findAllWithWorkItemAndPackage();
}
