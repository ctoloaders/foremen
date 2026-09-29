package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * Update payload for an {@code AssortmentPosition} (FOR-05-04-UI assortment rework): the owning
 * group and the backing material type (both required) and an optional sort order.
 *
 * <p>The per-package work-item link is NOT set here (FOR-05-05 Wave 1b, #8): it is managed via the
 * dedicated per-package links endpoint, since a position may link to a different work per package.
 */
public record AssortmentPositionUpdateRequest(
    @NotNull Long assortmentGroupId,
    @NotNull Long materialTypeId,
    Integer sortOrder
) {}
