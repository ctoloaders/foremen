package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.foremen.dao.model.VariantReason;

/**
 * FOR-11-00 (R21.5): one retired option of a position. {@code unitSalePrice} and
 * {@code unitPlannedPurchasePrice} are {@code null} unless the price-visibility policy lets the
 * caller's audience see them.
 */
public record PositionVariantDto(
        Long id,
        Long positionId,
        Long materialId,
        String model,
        String producer,
        String seller,
        String link,
        String photoUrl,
        BigDecimal unitSalePrice,
        BigDecimal unitPlannedPurchasePrice,
        VariantReason reason,
        String note,
        LocalDateTime retiredAt,
        String retiredBy) {
}
