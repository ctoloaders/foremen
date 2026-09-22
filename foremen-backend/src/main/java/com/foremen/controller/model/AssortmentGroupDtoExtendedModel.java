package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Edit-form DTO for an {@code AssortmentGroup} (FOR-05-04, Requirement 6.1). Carries the raw
 * i18n fields the edit form pre-selects, mirroring {@code WorkVolumeFormulaDtoExtendedModel}.
 */
public record AssortmentGroupDtoExtendedModel(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Integer sortOrder
) {}
