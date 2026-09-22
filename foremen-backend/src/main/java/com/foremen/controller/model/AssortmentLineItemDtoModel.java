package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Row DTO for an {@code AssortmentLineItem} (FOR-05-04, Requirement 6.1, 6.2). {@code name} is
 * the localized display name (PL fallback). {@code typicalProductId}/{@code typicalProductName}
 * are a nullable, assistive-only provenance reference (Requirement 6.6, 6.7) — they never drive
 * {@code minPrice}/{@code avgPrice}/{@code maxPrice}.
 */
public record AssortmentLineItemDtoModel(Long id, Long assortmentGroupId, String assortmentGroupName,
                                          Long offerPackageId, String offerPackageName, String name,
                                          BigDecimal minPrice, BigDecimal avgPrice, BigDecimal maxPrice,
                                          BigDecimal qtyRef50, Long typicalProductId,
                                          String typicalProductName) {}
