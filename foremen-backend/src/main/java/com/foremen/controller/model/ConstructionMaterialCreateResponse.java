package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response returned after creating a construction material. Mirrors
 * {@link ConstructionMaterialDtoExtendedModel}: the localized {@link RefDto} references, the raw
 * reference ids, the prices, {@code website}, resolved {@code imageUrl}, {@code active}, and the
 * computed {@code priceRanges}.
 */
public record ConstructionMaterialCreateResponse(
        Long id,
        String name,
        String nameRU,
        String namePL,
        RefDto type,
        RefDto producer,
        RefDto seller,
        RefDto unit,
        RefDto currency,
        Long typeId,
        Long producerId,
        Long sellerId,
        Long unitId,
        Long currencyId,
        BigDecimal purchasePrice,
        BigDecimal retailGross,
        BigDecimal retailNet,
        String website,
        String imageUrl,
        boolean active,
        List<PriceRangeEntry> priceRanges
) {}
