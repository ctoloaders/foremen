package com.foremen.controller.model;

/**
 * Update response for an {@code AssortmentPosition} row: echoes the owning group id, the backing
 * material type id and the sort order. Mapped from {@code AssortmentPositionServiceExtendedModel}.
 */
public record AssortmentPositionUpdateResponse(Long assortmentGroupId, Long materialTypeId,
                                               Integer sortOrder) {}
