package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Write-path service model for a construction material.
 *
 * <p>Carries the raw reference ids ({@code typeId}/{@code producerId}/{@code sellerId}/
 * {@code offerPackageIds}/{@code unitId}/{@code currencyId}), the localized {@code nameRU}/
 * {@code namePL}, the three prices, {@code website}, the {@code image} object key, and {@code active}.
 * Mutable ({@code @Data}) so the {@code ConstructionMaterialService} normalize step (task 5.2) can
 * default {@code active} and resolve/validate references before the entity is created/updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstructionMaterialServiceExtendedModel {
    private Long id;
    private String nameRU;
    private String namePL;
    private Long typeId;
    private Long producerId;
    private Long sellerId;
    private Set<Long> offerPackageIds = new LinkedHashSet<>();
    private Long unitId;
    private Long currencyId;
    private BigDecimal purchasePrice;
    private BigDecimal retailGross;
    private BigDecimal retailNet;
    private String website;
    private String image;
    private boolean active = true;
}
