package com.foremen.service.model;

import java.math.BigDecimal;

/**
 * Service-layer write model for an {@code AssortmentLineItem} row (FOR-05-04, Requirement 6.1,
 * 6.2). Carries the raw i18n fields ({@code nameRU}/{@code namePL}), the flat FK ids the write
 * path resolves ({@code assortmentGroupId}/{@code offerPackageId}/nullable
 * {@code typicalProductId}), and the price/quantity fields. {@code typicalProductId} is
 * assistive/provenance only (Requirement 6.6, 6.7) — it never drives {@code minPrice}/
 * {@code avgPrice}/{@code maxPrice}.
 */
public record AssortmentLineItemServiceExtendedModel(Long id, Long assortmentGroupId, Long offerPackageId,
                                                      String nameRU, String namePL, BigDecimal minPrice,
                                                      BigDecimal avgPrice, BigDecimal maxPrice,
                                                      BigDecimal qtyRef50, Long typicalProductId) {
}
