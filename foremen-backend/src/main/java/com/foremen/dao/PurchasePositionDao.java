package com.foremen.dao;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.PurchasePositionEntity;

@Repository
public interface PurchasePositionDao extends AdminDao<PurchasePositionEntity, Long> {

    /** In-use check (R7.9): true when any position uses the material as planned or actual item. */
    @Query("SELECT COUNT(p) > 0 FROM PurchasePositionEntity p "
            + "WHERE p.plannedMaterial.id = :materialId OR p.actualMaterial.id = :materialId")
    boolean existsByMaterialId(@Param("materialId") Long materialId);

    /**
     * Suggestion candidates from current options (FOR-11-00 R21.6): rows of
     * {@code [materialId, salePrice, lastChange]} for positions of the project whose planned material
     * has the given type, excluding one material. Newest first.
     */
    @Query("SELECT p.plannedMaterial.id, p.salePrice, COALESCE(p.updatedDate, p.createdDate) "
            + "FROM PurchasePositionEntity p "
            + "WHERE p.project.id = :projectId AND p.plannedMaterial.type.id = :typeId "
            + "AND p.plannedMaterial.id <> :excludeMaterialId "
            + "ORDER BY COALESCE(p.updatedDate, p.createdDate) DESC, p.id DESC")
    List<Object[]> findSuggestionCandidates(@Param("projectId") Long projectId,
                                            @Param("typeId") Long typeId,
                                            @Param("excludeMaterialId") Long excludeMaterialId,
                                            Pageable pageable);

    /** Positions of a purchase request, ordered by id. */
    @Query("SELECT p FROM PurchasePositionEntity p WHERE p.purchaseRequest.id = :requestId ORDER BY p.id")
    List<PurchasePositionEntity> findByPurchaseRequestId(@Param("requestId") Long requestId);
}
