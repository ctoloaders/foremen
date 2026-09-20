package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Response returned after creating a finishing material. Mirrors
 * {@link FinishingMaterialDtoExtendedModel}: the derived {@code label}, the localized {@link RefDto}
 * references, the raw reference ids, the free-text fields, the prices, {@code link}, resolved
 * {@code photoUrl}, and {@code active}.
 */
public record FinishingMaterialCreateResponse(
        Long id,
        String label,
        RefDto category,
        RefDto material,
        RefDto type,
        RefDto producer,
        List<RefDto> packages,
        RefDto unit,
        Long categoryId,
        Long materialId,
        Long typeId,
        Long producerId,
        Set<Long> offerPackageIds,
        Long unitId,
        String model,
        String sku,
        String features,
        BigDecimal purchasePrice,
        BigDecimal retailGross,
        BigDecimal retailNet,
        String link,
        String photoUrl,
        boolean active
) {}
