package com.foremen.service.model;

import java.math.BigDecimal;

import com.foremen.dao.model.ConsumptionBranch;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-path (and extended-read) service model for an {@code EstimateLineRoomMaterial} — the
 * estimate's frozen copied-price material line of a cell (FOR-05-05 design §B1, R13/R6/R4).
 *
 * <p>Carries the flat reference ids the write path resolves ({@code roomQtyId}, the branch's type id,
 * the provenance/concrete material ids) plus the copied money band ({@code normQty},
 * {@code rangeMin}/{@code rangeMax}) and the chosen-product point ({@code concreteNet}). It satisfies
 * the {@link com.foremen.service.ProjectScopedService} CRUD contract of
 * {@code EstimateAssignmentService}; the orchestrator's bespoke {@code assign}/{@code unassign}/
 * {@code applyAssignments} methods mutate the entity graph directly rather than through this model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLineRoomMaterialServiceExtendedModel {
    private Long id;
    private Long roomQtyId;
    private ConsumptionBranch branch;
    private Long constructionTypeId;
    private Long finishingTypeId;
    private BigDecimal normQty;
    private BigDecimal rangeMin;
    private BigDecimal rangeMax;
    private Long sourceConstructionMaterialId;
    private Long sourceFinishingMaterialId;
    private Long concreteConstructionMaterialId;
    private Long concreteFinishingMaterialId;
    private BigDecimal concreteNet;
}
