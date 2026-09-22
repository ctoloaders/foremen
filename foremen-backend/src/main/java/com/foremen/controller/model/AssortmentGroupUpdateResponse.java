package com.foremen.controller.model;

/**
 * Update response for an {@code AssortmentGroup} row: echoes the raw i18n fields and sort order.
 * Mapped from {@code AssortmentGroupServiceExtendedModel}.
 */
public record AssortmentGroupUpdateResponse(String nameRU, String namePL, Integer sortOrder) {}
