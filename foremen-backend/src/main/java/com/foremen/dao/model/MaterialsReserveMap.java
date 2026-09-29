package com.foremen.dao.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Project-level reserve map (FOR-05-05b, R4.2), bound to the {@code estimates.materials_reserve_map
 * jsonb} column via {@code @JdbcTypeCode(SqlTypes.JSON)} on {@link EstimateEntity} and
 * serialized/deserialized by Jackson (mirroring {@link RoomGeometry}).
 *
 * <p>Keyed by {@code materialId}, each entry carries the material's authoritative reserve
 * {@link ReserveEntry#percent() percent} plus its last-computed totals. A {@code null} column, an
 * absent key, or a {@code null}/{@code 0} percent all mean <em>identity</em> (no reserve) for that
 * material (R4.5).
 *
 * <p>Only {@code percent} is authoritative input; {@code asIsQty}/{@code effectiveQty}/
 * {@code bruttoTotal} are computed on each write for display/reuse but are <strong>never trusted on
 * read</strong> — the read assembler always recomputes from the live kosztorys, so a stale cached
 * total never drives display (R12.5).
 *
 * @param byMaterialId reserve entries keyed by concrete material id; {@code null} ⇒ empty map
 */
public record MaterialsReserveMap(Map<Long, ReserveEntry> byMaterialId) {

    /**
     * One material's reserve percent plus its last-computed totals (R4.2).
     *
     * @param percent      reserve percent (0..100, ≤2 decimals); {@code null}/absent ⇒ identity (R4.5, R9.3)
     * @param asIsQty      last-computed as-is aggregate quantity (cache/display only)
     * @param effectiveQty last-computed {@code Effective_Quantity} (cache/display only)
     * @param bruttoTotal  last-computed effective brutto price total (cache/display only)
     */
    public record ReserveEntry(
            BigDecimal percent,
            BigDecimal asIsQty,
            BigDecimal effectiveQty,
            BigDecimal bruttoTotal) {
    }
}
