package com.foremen.dao;

import com.foremen.dao.model.ConstructionMaterialEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
