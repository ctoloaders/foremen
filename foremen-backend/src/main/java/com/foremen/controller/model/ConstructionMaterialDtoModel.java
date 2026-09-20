package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * List/read DTO for a construction material (one catalog row).
 *
 * <p>References are exposed as localized {@link RefDto} objects ({@code type}/{@code producer}/
 * {@code seller}/{@code unit}/{@code currency}), with {@code packages} as a {@code List<RefDto>}.
 * {@code imageUrl} is the resolved CDN URL (null when the entity has no image); the persisted GCS
 * object key is never returned on the read/list DTO. {@code priceRanges} carries the computed
 * per-({@code package}, {@code type}) MIN..MAX {@code retailNet} for each of this row's packages so
 * the UI can render the price-range column without a second round-trip (populated by the
 * {@code PriceRangeResolver}, task 7.1/7.2).
 */
public record ConstructionMaterialDtoModel(
        Long id,
        String name,
        RefDto type,
        RefDto producer,
        RefDto seller,
        List<RefDto> packages,
        RefDto unit,
        RefDto currency,
        BigDecimal purchasePrice,
        BigDecimal retailGross,
        BigDecimal retailNet,
        String website,
        String imageUrl,
        boolean active,
        List<PriceRangeEntry> priceRanges
) {}
