package com.foremen.service.procurement.position;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FOR-11-00 (R21.6): a known analog material (same project, same material type). {@code salePrice}
 * is already filtered by {@link PositionPriceVisibility}; {@code photo} is the raw object key.
 */
public record SuggestionView(
        Long materialId,
        String model,
        String producer,
        String seller,
        String link,
        String photo,
        BigDecimal salePrice,
        LocalDateTime lastKnownAt) {
}
