package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * Create payload for an {@code AssortmentPosition} (FOR-05-04-UI assortment rework): the owning
 * group and the backing material type (both required — a position REQUIRES a material type), and
 * an optional sort order. Creating a global position makes it appear for ALL offer packages (with
 * empty prices until set via the package editor).
 */
public record AssortmentPositionCreateRequest(
    @NotNull Long assortmentGroupId,
    @NotNull Long materialTypeId,
    Integer sortOrder
) {}
