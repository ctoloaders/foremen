package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * Update payload for an {@code AssortmentPosition} (FOR-05-04-UI assortment rework): the owning
 * group and the backing material type (both required) and an optional sort order.
 */
public record AssortmentPositionUpdateRequest(
    @NotNull Long assortmentGroupId,
    @NotNull Long materialTypeId,
    Integer sortOrder
) {}
