package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Row DTO for an {@code AssortmentGroup} (FOR-05-04, Requirement 6.1). {@code name} is the
 * localized display name (PL fallback), mirroring {@code WorkVolumeFormulaDtoModel}'s shape.
 * {@code referenceQty}/{@code referenceUnit} are the group's single reference quantity + unit
 * (FOR-05-04-UI).
 */
public record AssortmentGroupDtoModel(Long id, String name, Integer sortOrder,
                                      BigDecimal referenceQty, String referenceUnit) {}
