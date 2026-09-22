package com.foremen.controller.model;

/**
 * Row DTO for an {@code AssortmentGroup} (FOR-05-04, Requirement 6.1). {@code name} is the
 * localized display name (PL fallback), mirroring {@code WorkVolumeFormulaDtoModel}'s shape.
 */
public record AssortmentGroupDtoModel(Long id, String name, Integer sortOrder) {}
