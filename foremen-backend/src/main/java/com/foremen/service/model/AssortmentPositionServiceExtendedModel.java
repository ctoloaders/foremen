package com.foremen.service.model;

/**
 * Service-layer write model for an {@code AssortmentPosition} row (FOR-05-04-UI assortment
 * rework). Carries the flat FK ids the write path resolves ({@code assortmentGroupId},
 * {@code materialTypeId}) and the optional {@code sortOrder}. A position REQUIRES a material type,
 * so {@code materialTypeId} is mandatory on create.
 */
public record AssortmentPositionServiceExtendedModel(Long id, Long assortmentGroupId,
                                                     Long materialTypeId, Integer sortOrder) {
}
