package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Write-path service model for a finishing material.
 *
 * <p>Carries the raw reference ids ({@code categoryId}/{@code materialId}/{@code typeId}/
 * {@code producerId}/{@code offerPackageIds}/{@code unitId}), the free-text {@code model}/{@code sku}/
 * {@code features}, the three prices, the {@code link}, the {@code photo} object key, and
 * {@code active}. Unlike {@code ConstructionMaterialServiceExtendedModel} there is no {@code code},
 * no localized {@code name}, no {@code seller}/{@code currency}, and no per-unit price. Mutable
 * ({@code @Data}) so the {@code FinishingMaterialService} normalize step (task 2.2) can default
 * {@code active} and resolve/validate references before the entity is created/updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinishingMaterialServiceExtendedModel {
    private Long id;
    private Long categoryId;
    private Long materialId;
    private Long typeId;
    private Long producerId;
    private Set<Long> offerPackageIds = new LinkedHashSet<>();
    private Long unitId;
    private String model;
    private String sku;
    private String features;
    private BigDecimal purchasePrice;
    private BigDecimal retailGross;
    private BigDecimal retailNet;
    private String link;
    private String photo;
    private boolean active = true;
}
