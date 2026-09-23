package com.foremen.controller.model;

/**
 * Edit-form DTO for an {@code AssortmentPosition} (FOR-05-04-UI assortment rework): the flat FK
 * ids the edit form pre-selects ({@code assortmentGroupId}, {@code materialTypeId}) and the
 * optional {@code sortOrder}.
 */
public record AssortmentPositionDtoExtendedModel(Long assortmentGroupId, Long materialTypeId,
                                                 Integer sortOrder) {}
