package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Extended DTO for a construction material (drives the edit form).
 *
 * <p>Exposes everything in {@link ConstructionMaterialDtoModel} — the localized {@link RefDto}
 * references, the prices, {@code website}, resolved {@code imageUrl}, {@code active}, and the
 * computed {@code priceRanges} — PLUS the raw reference ids the edit form needs to pre-select
 * ({@code typeId}/{@code producerId}/{@code sellerId}/{@code offerPackageIds}/{@code unitId}/
 * {@code currencyId}). Like the list DTO, it never returns the persisted GCS object key, only the
 * resolved {@code imageUrl}.
 */
public record ConstructionMaterialDtoExtendedModel(
        Long id,
        String name,
        String nameRU,
        String namePL,
        RefDto type,
        RefDto producer,
        RefDto seller,
        List<RefDto> packages,
        RefDto unit,
        RefDto currency,
        Long typeId,
        Long producerId,
        Long sellerId,
        Set<Long> offerPackageIds,
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
