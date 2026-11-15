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

    /**
     * Deadline sweep (FOR-11-04 R14.2): positions whose order-by date has been reached while still before
     * {@code ORDERED} ({@code PLANNED} ... {@code WITH_BUYER}), not {@code CLIENT_OWN}, and whose one-shot
     * marker differs from the current order-by date (a new date re-arms it). The project is fetched.
     */
    @Query("SELECT p FROM PurchasePositionEntity p JOIN FETCH p.project "
            + "WHERE p.orderByDate IS NOT NULL AND p.orderByDate <= :today "
            + "AND p.status IN (com.foremen.dao.model.PositionStatus.PLANNED, "
            + "com.foremen.dao.model.PositionStatus.PROPOSED, com.foremen.dao.model.PositionStatus.ACCEPTED, "
            + "com.foremen.dao.model.PositionStatus.IN_PROFORMA, com.foremen.dao.model.PositionStatus.CLIENT_PAID, "
            + "com.foremen.dao.model.PositionStatus.WITH_BUYER) "
            + "AND p.sourcingPath <> com.foremen.dao.model.SourcingPath.CLIENT_OWN "
            + "AND (p.orderByNotifiedFor IS NULL OR p.orderByNotifiedFor <> p.orderByDate) "
            + "ORDER BY p.id")
    List<PurchasePositionEntity> findOrderByMissedCandidates(@Param("today") java.time.LocalDate today);

    /** Assigned buyer id of the position's purchase request; null when unassigned or no request. */
    @Query("SELECT b.id FROM PurchasePositionEntity p LEFT JOIN p.purchaseRequest r "
            + "LEFT JOIN r.assignedBuyer b WHERE p.id = :positionId")
    Long findAssignedBuyerId(@Param("positionId") Long positionId);
}
