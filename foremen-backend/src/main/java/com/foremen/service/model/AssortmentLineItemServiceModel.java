package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer read model for an {@code AssortmentLineItem} row: one curated catalog row within
 * an assortment group, carrying min/avg/max price per {@code OfferPackage} and a quantity assumed
 * against the 50 m² reference apartment (FOR-05-04, Requirement 6.1, 6.2). {@code name} is the
 * localized display name (PL fallback). {@code typicalProductId}/{@code typicalProductName} are
 * a nullable, assistive-only provenance reference (Requirement 6.6, 6.7) — they never drive
 * {@code minPrice}/{@code avgPrice}/{@code maxPrice}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssortmentLineItemServiceModel {
    private Long id;
    private Long assortmentGroupId;
    private String assortmentGroupName;
    private Long offerPackageId;
    private String offerPackageName;
    private String name;
    private BigDecimal minPrice;
    private BigDecimal avgPrice;
    private BigDecimal maxPrice;
    private BigDecimal qtyRef50;
    private Long typicalProductId;
    private String typicalProductName;
}
