package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** FOR-11-00 (R21.6): a known analog material; {@code salePrice} follows the price-visibility policy. */
public record VariantSuggestionDto(
        Long materialId,
        String model,
        String producer,
        String seller,
        String link,
        String photoUrl,
        BigDecimal salePrice,
        LocalDateTime lastKnownAt) {
}
