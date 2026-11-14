package com.foremen.service.procurement.position;

import java.math.BigDecimal;

import com.foremen.dao.model.VariantReason;

/**
 * FOR-11-00 (R21.3): the new current option of a position. When {@code materialId} equals the
 * current material, omitted prices keep their current value; for another material omitted prices
 * are cleared (they belonged to the retired item).
 */
public record ReplaceCommand(Long materialId, BigDecimal salePrice, BigDecimal plannedPurchasePrice,
                             VariantReason reason, String note) {
}
