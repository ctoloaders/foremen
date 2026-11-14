package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Response after creating a Project_Material (same shape as the extended DTO). */
public record ProjectMaterialCreateResponse(
        Long id,
        Long projectId,
        Long sellerId,
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
        BigDecimal costNet,
        String link,
        String photoUrl,
        boolean active
) {}
