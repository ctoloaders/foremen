package com.foremen.controller.model;

/**
 * Row DTO for an {@code AssortmentPosition} (FOR-05-04-UI assortment rework): its id, owning group
 * (id + localized name), backing material type (id + localized name) and optional sort order.
 */
public record AssortmentPositionDtoModel(Long id, Long assortmentGroupId, String assortmentGroupName,
                                         Long materialTypeId, String materialTypeName,
                                         Integer sortOrder) {}
