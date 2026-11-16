package com.foremen.service.procurement.estimatesync;

import java.math.BigDecimal;

/**
 * One finishing row of the project's materials list as the sync sees it (FOR-11-06 design 6.2): either a
 * chosen-material row ({@code M:{materialId}}) or a placeholder row ({@code T:{finishingTypeId}}).
 *
 * @param sourceKey  stable row key
 * @param materialId the chosen finishing material id, or {@code null} for a placeholder
 * @param typeId     the finishing material type id (used by the work-category resolver), may be {@code null}
 * @param unitCode   the estimate unit code, may be {@code null}
 * @param quantity   the effective quantity
 * @param salePrice  the estimate net unit price of a chosen material, or {@code null}
 */
public record EstimateRow(String sourceKey, Long materialId, Long typeId, String unitCode,
                          BigDecimal quantity, BigDecimal salePrice) {
}
