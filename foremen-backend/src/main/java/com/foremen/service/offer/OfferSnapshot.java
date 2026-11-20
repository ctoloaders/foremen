package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
        String appliedPackageNamePl) {

    /** Version 1 shape (no package marker); kept for readers and tests that build snapshots by hand. */
    public OfferSnapshot(int version, String capturedAt, BigDecimal vatRatePct, BigDecimal totalNet,
                         BigDecimal totalVat, BigDecimal totalGross, List<Room> rooms, List<Group> groups) {
        this(version, capturedAt, vatRatePct, totalNet, totalVat, totalGross, rooms, groups, null, null, null);
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
            Integer lineNo) {

        /** Version 1 shape (no restore data). */
        public Row(Long workItemId, String nameRu, String namePl, String unitCode, String unitNameRu,
                   String unitNamePl, BigDecimal quantity, BigDecimal unitPrice, BigDecimal baseUnitPrice,
                   BigDecimal valueNet, BigDecimal baseValueNet, List<Cell> cells) {
            this(workItemId, nameRu, namePl, unitCode, unitNameRu, unitNamePl, quantity, unitPrice,
                    baseUnitPrice, valueNet, baseValueNet, cells, null, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(Long roomId, BigDecimal quantity) {
    }
}
