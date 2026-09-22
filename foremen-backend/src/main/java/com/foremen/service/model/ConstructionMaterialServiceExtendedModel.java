package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-path service model for a construction material.
 *
 * <p>Carries the raw reference ids ({@code typeId}/{@code producerId}/{@code sellerId}/
 * {@code unitId}/{@code currencyId}), the localized {@code nameRU}/{@code namePL}, the three prices,
 * {@code website}, the {@code image} object key, and {@code active}. The material-side package
 * dimension was collapsed by FOR-05-04-UI (Requirement 5), so there is no longer any package
 * binding. Mutable ({@code @Data}) so the {@code ConstructionMaterialService} normalize step can
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
    private Long unitId;
    private Long currencyId;
    private BigDecimal purchasePrice;
    private BigDecimal retailGross;
    private BigDecimal retailNet;
    private String website;
    private String image;
    private boolean active = true;
}
