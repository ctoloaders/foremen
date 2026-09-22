package com.foremen.controller.model;

/**
 * Create response for an {@code AssortmentGroup} row: echoes the raw i18n fields and sort order.
 * Mapped from {@code AssortmentGroupServiceExtendedModel}.
 */
public record AssortmentGroupCreateResponse(String nameRU, String namePL, Integer sortOrder) {}
