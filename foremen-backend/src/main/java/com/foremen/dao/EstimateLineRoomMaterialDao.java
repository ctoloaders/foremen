package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.EstimateLineRoomMaterialEntity;

/**
 * DAO for {@link EstimateLineRoomMaterialEntity} — the estimate's frozen copied-price material line
 * of a cell (FOR-05-05 design §B1, R13/R6/R4). Backs {@code EstimateAssignmentService}, the matrix
 * write orchestrator (design §B4): the generic {@link AdminDao} CRUD surface is sufficient because a
 * material line is always reached through its owning {@code (line, room)} room-qty, which owns it via
 * a cascade + orphan-removal collection ({@code EstimateLineRoomQtyEntity.materials}). Persisting the
 * owning graph therefore cascades to the material rows; this DAO exists to satisfy the
 * {@link ProjectScopedService} CRUD contract (with {@code getProjectIdPath()} resolving through
 * {@code roomQty.line.estimate.project}) and for the material-line-keyed reads used by the
 * add/remove/choose-concrete operations (tasks 5.2/5.3).
 */
@Repository
public interface EstimateLineRoomMaterialDao extends AdminDao<EstimateLineRoomMaterialEntity, Long> {
}
