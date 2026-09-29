package com.foremen.service.model;

import java.math.BigDecimal;

import com.foremen.dao.model.ConsumptionBranch;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for an {@code EstimateLineRoomMaterial} — the estimate's frozen
 * copied-price material line of a cell (FOR-05-05 design §B1, R13/R6/R4).
 *
 * <p>Flat FK ids paired with the copied {@code normQty}/{@code rangeMin}/{@code rangeMax} money band
 * and the optional chosen-concrete provenance ({@code concrete*MaterialId} + {@code concreteNet}). A
 * row with no concrete product is a Placeholder (contributes its range); a chosen product collapses
 * the line to its {@code concreteNet} point (R6.4, R6.6). This model backs the
 * {@link com.foremen.service.ProjectScopedService} CRUD contract of
 * {@code EstimateAssignmentService}; the orchestrator's bespoke {@code assign}/{@code unassign}/
 * {@code applyAssignments} methods operate on the entity graph directly rather than through this
 * model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLineRoomMaterialServiceModel {
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
