package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Update response for an {@code AssortmentGroup} row: echoes the raw i18n fields, sort order and
 * the group's reference quantity + unit. Mapped from {@code AssortmentGroupServiceExtendedModel}.
 */
public record AssortmentGroupUpdateResponse(String nameRU, String namePL, Integer sortOrder,
                                            BigDecimal referenceQty, String referenceUnit) {}
