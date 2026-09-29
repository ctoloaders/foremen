package com.foremen.service.model;

/**
 * Service-layer write model for an {@code AssortmentPosition} row (FOR-05-04-UI assortment
 * rework). Carries the flat FK ids the write path resolves ({@code assortmentGroupId},
 * {@code materialTypeId}) and the optional {@code sortOrder}. A position REQUIRES a material type,
 * so {@code materialTypeId} is mandatory on create.
 *
 * <p>The per-package work-item link is NOT carried here (FOR-05-05 Wave 1b, #8): a position links
 * to a possibly different work per package, managed via the dedicated per-package links endpoint,
 * not the generic position CRUD.
 */
public record AssortmentPositionServiceExtendedModel(Long id, Long assortmentGroupId,
                                                     Long materialTypeId, Integer sortOrder) {
}
