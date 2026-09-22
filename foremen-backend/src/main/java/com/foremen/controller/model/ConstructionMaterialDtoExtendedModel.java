package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Extended DTO for a construction material (drives the edit form).
 *
 * <p>Exposes everything in {@link ConstructionMaterialDtoModel} — the localized {@link RefDto}
 * references, the prices, {@code website}, resolved {@code imageUrl}, {@code active}, and the
 * computed {@code priceRanges} — PLUS the raw reference ids the edit form needs to pre-select
 * ({@code typeId}/{@code producerId}/{@code sellerId}/{@code unitId}/{@code currencyId}). Like the
 * list DTO, it never returns the persisted GCS object key, only the resolved {@code imageUrl}.
 */
public record ConstructionMaterialDtoExtendedModel(
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
