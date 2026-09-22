package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.FinishingMaterialEntity;

@Repository
public interface FinishingMaterialDao extends AdminDao<FinishingMaterialEntity, Long> {

    /**
     * Loads the ACTIVE, priced finishing materials that feed the FOR-04-19 computed money range: a
     * finishing material qualifies when {@code active = true} AND {@code retailNet} is non-null. The
     * {@code packages} and {@code type} associations are fetched eagerly via an {@link EntityGraph}
     * so {@code MaterialBatchLookup} can group each material by {@code (offerPackage, type)} without
     * a lazy-load per row (no N+1). {@code DISTINCT} de-duplicates the row explosion introduced by
     * the {@code packages} join.
     *
     * @return the active finishing materials with a non-null {@code retailNet}, with {@code packages}
     * + {@code type} initialized
     */
    @EntityGraph(attributePaths = {"packages", "type"})
    @Query("SELECT DISTINCT m FROM FinishingMaterialEntity m WHERE m.active = true AND m.retailNet IS NOT NULL")
    List<FinishingMaterialEntity> findByActiveTrueAndRetailNetNotNull();

    /**
     * Loads the FULL analog batch for the FOR-04-19 consumption drill-in (Requirement 5.4,
     * package-less per FOR-05-04 Requirement 7.2): every ACTIVE finishing material of one of the
     * given types ({@code type.id IN :typeIds}), regardless of which offer package(s) it belongs to
     * — {@code WorkMaterialConsumption} no longer carries an {@code offerPackage} dimension, so the
     * analog batch is no longer filtered by package. Unlike {@link #findByActiveTrueAndRetailNetNotNull()}
     * this does NOT filter on {@code retailNet}: an unpriced material still appears in the drill-in
     * list (with a {@code null} cost) while being excluded from the type-level band's MIN/MAX by the
     * range computation.
     *
     * <p>The {@code type}, {@code material} (the display name source), {@code producer} and
     * {@code unit} associations are fetched eagerly via an {@link EntityGraph} so the enrichment
     * resolver can render each material's name/type/producer/unit without a lazy-load per row.
     * A finishing material has no {@code seller}, so none is fetched. The materials are ordered by id
     * for a deterministic drill-in list. An empty {@code typeIds} yields an empty list without a
     * query.
     *
     * @param typeIds the analog-group finishing material type ids to load
     * @return the active finishing materials of those types, with
     *         {@code type}/{@code material}/{@code producer}/{@code unit} initialized
     */
    @EntityGraph(attributePaths = {"type", "material", "producer", "unit"})
    @Query("SELECT DISTINCT m FROM FinishingMaterialEntity m "
            + "WHERE m.active = true AND m.type.id IN :typeIds "
            + "ORDER BY m.id")
    List<FinishingMaterialEntity> findActiveByTypes(@Param("typeIds") List<Long> typeIds);
}
