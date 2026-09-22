package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Update response for an {@code AssortmentLineItem} row: echoes the raw i18n fields, flat FK
 * ids, and price/quantity fields. Mapped from {@code AssortmentLineItemServiceExtendedModel}.
 */
public record AssortmentLineItemUpdateResponse(Long assortmentGroupId, Long offerPackageId,
                                                String nameRU, String namePL, BigDecimal minPrice,
                                                BigDecimal avgPrice, BigDecimal maxPrice,
                                                BigDecimal qtyRef50, Long typicalProductId) {}
