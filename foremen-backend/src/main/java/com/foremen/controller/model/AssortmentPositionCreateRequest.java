package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * Create payload for an {@code AssortmentPosition} (FOR-05-04-UI assortment rework): the owning
 * group and the backing material type (both required — a position REQUIRES a material type), and
 * an optional sort order. Creating a global position makes it appear for ALL offer packages (with
 * empty prices until set via the package editor).
 *
 * <p>The per-package work-item link is NOT set here (FOR-05-05 Wave 1b, #8): it is managed via the
 * dedicated per-package links endpoint, since a position may link to a different work per package.
 */
public record AssortmentPositionCreateRequest(
    @NotNull Long assortmentGroupId,
    @NotNull Long materialTypeId,
    Integer sortOrder
) {}
