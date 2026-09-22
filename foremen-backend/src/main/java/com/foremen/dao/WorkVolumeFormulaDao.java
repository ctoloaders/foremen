package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkVolumeFormulaEntity;

/**
 * Plain {@link AdminDao} for {@link WorkVolumeFormulaEntity} (FOR-05-04, task 18.3): a work
 * item's optional default volume formula. The generic FOR-04-01 query DSL (e.g.
 * {@code workItem.id==}) is sufficient for list/lookup, mirroring {@code WorkMaterialConsumptionDao}.
 *
 * <p>{@link #findByWorkItemId} and {@link #findAllWithWorkItem} additionally support the
 * catalog-wide cycle check on save (task 20.1, R3.3) and room-scoped formula derivation (task
 * 20.1, R5.3), which both need every persisted default formula's owning {@code WorkItem.code}.
 */
@Repository
public interface WorkVolumeFormulaDao extends AdminDao<WorkVolumeFormulaEntity, Long> {

    @Query("SELECT f FROM WorkVolumeFormulaEntity f WHERE f.workItem.id = :workItemId")
    Optional<WorkVolumeFormulaEntity> findByWorkItemId(@Param("workItemId") Long workItemId);

    @Query("SELECT f FROM WorkVolumeFormulaEntity f JOIN FETCH f.workItem")
    List<WorkVolumeFormulaEntity> findAllWithWorkItem();
}
