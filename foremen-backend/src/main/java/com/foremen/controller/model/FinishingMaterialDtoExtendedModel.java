package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Extended DTO for a finishing material (drives the edit form).
 *
 * <p>Exposes everything in {@link FinishingMaterialDtoModel} — the derived {@code label}, the
 * localized {@link RefDto} references, the free-text fields, the prices, {@code link}, resolved
 * {@code photoUrl}, and {@code active} — PLUS the raw reference ids the edit form needs to pre-select
 * ({@code categoryId}/{@code materialId}/{@code typeId}/{@code producerId}/{@code offerPackageIds}/
 * {@code unitId}). Like the list DTO, it never returns the persisted GCS object key, only the
 * resolved {@code photoUrl}.
 */
public record FinishingMaterialDtoExtendedModel(
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
