package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read model for the single-package grouped assortment editor (FOR-05-04-UI). Carries the target
 * package (code, localized name, the persisted {@code zlM2} cache) plus the live min/avg/max TOTAL
 * zł/m² band computed from current data, and every assortment group — sorted by {@code sortOrder}
 * then localized name — with its {@code referenceQty}/{@code referenceUnit} and ALL of the group's
 * (global) positions. Each position's min/avg/max are THIS package's prices (null when no price
 * row exists yet for the package).
 *
 * @param packageCode   the target {@code OfferPackage} code
 * @param packageName   the target package's localized display name (PL fallback)
 * @param zlM2          the persisted denormalized package zł/m² cache; nullable ({@code null} =
 *                      not yet computed/saved)
 * @param totalMinZlM2  the live TOTAL zł/m² using each position's {@code minPrice}
 * @param totalAvgZlM2  the live TOTAL zł/m² using each position's {@code avgPrice} (the canonical
 *                      package price)
 * @param totalMaxZlM2  the live TOTAL zł/m² using each position's {@code maxPrice}
 * @param groups        the assortment groups (sorted), each with its positions
 */
public record PackageAssortmentEditorResponse(
        String packageCode,
        String packageName,
        BigDecimal zlM2,
        BigDecimal totalMinZlM2,
        BigDecimal totalAvgZlM2,
        BigDecimal totalMaxZlM2,
        List<Group> groups) {

    /**
     * A single assortment group in the editor: its id, localized name, sort order, single
     * reference quantity + unit, and ALL of the group's (global) positions.
     */
    public record Group(
            Long groupId,
            String name,
            Integer sortOrder,
            BigDecimal referenceQty,
            String referenceUnit,
            List<Position> positions) {
    }

    /**
     * A single assortment position for the current package: its id, its backing material type
     * (id + localized name), the min/avg/max price band for THIS package (null when no price row
     * exists yet), and the OPTIONAL per-band quantity overrides ({@code minQty}/{@code avgQty}/
     * {@code maxQty} — null when that band uses the group's {@code referenceQty}).
     */
    public record Position(
            Long positionId,
            Long materialTypeId,
            String materialTypeName,
            BigDecimal minPrice,
            BigDecimal avgPrice,
            BigDecimal maxPrice,
            BigDecimal minQty,
            BigDecimal avgQty,
            BigDecimal maxQty) {
    }
}
