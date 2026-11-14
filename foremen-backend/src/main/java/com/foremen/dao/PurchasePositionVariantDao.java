package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.PurchasePositionVariantEntity;

/** FOR-11-00 (R21): variant history of purchase positions. */
@Repository
public interface PurchasePositionVariantDao extends AdminDao<PurchasePositionVariantEntity, Long> {

    /** History of a position, newest retirement first (R21.5). Material is fetched for label fields. */
    @Query("SELECT v FROM PurchasePositionVariantEntity v JOIN FETCH v.material "
            + "WHERE v.position.id = :positionId ORDER BY v.createdDate DESC, v.id DESC")
    List<PurchasePositionVariantEntity> findHistory(@Param("positionId") Long positionId);

    /** Batched variant counts: rows of {@code [positionId, count]} (no N+1 for lists, R21.2). */
    @Query("SELECT v.position.id, COUNT(v) FROM PurchasePositionVariantEntity v "
            + "WHERE v.position.id IN :positionIds GROUP BY v.position.id")
    List<Object[]> countByPositionIds(@Param("positionIds") Collection<Long> positionIds);

    /**
     * Suggestion candidates from history (R21.6): rows of
     * {@code [materialId, unitSalePrice, createdDate]} for variants of positions of the project whose
     * material has the given type, excluding one material. Newest first; the service keeps the
     * latest row per material and merges with {@link PurchasePositionDao#findSuggestionCandidates}.
     */
    @Query("SELECT v.material.id, v.unitSalePrice, v.createdDate FROM PurchasePositionVariantEntity v "
            + "WHERE v.position.project.id = :projectId AND v.material.type.id = :typeId "
            + "AND v.material.id <> :excludeMaterialId "
            + "ORDER BY v.createdDate DESC, v.id DESC")
    List<Object[]> findSuggestionCandidates(@Param("projectId") Long projectId,
                                            @Param("typeId") Long typeId,
                                            @Param("excludeMaterialId") Long excludeMaterialId,
                                            Pageable pageable);

    /** In-use check (R3.7): true when any variant references the material. */
    @Query("SELECT COUNT(v) > 0 FROM PurchasePositionVariantEntity v WHERE v.material.id = :materialId")
    boolean existsByMaterialId(@Param("materialId") Long materialId);
}
