package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkerTypeEntity;

/**
 * FOR-05-06 — WorkerType repository. Beyond the generic {@link AdminDao} CRUD it exposes the two
 * lookups the service validation needs: {@code code} uniqueness and the count of base ({@code is_base})
 * rows, each in an "other than this id" form so an update can exclude the row being edited.
 */
@Repository
public interface WorkerTypeDao extends AdminDao<WorkerTypeEntity, Long> {

    Optional<WorkerTypeEntity> findByCode(String code);

    /** True when another row (id &ne; {@code excludeId}) already uses {@code code}. */
    @Query("SELECT COUNT(w) > 0 FROM WorkerTypeEntity w WHERE w.code = :code AND w.id <> :excludeId")
    boolean existsByCodeAndIdNot(@Param("code") String code, @Param("excludeId") Long excludeId);

    /** True when any row uses {@code code} (create-time uniqueness check). */
    boolean existsByCode(String code);

    /** Number of base ({@code is_base = true}) rows across the whole dictionary. */
    @Query("SELECT COUNT(w) FROM WorkerTypeEntity w WHERE w.base = true")
    long countByBaseTrue();

    /** Number of base rows other than {@code excludeId} (update-time single-base check). */
    @Query("SELECT COUNT(w) FROM WorkerTypeEntity w WHERE w.base = true AND w.id <> :excludeId")
    long countByBaseTrueAndIdNot(@Param("excludeId") Long excludeId);

    /**
     * The ACTIVE worker-type tiers in stable display order ({@code order_no}, then {@code id}) — the
     * ordered dictionary the Margins read model uses for its column headers and per-tier cost columns
     * (FOR-05-06, design §B5). Inactive tiers are excluded so a deactivated hiring type drops out of
     * the matrix without deleting its history.
     *
     * @return the active tiers ordered by {@code orderNo}, then {@code id}
     */
    @Query("SELECT w FROM WorkerTypeEntity w WHERE w.active = true ORDER BY w.orderNo ASC, w.id ASC")
    List<WorkerTypeEntity> findActiveOrdered();
}
