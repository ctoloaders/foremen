package com.foremen.service.estimate.materials;

import java.math.BigDecimal;

/**
 * A finishing material row of the estimate that has no chosen concrete product yet (a Placeholder
 * line, FOR-11-06 design 6.2). Read-only; produced by {@link MaterialsListService#placeholders(Long)}
 * from the same kosztorys matrix and the same quantity helpers as the Materials tab, so the quantity
 * rule is not duplicated.
 *
 * @param finishingTypeId the finishing material type id (row identity)
 * @param typeName        the localized type name
 * @param unit            the norm unit code, or {@code null}
 * @param qty             the effective quantity (ceiling for a per-unit basis, identity for a per-room
 *                        basis, whole pieces for the piece unit), summed over all rooms; no reserve
 * @param rangeMin        the lowest copied type price per physical unit, or {@code null}
 * @param rangeMax        the highest copied type price per physical unit, or {@code null}
 */
public record PlaceholderRowDto(
        Long finishingTypeId,
        String typeName,
        String unit,
        BigDecimal qty,
        BigDecimal rangeMin,
        BigDecimal rangeMax) {

    /** Stable row key of a placeholder row, {@code "T:" + finishingTypeId}. */
    public String sourceKey() {
        return "T:" + finishingTypeId;
    }
}
