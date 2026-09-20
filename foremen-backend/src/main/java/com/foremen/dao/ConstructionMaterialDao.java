package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ConstructionMaterialEntity;

@Repository
public interface ConstructionMaterialDao extends AdminDao<ConstructionMaterialEntity, Long> {

    /**
     * Loads the ACTIVE, priced construction materials that feed the computed price range (FOR-04-17,
     * Requirement 6): {@code active = true} AND {@code retailNet} is non-null. The {@code packages}
     * and {@code type} associations are fetched eagerly via an {@link EntityGraph} so
     * {@code PriceRangeResolver} (task 7.1) can fan each material out to {@code (package, type)}
     * without tripping a lazy-load per row (no N+1). {@code DISTINCT} de-duplicates the row explosion
     * introduced by the {@code packages} join.
     *
     * @return the active materials with a non-null {@code retailNet}, with {@code packages} + {@code type} initialized
     */
    @EntityGraph(attributePaths = {"packages", "type"})
    @Query("SELECT DISTINCT m FROM ConstructionMaterialEntity m WHERE m.active = true AND m.retailNet IS NOT NULL")
    List<ConstructionMaterialEntity> findByActiveTrueAndRetailNetNotNull();

    /**
     * Loads the FULL analog batch for the FOR-04-19 consumption drill-in (Requirement 5.4): every
     * ACTIVE construction material of one of the given types ({@code type.id IN :typeIds}) that
     * belongs to the given offer package ({@code :offerPackageId} present in its {@code packages}
     * set). Unlike {@link #findByActiveTrueAndRetailNetNotNull()} this does NOT filter on
     * {@code retailNet}: an unpriced material still appears in the drill-in list (with a {@code null}
     * cost) while being excluded from the type-level band's MIN/MAX by the range computation.
     *
     * <p>The {@code type}, {@code producer}, {@code seller} and {@code unit} associations are fetched
     * eagerly via an {@link EntityGraph} so the enrichment resolver can render each material's
     * type/producer/seller/unit without a lazy-load per row. The materials are ordered by id for a
     * deterministic drill-in list. An empty {@code typeIds} yields an empty list without a query.
     *
     * @param offerPackageId the row's offer package id
     * @param typeIds        the analog-group construction material type ids to load
     * @return the active construction materials of those types in that package, with
     *         {@code type}/{@code producer}/{@code seller}/{@code unit} initialized
     */
    @EntityGraph(attributePaths = {"type", "producer", "seller", "unit"})
    @Query("SELECT DISTINCT m FROM ConstructionMaterialEntity m JOIN m.packages p "
            + "WHERE m.active = true AND p.id = :offerPackageId AND m.type.id IN :typeIds "
            + "ORDER BY m.id")
    List<ConstructionMaterialEntity> findActiveByPackageAndTypes(
            @Param("offerPackageId") Long offerPackageId,
            @Param("typeIds") List<Long> typeIds);
}
