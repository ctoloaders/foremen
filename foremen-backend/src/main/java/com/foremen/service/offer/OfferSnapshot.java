package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.service.estimate.CellAiMeta;

/**
 * FOR-05-07 Batch 2: the immutable, matrix-shaped snapshot of an estimate stored as JSONB on the offer
 * when it is prepared (works grouped by category, rooms, quantities, effective and base unit prices,
 * totals and the project VAT rate at that moment). Staff-only fields ({@code baseUnitPrice},
 * {@code baseValueNet}) are removed for CLIENT by {@link OfferSnapshotViews}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OfferSnapshot(
        int version,
        String capturedAt,
        BigDecimal vatRatePct,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        List<Room> rooms,
        List<Group> groups,
        String appliedPackageCode,
        String appliedPackageNameRu,
        String appliedPackageNamePl,
        BigDecimal packageSubtotal) {

    /** Version 1 shape (no package marker); kept for readers and tests that build snapshots by hand. */
    public OfferSnapshot(int version, String capturedAt, BigDecimal vatRatePct, BigDecimal totalNet,
                         BigDecimal totalVat, BigDecimal totalGross, List<Room> rooms, List<Group> groups) {
        this(version, capturedAt, vatRatePct, totalNet, totalVat, totalGross, rooms, groups,
                null, null, null, null);
    }

    /** Shape with the package marker but without the package subtotal (D7 added it). */
    public OfferSnapshot(int version, String capturedAt, BigDecimal vatRatePct, BigDecimal totalNet,
                         BigDecimal totalVat, BigDecimal totalGross, List<Room> rooms, List<Group> groups,
                         String appliedPackageCode, String appliedPackageNameRu, String appliedPackageNamePl) {
        this(version, capturedAt, vatRatePct, totalNet, totalVat, totalGross, rooms, groups,
                appliedPackageCode, appliedPackageNameRu, appliedPackageNamePl, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Room(Long id, String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(Long categoryId, String nameRu, String namePl, List<Row> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            Long workItemId,
            String nameRu,
            String namePl,
            String unitCode,
            String unitNameRu,
            String unitNamePl,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal baseUnitPrice,
            BigDecimal valueNet,
            BigDecimal baseValueNet,
            List<Cell> cells,
            BigDecimal unitPriceOverride,
            String comment,
            Long workPriceId,
            Integer lineNo,
            Long lineId,
            Boolean packageCovered) {

        /** Shape before D7 (no estimate line id, no package coverage flag). */
        public Row(Long workItemId, String nameRu, String namePl, String unitCode, String unitNameRu,
                   String unitNamePl, BigDecimal quantity, BigDecimal unitPrice, BigDecimal baseUnitPrice,
                   BigDecimal valueNet, BigDecimal baseValueNet, List<Cell> cells,
                   BigDecimal unitPriceOverride, String comment, Long workPriceId, Integer lineNo) {
            this(workItemId, nameRu, namePl, unitCode, unitNameRu, unitNamePl, quantity, unitPrice,
                    baseUnitPrice, valueNet, baseValueNet, cells, unitPriceOverride, comment, workPriceId,
                    lineNo, null, null);
        }

        /** Version 1 shape (no restore data). */
        public Row(Long workItemId, String nameRu, String namePl, String unitCode, String unitNameRu,
                   String unitNamePl, BigDecimal quantity, BigDecimal unitPrice, BigDecimal baseUnitPrice,
                   BigDecimal valueNet, BigDecimal baseValueNet, List<Cell> cells) {
            this(workItemId, nameRu, namePl, unitCode, unitNameRu, unitNamePl, quantity, unitPrice,
                    baseUnitPrice, valueNet, baseValueNet, cells, null, null, null, null, null, null);
        }
    }

    /**
     * One room cell. Version 3 (FOR-05-04, Requirement 21.3) adds the override flag, the engine facts and
     * the staff-only AI explanation {@code ai}; version 2 cells carry only {@code roomId} and
     * {@code quantity} and still read (the new components are {@code null}, meaning "not overridden").
     * Requirement 23.2 adds the staff-only consumable-material lines {@code materials}; a cell without
     * them ({@code null}) restores without materials.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(
            Long roomId,
            BigDecimal quantity,
            Boolean volumeOverridden,
            String formulaKey,
            Boolean fallbackUsed,
            CellAiMeta ai,
            List<MaterialLine> materials) {

        /** Shape of task 3.11 (no material lines). */
        public Cell(Long roomId, BigDecimal quantity, Boolean volumeOverridden, String formulaKey,
                    Boolean fallbackUsed, CellAiMeta ai) {
            this(roomId, quantity, volumeOverridden, formulaKey, fallbackUsed, ai, null);
        }

        /** Version 2 shape (no override flag, no engine facts, no AI explanation, no materials). */
        public Cell(Long roomId, BigDecimal quantity) {
            this(roomId, quantity, null, null, null, null, null);
        }
    }

    /**
     * FOR-05-04 (Requirement 23.2): the snapshot copy of one {@code EstimateLineRoomMaterial}, with
     * everything needed to rebuild the line exactly. {@code branch} is {@code construction} or
     * {@code finishing} and selects which of the two type ids (and product ids) is set. {@code quantity}
     * is the effective physical quantity at snapshot time (display only; a restore recomputes it from
     * {@code normQty}, {@code consumptionBasis}, the cell volume and the override). {@code qtyOverridden}
     * and {@code appliedFromPackage} are written only when {@code true}. Staff-only: never mapped into
     * client read models.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MaterialLine(
            String branch,
            Long constructionTypeId,
            Long finishingTypeId,
            BigDecimal normQty,
            String consumptionBasis,
            BigDecimal rangeMin,
            BigDecimal rangeMax,
            Boolean qtyOverridden,
            BigDecimal manualQty,
            BigDecimal quantity,
            Long concreteConstructionMaterialId,
            Long concreteFinishingMaterialId,
            BigDecimal concreteNet,
            Boolean appliedFromPackage,
            Long sourceConstructionMaterialId,
            Long sourceFinishingMaterialId) {

        /** The type id of the line's branch. */
        public Long typeId() {
            return "finishing".equals(branch) ? finishingTypeId : constructionTypeId;
        }

        /** Whether the quantity is a manual override. */
        public boolean overridden() {
            return Boolean.TRUE.equals(qtyOverridden);
        }
    }
}
