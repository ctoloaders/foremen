package com.foremen.service.procurement.position;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.foremen.dao.model.VariantReason;

/**
 * FOR-11-00 (R21.5): one retired option of a position. Money is already filtered by
 * {@link PositionPriceVisibility} for the caller's audience. {@code photo} is the raw object key.
 */
public record VariantView(
        Long id,
        Long positionId,
        Long materialId,
        String model,
        String producer,
        String seller,
        String link,
        String photo,
        BigDecimal unitSalePrice,
        BigDecimal unitPlannedPurchasePrice,
        VariantReason reason,
        String note,
        LocalDateTime retiredAt,
        String retiredBy) {
}
